"""Synthetic isolated datasets; no provider calls or real dataset writes."""
import copy
import json
import sys
import subprocess
from pathlib import Path
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import casebook_intake as tool
import build_incident_dataset as builder
from test_incident_publish import setup


def save(root, name, value):
    path = root / name; path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(tool.encoded(value))
    return {"path": name, "sha256": tool.digest(path.read_bytes())}


def snapshot(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in root.rglob('*') if p.is_file()}


@pytest.fixture
def dataset(tmp_path):
    setup(tmp_path)
    origin = save(tmp_path, 'research/source.json', {"transcript": [
        {"text": "합성 평가 표현", "startMs": 0, "endMs": 1000},
        {"text": "합성 후속 상황", "startMs": 1000, "endMs": 2000}]})
    review = save(tmp_path, 'research/reviews.json', {"trainingUseAuthorized": False, "reviews": [
        {"eventId": "e1", "anchorId": "stt-index-0", "decision": "PASS", "scope": "합성 제공 문맥", "userReply": "PASS"}]})
    source = {"schemaVersion": "context-transcript-source-1", "familyId": "development-seed",
        "sourceKind": "USER_SUPPLIED_SCRIPT_NOT_AUDIO_VERIFIED", "sourceGroupId": "synthetic-video",
        "origin": {**origin, "videoId": 1}, "audioVerified": False,
        "developmentReview": {"decision": "PASS", "basis": "USER_APPROVED_DEVELOPMENT_BOUNDARY", "scope": "합성 제공 문맥",
                              "approvalRecord": {**review, "eventIds": ["e1"]}},
        "segments": [{"id": "stt-index-0", "text": "합성 평가 표현", "startMs": 0, "endMs": 1000},
                     {"id": "stt-index-1", "text": "합성 후속 상황", "startMs": 1000, "endMs": 2000}]}
    ref = save(tmp_path, 'context-sources/seed.json', source)
    unit = {"schemaVersion": "context-reference-unit-2", "id": "dev#seed", "familyId": "development-seed",
        "status": "DRAFT", "humanVerified": False, "trainingUseAuthorized": False,
        "referencePurpose": "DEVELOPMENT_BOUNDARY_NOT_PUBLIC_CONTROVERSY", "developmentDecision": "PASS",
        "mechanismIds": ["mechanism"], "routingKeys": ["reaction-target-boundary"],
        "source": {"kind": "STORED_TRANSCRIPT_SNAPSHOT", **ref}, "utteranceIds": ["stt-index-0", "stt-index-1"],
        "roles": {"situationIds": ["stt-index-1"], "evaluationIds": ["stt-index-0"]},
        "retrievalText": "합성 평가 표현 후속 상황", "interpretation": "합성 정상 해석", "boundary": "합성 적용 경계", "normalContrasts": []}
    save(tmp_path, 'context-units/seed.json', unit)
    save(tmp_path, 'context-relations/seed.json', {"schemaVersion": "context-relation-profile-1", "unitId": unit['id'],
        "status": "DRAFT", "humanVerified": False, "trainingUseAuthorized": False,
        "sourceSha256": ref['sha256'], "utteranceIds": unit['utteranceIds'], "situation": "합성 상황",
        "target": "합성 대상", "evaluation": unit['interpretation'], "linkage": "합성 연결"})
    save(tmp_path, 'definitions/context-routing.json', {"schemaVersion": "context-routing-keys-1", "keys": [
        {"id": "reaction-target-boundary", "description": "합성 반응 경계"}]})
    save(tmp_path, 'definitions/runtime-selection.json', {"schemaVersion": "incident-runtime-selection-1", "targetSchema": "review-guidelines-7"})
    builder.publish(tmp_path)
    return tmp_path


def spec(root, action='ADD'):
    value = {"schemaVersion": "casebook-intake-1", "action": action, "slug": "new-boundary",
        "id": "dev#new", "familyId": "development-new", "sourceGroupId": "synthetic-video", "decision": "PASS",
        "source": {"path": "research/source.json", "sha256": tool.digest((root / 'research/source.json').read_bytes()),
                   "format": "RESULT_TRANSCRIPT", "kind": "USER_SUPPLIED_SCRIPT_NOT_AUDIO_VERIFIED", "videoId": 1},
        "approval": {"path": "research/reviews.json", "sha256": tool.digest((root / 'research/reviews.json').read_bytes()), "eventIds": ["e1"]},
        "segments": [{"index": 0, "id": "stt-index-0", "role": "EVALUATION"},
                     {"index": 1, "id": "stt-index-1", "role": "SITUATION"}],
        "mechanismIds": ["mechanism"], "routingKeys": ["reaction-target-boundary"],
        "retrievalText": "합성 평가 표현 후속 상황", "interpretation": "합성 보강 해석", "boundary": "합성 적용 경계",
        "relation": {"situation": "합성 상황", "target": "합성 대상", "linkage": "합성 연결"},
        "relatedUnitIds": ["dev#seed"]}
    if action == 'UPDATE':
        value.update(slug='seed', id='dev#seed', familyId='development-seed', relatedUnitIds=[],
                     expectedUnitSha256=tool.digest((root / 'context-units/seed.json').read_bytes()))
    return value


def fake_audit(plan):
    return {"planSha256": plan['sha'], "runtimeSha256": plan['summary']['candidateRuntimeSha256'],
            "retrieved": True, "providerCalls": 0, "testFixtureNotJavaExecution": True}


@pytest.mark.parametrize('count,accepted', [(11, True), (12, False)])
def test_long_pass_keeps_every_source_line(dataset, count, accepted):
    value = spec(dataset)
    value['source'].update(save(dataset, 'research/long.json', {'transcript': [
        {'text': f'합성 문맥 {i}', 'startMs': i * 1000, 'endMs': (i + 1) * 1000}
        for i in range(count)]}))
    value['segments'] = [{'index': i, 'id': f'stt-index-{i}',
                          'role': 'EVALUATION' if i == 0 else 'SITUATION'} for i in range(count)]
    lines, _ = tool.source_lines(dataset, value) if accepted else (None, None)
    if accepted:
        assert len(lines) == count
        plan = tool.plan(dataset, value)
        example = next(e for e in plan['payload']['candidateRuntime']['examples'] if e['id'] == value['id'])
        assert len(example['context']['flow']) == count + 1
    else:
        with pytest.raises(tool.PilotError, match='COMPLETE_CONTEXT'):
            tool.source_lines(dataset, value)


def test_preview_is_deterministic_no_write_and_shows_exact_changed_paths(dataset):
    before = snapshot(dataset); first = tool.plan(dataset, spec(dataset)); second = tool.plan(dataset, spec(dataset))
    assert first == second and snapshot(dataset) == before
    assert first['summary']['examplesBefore'] == 2 and first['summary']['examplesAfter'] == 3
    assert first['summary']['changedFiles'] == ['context-relations/new-boundary.json', 'context-sources/new-boundary.json', 'context-units/new-boundary.json']
    assert first['summary']['relatedUnitIds'] == ['dev#seed']
    assert first['summary']['networkCalls'] == 0


def test_apply_add_publishes_verified_runtime_and_private_receipt(dataset):
    value = spec(dataset); plan = tool.plan(dataset, value)
    result = tool.apply_plan(dataset, value, plan['sha'], fake_audit)
    assert result['applied'] and builder.verify(dataset)['examples'] == 3
    unit = json.loads((dataset / 'context-units/new-boundary.json').read_bytes())
    assert unit['humanVerified'] is False and unit['trainingUseAuthorized'] is False
    receipt = json.loads((dataset / plan['receipt']).read_bytes())
    assert receipt['intake']['relatedUnitIds'] == ['dev#seed'] and receipt['retrievalAudit']['providerCalls'] == 0
    assert not (dataset / '.casebook-intake.lock').exists()


def test_update_preserves_source_and_label_and_only_writes_authored_views(dataset):
    value = spec(dataset, 'UPDATE'); original = (dataset / 'context-sources/seed.json').read_bytes()
    plan = tool.plan(dataset, value)
    assert plan['summary']['examplesBefore'] == plan['summary']['examplesAfter'] == 2
    assert plan['summary']['changedFiles'] == ['context-relations/seed.json', 'context-units/seed.json']
    tool.apply_plan(dataset, value, plan['sha'], fake_audit)
    assert (dataset / 'context-sources/seed.json').read_bytes() == original
    assert builder.verify(dataset)['examples'] == 2
    receipt = json.loads((dataset / plan['receipt']).read_bytes())
    assert json.loads(receipt['previousFiles']['context-units/seed.json'])['interpretation'] == '합성 정상 해석'


def test_hold_keeps_runtime_identical_and_never_audits(dataset):
    value = spec(dataset); value.update(action='HOLD', decision='UNCERTAIN', missingInformation=['원음 확인'])
    ref = save(dataset, 'research/hold-review.json', {'reviews': [{'eventId': 'h1', 'anchorId': 'stt-index-0',
        'decision': 'UNCERTAIN', 'scope': '원음 불명확', 'userReply': '정보 부족'}]})
    value['approval'] = {**ref, 'eventIds': ['h1']}
    before = (dataset / 'guidelines/runtime.json').read_bytes(); plan = tool.plan(dataset, value)
    assert not plan['summary']['runtimeChanged'] and plan['summary']['changedFiles'] == []
    tool.apply_plan(dataset, value, plan['sha'], lambda _: pytest.fail('HOLD must not run Java'))
    assert (dataset / 'guidelines/runtime.json').read_bytes() == before
    assert json.loads((dataset / plan['receipt']).read_bytes())['state'] == 'HOLD_NOT_RUNTIME'
    assert not (dataset / 'context-units/new-boundary.json').exists()


@pytest.mark.parametrize('change', ['unknown', 'source-hash', 'approval-hash', 'label', 'anchor', 'path', 'slug', 'role', 'order', 'raw-time', 'mechanism', 'routing', 'related', 'implicit-uncertain'])
def test_invalid_intakes_never_change_dataset(dataset, change):
    value = spec(dataset)
    if change == 'unknown': value['runtimeEligible'] = True
    if change == 'source-hash': value['source']['sha256'] = '0' * 64
    if change == 'approval-hash': value['approval']['sha256'] = '0' * 64
    if change == 'label': value['decision'] = 'REVIEW_REQUIRED'
    if change == 'anchor': value['segments'] = value['segments'][1:]; value['segments'][0]['role'] = 'EVALUATION'
    if change == 'path': value['source']['path'] = '../outside.json'
    if change == 'slug': value['slug'] = '../outside'
    if change == 'role': value['segments'][0]['role'] = 'INVENTED'
    if change == 'order': value['segments'].reverse()
    if change == 'raw-time': value['segments'][0]['startMs'] = 100
    if change == 'mechanism': value['mechanismIds'] = ['unknown']
    if change == 'routing': value['routingKeys'] = ['unknown']
    if change == 'related': value['relatedUnitIds'] = ['missing']
    if change == 'implicit-uncertain': value['decision'] = 'UNCERTAIN'
    before = snapshot(dataset)
    with pytest.raises((ValueError, KeyError, TypeError)): tool.plan(dataset, value)
    assert snapshot(dataset) == before


def test_stale_preview_or_source_rejected_before_write(dataset):
    value = spec(dataset); plan = tool.plan(dataset, value); before = snapshot(dataset)
    with pytest.raises(ValueError, match='PLAN_HASH'): tool.apply_plan(dataset, value, '0' * 64, fake_audit)
    assert snapshot(dataset) == before
    value['boundary'] = '다른 경계'
    with pytest.raises(ValueError, match='PLAN_HASH'): tool.apply_plan(dataset, value, plan['sha'], fake_audit)
    assert snapshot(dataset) == before


def test_stale_update_or_identity_change_is_refused(dataset):
    for field, new in [('id', 'changed'), ('expectedUnitSha256', '0' * 64), ('familyId', 'changed')]:
        value = spec(dataset, 'UPDATE'); value[field] = new
        before = snapshot(dataset)
        with pytest.raises(ValueError): tool.plan(dataset, value)
        assert snapshot(dataset) == before


def test_failed_audit_never_writes_canonical_data(dataset):
    value = spec(dataset); plan = tool.plan(dataset, value); before = snapshot(dataset)
    def fail(_): raise tool.PilotError('EXPECTED_REFERENCE_NOT_RETRIEVED')
    with pytest.raises(ValueError): tool.apply_plan(dataset, value, plan['sha'], fail)
    assert snapshot(dataset) == before


def test_failed_publication_restores_canonical_files_and_runtime(dataset, monkeypatch):
    value = spec(dataset); plan = tool.plan(dataset, value); before = snapshot(dataset)
    def fail(_): raise RuntimeError('injected publish failure')
    monkeypatch.setattr(builder, 'publish', fail)
    with pytest.raises(RuntimeError): tool.apply_plan(dataset, value, plan['sha'], fake_audit)
    assert snapshot(dataset) == before
    assert builder.verify(dataset)['examples'] == 2


def test_keyboard_interrupt_rolls_back_and_releases_lock(dataset, monkeypatch):
    value = spec(dataset); preview = tool.plan(dataset, value); before = snapshot(dataset)
    def cancel(_): raise KeyboardInterrupt()
    monkeypatch.setattr(builder, 'publish', cancel)
    with pytest.raises(KeyboardInterrupt): tool.apply_plan(dataset, value, preview['sha'], fake_audit)
    assert snapshot(dataset) == before


def test_partial_publication_restores_previous_runtime_pair(dataset, monkeypatch):
    value = spec(dataset); plan = tool.plan(dataset, value); before = tool.baseline(dataset)
    original = builder.publish
    def wrong_manifest(root): original(root); return {}
    monkeypatch.setattr(builder, 'publish', wrong_manifest)
    with pytest.raises(ValueError, match='PUBLISHED_PLAN'): tool.apply_plan(dataset, value, plan['sha'], fake_audit)
    assert tool.baseline(dataset) == before
    assert not (dataset / 'context-units/new-boundary.json').exists()


def test_lock_is_not_removed_when_another_intake_owns_it(dataset):
    lock = dataset / '.casebook-intake.lock'; lock.write_text('other owner')
    with pytest.raises(FileExistsError): tool.apply_plan(dataset, spec(dataset), '0' * 64, fake_audit)
    assert lock.read_text() == 'other owner'


def test_symlink_input_and_target_are_refused(dataset):
    value = spec(dataset); target = dataset / 'research/alias.json'; target.symlink_to(dataset / 'research/source.json')
    value['source']['path'] = 'research/alias.json'
    with pytest.raises(ValueError, match='SYMLINK'): tool.plan(dataset, value)
    target = dataset / 'context-sources/new-boundary.json'; target.symlink_to(dataset / 'research/source.json')
    with pytest.raises(ValueError, match='SYMLINK'): tool.plan(dataset, spec(dataset))


def test_rollback_does_not_overwrite_an_external_edit(dataset, monkeypatch):
    value = spec(dataset); plan = tool.plan(dataset, value)
    external = dataset / 'context-units/new-boundary.json'
    def fail(root): external.write_bytes(b'foreign editor'); raise RuntimeError('external mutation')
    monkeypatch.setattr(builder, 'publish', fail)
    with pytest.raises(ValueError, match='ROLLBACK_CONFLICT'): tool.apply_plan(dataset, value, plan['sha'], fake_audit)
    assert external.read_bytes() == b'foreign editor'


def test_duplicate_add_is_not_silently_overwritten(dataset):
    value = spec(dataset); plan = tool.plan(dataset, value)
    tool.apply_plan(dataset, value, plan['sha'], fake_audit)
    before = snapshot(dataset)
    with pytest.raises(ValueError): tool.plan(dataset, value)
    assert snapshot(dataset) == before


def test_review_requires_explicit_control_and_keeps_it_synthetic(dataset):
    value = spec(dataset); value['decision'] = 'REVIEW_REQUIRED'
    ref = save(dataset, 'research/review-positive.json', {'reviews': [{'eventId': 'r1', 'anchorId': 'stt-index-0',
        'decision': 'REVIEW_REQUIRED', 'scope': '합성 검토 제공 문맥', 'userReply': '검토 대상'}]})
    value['approval'] = {**ref, 'eventIds': ['r1']}
    before = snapshot(dataset)
    with pytest.raises(ValueError, match='CONTROL'): tool.plan(dataset, value)
    assert snapshot(dataset) == before
    value['normalContrasts'] = [{'kind': 'SYNTHETIC_DEVELOPMENT_CONTROL', 'text': '합성 정상 대조'}]
    preview = tool.plan(dataset, value)
    context = next(e['context'] for e in preview['payload']['candidateRuntime']['examples'] if e['id'] == value['id'])
    assert context['normalContrasts'] == ['합성 개발 대조(실제 관측 아님): 합성 정상 대조']
    assert context['criticismHypotheses']


def test_linked_control_provenance_is_copied_into_isolated_preview(dataset):
    value = spec(dataset)
    ref = save(dataset, 'research/control-approval.json', {'status': 'USER_APPROVED_DEVELOPMENT_BOUNDARIES',
        'trainingUseAuthorized': False, 'normalDrafts': [{'id': 'control1', 'humanApproved': True,
        'expectedDecision': 'PASS', 'sourceKind': 'SYNTHETIC_DEVELOPMENT_CONTROL', 'raw': [{'text': '승인된 합성 대조'}]}]})
    value['normalContrasts'] = [{'kind': 'SYNTHETIC_DEVELOPMENT_CONTROL', 'text': '승인된 합성 대조',
                                'approvalSource': {**ref, 'caseId': 'control1'}}]
    assert tool.plan(dataset, value)['summary']['examplesAfter'] == 3
    value['normalContrasts'][0]['approvalSource']['sha256'] = '0' * 64
    with pytest.raises(ValueError, match='HASH_MISMATCH'): tool.plan(dataset, value)


def test_no_change_update_is_previewable_but_not_published(dataset):
    value = spec(dataset, 'UPDATE'); value['interpretation'] = '합성 정상 해석'
    preview = tool.plan(dataset, value)
    assert preview['summary']['changedFiles'] == [] and not preview['summary']['runtimeChanged']
    before = snapshot(dataset)
    with pytest.raises(ValueError, match='NO_CHANGE'):
        tool.apply_plan(dataset, value, preview['sha'], lambda _: pytest.fail('no-change must not audit'))
    assert snapshot(dataset) == before


def test_invalid_audit_result_is_not_accepted(dataset):
    value = spec(dataset); preview = tool.plan(dataset, value); before = snapshot(dataset)
    with pytest.raises(ValueError, match='RETRIEVAL_REPORT_MISMATCH'):
        tool.apply_plan(dataset, value, preview['sha'], lambda _: {'retrieved': True})
    assert snapshot(dataset) == before


def test_target_created_during_audit_is_not_overwritten(dataset):
    value = spec(dataset); preview = tool.plan(dataset, value)
    target = dataset / 'context-sources/new-boundary.json'
    def concurrent_edit(p):
        target.write_bytes(b'external source')
        return fake_audit(p)
    with pytest.raises(ValueError, match='TARGET_CHANGED_BEFORE_WRITE'):
        tool.apply_plan(dataset, value, preview['sha'], concurrent_edit)
    assert target.read_bytes() == b'external source'
    assert not (dataset / 'context-units/new-boundary.json').exists()


def test_cli_default_is_private_summary_and_apply_needs_explicit_plan_hash(dataset):
    value = spec(dataset); path = dataset / 'research/intake.json'; path.write_bytes(tool.encoded(value))
    before = snapshot(dataset)
    cmd = [sys.executable, str(Path(tool.__file__)), str(path), '--dataset-root', str(dataset)]
    result = subprocess.run(cmd, capture_output=True, text=True, check=True)
    summary = json.loads(result.stdout)
    assert summary['applied'] is False and '합성 평가 표현' not in result.stdout
    assert snapshot(dataset) == before
    failed = subprocess.run(cmd + ['--apply'], capture_output=True, text=True)
    assert failed.returncode != 0 and snapshot(dataset) == before


def test_cli_preview_directory_never_overwrites_existing_or_canonical_paths(dataset):
    path = dataset / 'research/intake.json'; path.write_bytes(tool.encoded(spec(dataset)))
    output = dataset / 'experiments/preview'
    cmd = [sys.executable, str(Path(tool.__file__)), str(path), '--dataset-root', str(dataset), '--output', str(output)]
    subprocess.run(cmd, capture_output=True, check=True)
    assert (output / 'plan.json').exists() and (output / 'runtime.json').exists()
    before = snapshot(dataset)
    assert subprocess.run(cmd, capture_output=True).returncode != 0
    assert snapshot(dataset) == before
    forbidden = cmd[:-1] + [str(dataset / 'context-units/preview')]
    assert subprocess.run(forbidden, capture_output=True).returncode != 0
    assert snapshot(dataset) == before
