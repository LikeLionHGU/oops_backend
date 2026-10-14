import copy
import json
import sys
from pathlib import Path
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from test_build_review_guidelines import GuidelineCompilerTests
from incident_build_definition import extract
from incidents_migration import to_incident
from build_incident_dataset import publish, verify
from archive_legacy_dictionary import archive, FILES


def setup(root):
    fixture = GuidelineCompilerTests(); fixture.setUp()
    cards, rules = fixture.bundle, fixture.plan
    family = cards['incidents'][0]['familyId']
    dictionary = dict(schemaVersion='context-dictionary-plan-1', status='ASSISTANT_DRAFT', humanApproved=False,
                      cards={'synthetic-card': dict(topicTags=['합성'], mechanismIds=['mechanism'], documents=[])})
    catalog = dict(incidents=[dict(familyId=family, caseIds=['synthetic-card'], collectionId='collection',
                                  evidenceLevel='REPORTED_CONTEXT_ONLY', selectedReactions=4)])
    definition = extract(cards, rules, dictionary, catalog)
    incident = to_incident(family, cards['incidents'][0], rules, dictionary, catalog)
    for name, value in [('definitions/build.json', definition), (f'incidents/{family}.json', incident)]:
        p = root / name; p.parent.mkdir(parents=True, exist_ok=True); p.write_text(json.dumps(value))
    return incident


def test_new_case_stale_input_detection_and_rebuild(tmp_path):
    incident = setup(tmp_path)
    first = publish(tmp_path)
    original = (tmp_path / 'guidelines/runtime.json').read_bytes()
    new = copy.deepcopy(incident)
    new.update(incidentId='second-incident')
    new['catalogMeta'].update(familyId='second-incident', caseIds=['second-card'], collectionId=None)
    s = new['statements'][0]; s['id'] = 'second-card'
    s['legacyCard'].update(id='second-card', familyId='second-incident', reactions=[])
    s['legacyCard']['controversy'].update(reasonHypotheses=[], reasonSupportReactionIds=[])
    (tmp_path / 'incidents/second-incident.json').write_text(json.dumps(new))
    with pytest.raises(Exception, match='RUNTIME_NOT_EQUIVALENT'):
        verify(tmp_path)
    second = publish(tmp_path)
    assert first['examples'] == 1 and second['examples'] == 2
    assert first['sourceBundleSha256'] != second['sourceBundleSha256']
    assert (tmp_path / 'legacy/runtime-history' / (first['runtimeSha256'] + '.json')).read_bytes() == original
    assert verify(tmp_path) == second


def test_archive_then_build_without_old_files(tmp_path):
    setup(tmp_path); before = publish(tmp_path)
    for name in FILES:
        p = tmp_path / name; p.parent.mkdir(parents=True, exist_ok=True); p.write_text('obsolete')
    assert set(archive(tmp_path)) == set(FILES)
    assert not any((tmp_path / name).exists() for name in FILES)
    assert verify(tmp_path) == before
    assert publish(tmp_path) == before
    assert archive(tmp_path) == []


def test_manifest_tamper_and_invalid_input_leave_runtime_intact(tmp_path):
    incident = setup(tmp_path); publish(tmp_path)
    output = tmp_path / 'guidelines/runtime.json'; previous = output.read_bytes()
    manifest = tmp_path / 'guidelines/build-manifest.json'
    value = json.loads(manifest.read_text()); value['examples'] = 100
    manifest.write_text(json.dumps(value))
    with pytest.raises(Exception, match='BUILD_MANIFEST_MISMATCH'):
        verify(tmp_path)
    with pytest.raises(Exception, match='BUILD_MANIFEST_MISMATCH'):
        archive(tmp_path)
    incident['statements'][0]['mechanismIds'] = ['unknown']
    (tmp_path / 'incidents' / (incident['incidentId'] + '.json')).write_text(json.dumps(incident))
    with pytest.raises(Exception, match='UNKNOWN_MECHANISM'):
        publish(tmp_path)
    assert output.read_bytes() == previous


def test_invalid_java_case_id_never_publishes(tmp_path):
    incident = setup(tmp_path); publish(tmp_path)
    previous = (tmp_path / 'guidelines/runtime.json').read_bytes()
    s = incident['statements'][0]
    s['id'] = s['legacyCard']['id'] = 'incident##s1'
    (tmp_path / 'incidents' / (incident['incidentId'] + '.json')).write_text(json.dumps(incident))
    with pytest.raises(Exception, match='JAVA_SOURCE_ID_CONTRACT'):
        publish(tmp_path)
    assert (tmp_path / 'guidelines/runtime.json').read_bytes() == previous
