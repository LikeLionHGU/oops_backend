import hashlib
import json
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import add_incident
import build_incident_dataset as builder
import incident_intake as intake
from dataset_write_lock import dataset_write_lock
from test_incident_publish import setup as setup_dataset


def write_json(root, relative, value):
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    raw = (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode()
    path.write_bytes(raw)
    return raw


def offline_test_audit(root, baseline_raw, candidate_runtime, binding_sha, before_ids, after_ids):
    candidate_raw = intake.encoded(candidate_runtime)
    added = [row for row in after_ids if row not in set(before_ids)]
    removed = [row for row in before_ids if row not in set(after_ids)]
    return {"schemaVersion": "incident-selector-impact-report-1", "auditBindingSha256": binding_sha,
        "evaluationFingerprint": "e" * 64,
        "baselineRuntimeSha256": hashlib.sha256(baseline_raw).hexdigest(),
        "candidateRuntimeSha256": hashlib.sha256(candidate_raw).hexdigest(),
        "sourceCaseIdsAdded": added, "sourceCaseIdsRemoved": removed,
        "sharedPatternFingerprintBefore": "p" * 64, "sharedPatternFingerprintAfter": "p" * 64,
        "sharedPatternUnchanged": True, "dynamicRouterHintsApplied": False,
        "relationHintsApplied": False, "selectorReplayMode": "TEST_DOUBLE",
        "providerCalls": 0, "modelJudgmentExecuted": False, "accuracyMeasured": False,
        "retrievalImpactOnly": True, "evaluationCaseCount": 1, "changedQueryCount": 0,
        "queriesSelectingNewIncidentReference": 0, "newIncidentReferenceSelectionCount": 0,
        "removedReferenceCountAcrossQueries": 0, "mechanismImpact": {},
        "cases": [{"caseId": "test-query", "changed": False}], "semanticQualityConclusion": "NOT_MEASURED"}


@pytest.fixture
def prepared(tmp_path):
    setup_dataset(tmp_path)
    builder.publish(tmp_path)
    snapshot = "research/source-snapshots/intake-article.txt"
    snapshot_raw = "기자가 보도한 발언과 이에 대한 비판 맥락의 합성 테스트 자료입니다.".encode()
    (tmp_path / snapshot).parent.mkdir(parents=True, exist_ok=True)
    (tmp_path / snapshot).write_bytes(snapshot_raw)
    source_hash = hashlib.sha256(snapshot_raw).hexdigest()
    build = json.loads((tmp_path / "definitions/build.json").read_bytes())
    mechanism = build["guidelines"]["mechanisms"][0]["id"]
    incident_id = "intake-review-test"
    statement_id = incident_id + "#s1"
    source_spec = {"incidentId": incident_id, "statements": [{
        "caseId": statement_id, "knownPoint": "보도된 표현과 비판 이유의 연결을 개발 참고로 보관한다.",
        "mechanismIds": [mechanism], "topicTags": ["합성"],
        "article": {"publisher": "Synthetic News", "url": "https://example.test/story",
                    "publishedDate": "2026-10-27", "snapshot": snapshot, "sha256": source_hash}
    }]}
    incident = add_incident.assemble(source_spec, tmp_path)
    candidate_rel = f"research/pending-incidents/{incident_id}.json"
    candidate_raw = write_json(tmp_path, candidate_rel, incident)
    doc_id = incident["statements"][0]["legacyDictionaryCard"]["documents"][0]["id"]
    approval = {"schemaVersion": "incident-review-approval-1", "incidentId": incident_id,
        "incidentSha256": hashlib.sha256(candidate_raw).hexdigest(), "decision": "APPROVE_REFERENCE",
        "scope": "합성 테스트 문서를 제한된 내부 검색 참고로 검수한다. 사실이나 원영상 검증은 아니다.",
        "reviewedBy": "synthetic test reviewer", "reviewedOn": "2026-10-27",
        "statementReviews": [{"statementId": statement_id, "decision": "REVIEW_REQUIRED",
            "rationale": "보도 해석을 내부 참고 경계로만 저장한다.", "sourceDocumentIds": [doc_id]}],
        "sourceUseReview": {"decision": "ALLOW_DECLARED_INTERNAL_USE", "basis": "OTHER_REVIEWED",
            "authorizedUses": ["MODEL_INPUT", "RUNTIME_RETRIEVAL"],
            "rationale": "합성 테스트 자료에 대한 테스트용 사용 승인."},
        "sourceArtifacts": [{"path": snapshot, "sha256": source_hash}]}
    approval_rel = f"research/incident-approvals/{incident_id}.json"
    write_json(tmp_path, approval_rel, approval)
    return tmp_path, candidate_rel, approval_rel, incident_id


def test_reviewed_incident_preview_is_read_only_and_hash_bound(prepared):
    root, candidate, approval, incident_id = prepared
    runtime_before = (root / "guidelines/runtime.json").read_bytes()
    lock_before = intake.incident_lock_path(root).read_bytes()
    planned = intake.plan(root, candidate, approval, audit_runner=offline_test_audit)
    assert planned["summary"]["action"] == "ADD"
    assert planned["summary"]["trainingUseAuthorized"] is False
    assert planned["summary"]["independentTruthClaim"] is False
    assert planned["summary"]["incidentsAfter"] == planned["summary"]["incidentsBefore"] + 1
    assert planned["summary"]["sourceCaseIdsAdded"] == [incident_id + "#s1"]
    assert planned["summary"]["sharedPolicyInputsUnchanged"] is True
    assert planned["summary"]["selectorImpactAudit"] == "COMPLETE_OFFLINE_PRODUCTION_SELECTOR"
    assert planned["summary"]["selectorImpactAuditSha256"] == planned["payload"]["selectorImpactAuditSha256"]
    assert not (root / "incidents" / f"{incident_id}.json").exists()
    assert (root / "guidelines/runtime.json").read_bytes() == runtime_before
    assert intake.incident_lock_path(root).read_bytes() == lock_before


def test_reviewed_incident_apply_updates_lock_and_runtime_transactionally(prepared):
    root, candidate, approval, incident_id = prepared
    planned = intake.plan(root, candidate, approval, audit_runner=offline_test_audit)
    result = intake.apply(root, candidate, approval, planned["sha"], audit_runner=offline_test_audit)
    assert result["applied"] is True
    assert (root / "incidents" / f"{incident_id}.json").is_file()
    assert (root / "research/incident-reviews" / f"{incident_id}.json").is_file()
    lock = json.loads(intake.incident_lock_path(root).read_bytes())
    assert lock["schemaVersion"] == "incident-source-lock-2"
    assert incident_id in lock["reviewedEntries"]
    assert len(lock["frozenLegacyEntries"]) == 1
    verified = builder.verify(root)
    assert verified["incidents"] == 2
    receipt = json.loads((root / result["receiptPath"]).read_bytes())
    assert receipt["trainingUseAuthorized"] is False
    assert receipt["independentTruthClaim"] is False
    assert receipt["selectorImpactAuditSha256"] == planned["summary"]["selectorImpactAuditSha256"]
    assert receipt["selectorImpactAudit"]["providerCalls"] == 0
    assert receipt["previousFiles"]["incidents/" + incident_id + ".json"] is None


def test_apply_is_refused_while_another_dataset_writer_holds_the_lock(prepared):
    root, candidate, approval, incident_id = prepared
    planned = intake.plan(root, candidate, approval, audit_runner=offline_test_audit)
    with dataset_write_lock(root):
        with pytest.raises(ValueError, match="DATASET_WRITE_IN_PROGRESS"):
            intake.apply(root, candidate, approval, planned["sha"], audit_runner=offline_test_audit)
    assert not (root / "incidents" / f"{incident_id}.json").exists()
    assert intake.apply(root, candidate, approval, planned["sha"], audit_runner=offline_test_audit)["applied"] is True


def test_clean_preview_has_no_impact_signals(prepared):
    root, candidate, approval, _ = prepared
    assert intake.plan(root, candidate, approval, audit_runner=offline_test_audit)["summary"]["impactSignals"] == []


def test_selection_change_and_displaced_references_require_exact_acknowledgement(prepared):
    root, candidate, approval, incident_id = prepared

    def shifted_audit(*args):
        report = offline_test_audit(*args)
        report["changedQueryCount"] = 1
        report["removedReferenceCountAcrossQueries"] = 2
        report["cases"][0]["changed"] = True
        return report

    planned = intake.plan(root, candidate, approval, audit_runner=shifted_audit)
    signals = ["EVALUATION_SELECTION_CHANGED", "EXISTING_REFERENCES_DISPLACED"]
    assert planned["summary"]["impactSignals"] == signals
    for acknowledged in ((), ("EVALUATION_SELECTION_CHANGED",), (*signals, "SHARED_PATTERN_CHANGED")):
        with pytest.raises(ValueError, match="IMPACT_ACKNOWLEDGEMENT_MISMATCH"):
            intake.apply(root, candidate, approval, planned["sha"], audit_runner=shifted_audit,
                         acknowledged_impact=acknowledged)
        assert not (root / "incidents" / f"{incident_id}.json").exists()
    result = intake.apply(root, candidate, approval, planned["sha"], audit_runner=shifted_audit,
                          acknowledged_impact=tuple(reversed(signals)))
    receipt = json.loads((root / result["receiptPath"]).read_bytes())
    assert result["applied"] is True and receipt["acknowledgedImpactSignals"] == signals


def test_report_without_new_reference_count_is_rejected(prepared):
    root, candidate, approval, _ = prepared

    def incomplete_audit(*args):
        report = offline_test_audit(*args)
        del report["queriesSelectingNewIncidentReference"]
        return report

    with pytest.raises(ValueError, match="INCIDENT_SELECTOR_REPORT_MISMATCH"):
        intake.plan(root, candidate, approval, audit_runner=incomplete_audit)


def test_changed_selector_audit_after_preview_requires_new_plan(prepared):
    root, candidate, approval, incident_id = prepared
    planned = intake.plan(root, candidate, approval, audit_runner=offline_test_audit)

    def changed_audit(*args):
        report = offline_test_audit(*args)
        report["changedQueryCount"] = 1
        report["cases"][0]["changed"] = True
        return report

    with pytest.raises(Exception, match="PLAN_HASH_MISMATCH_REVIEW_NEW_PREVIEW"):
        intake.apply(root, candidate, approval, planned["sha"], audit_runner=changed_audit)
    assert not (root / "incidents" / f"{incident_id}.json").exists()


def test_candidate_change_after_preview_requires_new_plan(prepared):
    root, candidate, approval, incident_id = prepared
    planned = intake.plan(root, candidate, approval, audit_runner=offline_test_audit)
    candidate_path = root / candidate
    value = json.loads(candidate_path.read_bytes())
    value["statements"][0]["legacyCard"]["knownPoint"]["text"] += " 수정"
    candidate_path.write_bytes(intake.encoded(value))
    with pytest.raises(Exception, match="INCIDENT_REVIEW_DRAFT_HASH_MISMATCH"):
        intake.apply(root, candidate, approval, planned["sha"], audit_runner=offline_test_audit)
    assert not (root / "incidents" / f"{incident_id}.json").exists()


def test_missing_source_review_or_incomplete_statement_coverage_blocks_preview(prepared):
    root, candidate, approval, _ = prepared
    approval_path = root / approval
    value = json.loads(approval_path.read_bytes())
    value["statementReviews"] = []
    write_json(root, approval, value)
    with pytest.raises(Exception, match="INCIDENT_REVIEW_STATEMENT_COVERAGE_REQUIRED"):
        intake.plan(root, candidate, approval, audit_runner=offline_test_audit)


def test_frozen_legacy_incident_cannot_be_updated_through_new_intake(prepared):
    root, candidate, approval, _ = prepared
    value = json.loads((root / candidate).read_bytes())
    frozen_id = next(iter(json.loads(intake.incident_lock_path(root).read_bytes())["entries"]))
    value["incidentId"] = frozen_id
    value["catalogMeta"]["familyId"] = frozen_id
    for statement in value["statements"]:
        statement["legacyCard"]["familyId"] = frozen_id
    candidate = f"research/pending-incidents/{frozen_id}.json"
    candidate_raw = write_json(root, candidate, value)
    approval_value = json.loads((root / approval).read_bytes())
    approval_value["incidentId"] = frozen_id
    approval_value["incidentSha256"] = hashlib.sha256(candidate_raw).hexdigest()
    approval_value["statementReviews"][0]["statementId"] = value["statements"][0]["id"]
    approval = f"research/incident-approvals/{frozen_id}.json"
    write_json(root, approval, approval_value)
    with pytest.raises(Exception, match="FROZEN_LEGACY_INCIDENT_UPDATE_REQUIRES_SEPARATE_REVIEW"):
        intake.plan(root, candidate, approval, audit_runner=offline_test_audit)


def test_reviewed_incident_can_be_amended_only_with_new_bound_review(prepared):
    root, candidate, approval, incident_id = prepared
    first = intake.plan(root, candidate, approval, audit_runner=offline_test_audit)
    intake.apply(root, candidate, approval, first["sha"], audit_runner=offline_test_audit)
    candidate_path = root / candidate
    incident = json.loads(candidate_path.read_bytes())
    incident["statements"][0]["legacyCard"]["knownPoint"]["text"] += " 보강"
    candidate_raw = intake.encoded(incident)
    candidate_path.write_bytes(candidate_raw)
    approval_path = root / approval
    review = json.loads(approval_path.read_bytes())
    review["incidentSha256"] = hashlib.sha256(candidate_raw).hexdigest()
    approval_path.write_bytes(intake.encoded(review))
    second = intake.plan(root, candidate, approval, audit_runner=offline_test_audit)
    assert second["summary"]["action"] == "UPDATE_REVIEWED"
    result = intake.apply(root, candidate, approval, second["sha"], audit_runner=offline_test_audit)
    assert result["applied"]
    assert builder.verify(root)["incidents"] == 2
    saved = json.loads((root / "incidents" / f"{incident_id}.json").read_bytes())
    assert saved["statements"][0]["legacyCard"]["knownPoint"]["text"].endswith("보강")
    receipt = json.loads((root / result["receiptPath"]).read_bytes())
    assert receipt["previousFiles"]["incidents/" + incident_id + ".json"] is not None
    assert receipt["previousFileSha256"]["incidents/" + incident_id + ".json"]


def test_failed_publish_restores_incident_review_lock_runtime_and_manifest(prepared, monkeypatch):
    root, candidate, approval, incident_id = prepared
    planned = intake.plan(root, candidate, approval, audit_runner=offline_test_audit)
    lock_path = intake.incident_lock_path(root)
    previous = {"runtime": (root / "guidelines/runtime.json").read_bytes(),
                "manifest": (root / "guidelines/build-manifest.json").read_bytes(),
                "lock": lock_path.read_bytes()}

    def fail_publish(_root):
        raise RuntimeError("injected publish failure")

    monkeypatch.setattr(intake.builder, "publish", fail_publish)
    with pytest.raises(RuntimeError, match="injected publish failure"):
        intake.apply(root, candidate, approval, planned["sha"], audit_runner=offline_test_audit)
    assert not (root / "incidents" / f"{incident_id}.json").exists()
    assert not (root / "research/incident-reviews" / f"{incident_id}.json").exists()
    assert (root / "guidelines/runtime.json").read_bytes() == previous["runtime"]
    assert (root / "guidelines/build-manifest.json").read_bytes() == previous["manifest"]
    assert lock_path.read_bytes() == previous["lock"]
    assert not (root / planned["receipt"]).exists()


def test_review_record_tampering_is_detected_after_apply(prepared):
    root, candidate, approval, incident_id = prepared
    planned = intake.plan(root, candidate, approval, audit_runner=offline_test_audit)
    intake.apply(root, candidate, approval, planned["sha"], audit_runner=offline_test_audit)
    review_path = root / "research/incident-reviews" / f"{incident_id}.json"
    review = json.loads(review_path.read_bytes())
    review["scope"] += " changed"
    review_path.write_bytes(intake.encoded(review))
    with pytest.raises(Exception, match="INCIDENT_REVIEW_HASH_MISMATCH"):
        builder.verify(root)


def test_add_incident_only_writes_quarantined_candidate(prepared, monkeypatch, capsys):
    root, _, _, _ = prepared
    snapshot = root / "research/source-snapshots/quarantined.txt"
    snapshot.write_text("합성 기사 발췌.", encoding="utf-8")
    source_hash = hashlib.sha256(snapshot.read_bytes()).hexdigest()
    mechanism = json.loads((root / "definitions/build.json").read_bytes())["guidelines"]["mechanisms"][0]["id"]
    spec = {"incidentId": "quarantined-candidate", "statements": [{
        "caseId": "quarantined-candidate#s1", "knownPoint": "합성 후보",
        "mechanismIds": [mechanism], "topicTags": ["합성"],
        "article": {"publisher": "Synthetic", "url": "https://example.test/candidate",
                    "publishedDate": "2026-10-27", "snapshot": "research/source-snapshots/quarantined.txt",
                    "sha256": source_hash}
    }]}
    spec_path = root / "research/candidate-spec.json"
    spec_path.write_bytes(intake.encoded(spec))
    output = "research/pending-incidents/quarantined-candidate.json"
    monkeypatch.setattr(sys, "argv", ["add_incident.py", str(spec_path), "--dataset-root", str(root),
                                       "--candidate-output", output])
    add_incident.main()
    response = json.loads(capsys.readouterr().out)
    assert response["candidate"]["path"] == output
    assert (root / output).is_file()
    assert not (root / "incidents/quarantined-candidate.json").exists()
    assert builder.verify(root)["incidents"] == 1


def test_legacy_add_incident_apply_switch_is_blocked(monkeypatch):
    monkeypatch.setattr(sys, "argv", ["add_incident.py", "does-not-need-to-exist.json", "--apply"])
    with pytest.raises(SystemExit, match="2"):
        add_incident.main()


def test_add_incident_rejects_snapshot_path_traversal(prepared):
    root, _, _, _ = prepared
    mechanism = json.loads((root / "definitions/build.json").read_bytes())["guidelines"]["mechanisms"][0]["id"]
    spec = {"incidentId": "unsafe-snapshot", "statements": [{
        "caseId": "unsafe-snapshot#s1", "knownPoint": "합성 후보", "mechanismIds": [mechanism],
        "topicTags": ["합성"], "article": {"publisher": "Synthetic", "url": "https://example.test/",
            "publishedDate": "2026-10-27", "snapshot": "research/source-snapshots/../../.env",
            "sha256": "0" * 64}
    }]}
    with pytest.raises(Exception, match="UNSAFE_SOURCE_SNAPSHOT_PATH"):
        add_incident.assemble(spec, root)


def test_second_reviewed_incident_keeps_earlier_review_in_preview_stage(prepared):
    root, candidate, approval, first_id = prepared
    planned = intake.plan(root, candidate, approval, audit_runner=offline_test_audit)
    intake.apply(root, candidate, approval, planned["sha"], audit_runner=offline_test_audit)
    first_review = f"research/incident-reviews/{first_id}.json"
    assert first_review in builder.input_hashes(root)

    second_id = "intake-review-two"
    second_raw = (root / candidate).read_text(encoding="utf-8").replace(first_id, second_id).encode()
    second_candidate = f"research/pending-incidents/{second_id}.json"
    (root / second_candidate).write_bytes(second_raw)
    second_approval = json.loads((root / approval).read_text(encoding="utf-8").replace(first_id, second_id))
    second_approval["incidentSha256"] = hashlib.sha256(second_raw).hexdigest()
    second_approval_path = f"research/incident-approvals/{second_id}.json"
    write_json(root, second_approval_path, second_approval)

    second = intake.plan(root, second_candidate, second_approval_path, audit_runner=offline_test_audit)
    intake.apply(root, second_candidate, second_approval_path, second["sha"], audit_runner=offline_test_audit)
    reviewed = json.loads(intake.incident_lock_path(root).read_bytes())["reviewedEntries"]
    assert reviewed.keys() == {first_id, second_id}
    builder.verify(root)


def test_production_incident_lock_stays_in_the_ignored_dataset():
    production = builder.DEFAULT_ROOT
    assert intake.incident_lock_path(production) == production.resolve() / "definitions/incident-source-lock.json"
    assert not (ROOT / "incident_source_lock.json").exists()
