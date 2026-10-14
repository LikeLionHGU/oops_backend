import copy
import json
import sys
from pathlib import Path
import pytest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from test_incident_publish import setup
from criticism_hypotheses import validate
from build_incident_dataset import generate, publish, verify
from incidents_migration import from_incident, to_incident, reassemble


def hypothesis():
    return dict(id='synthetic-menu', text='메뉴 선택을 무가치하게 취급한다는 개발 해석이다.',
                basis='USER_REVIEWED_DEVELOPMENT_EXPECTATION', status='DRAFT',
                evidenceRefs=[], sourceRefs=[], supportingReactionIds=[],
                requiredCurrentEvidence=['메뉴 지칭과 낮추는 평가의 실제 연결'],
                normalContrast=['단일 메뉴 안내나 단순 불만은 제외'], missingContext=['실제 원음 미확인'])


def test_incident_hypothesis_reaches_runtime_without_comments_or_approval(tmp_path):
    inc = setup(tmp_path); baseline, _ = generate(tmp_path)
    inc['statements'][0]['criticismHypotheses'] = [hypothesis()]
    p = tmp_path / 'incidents' / (inc['incidentId'] + '.json'); p.write_text(json.dumps(inc))
    runtime, dictionary = generate(tmp_path)
    h = dictionary['entries'][0]['interpretationMaterial']['criticismHypotheses'][0]
    assert h['status'] == 'DRAFT' and h['sourceRefs'] == []
    assert h['compactReferenceText'] in runtime['examples'][0]['context']['criticismHypotheses']
    assert '단일 메뉴 안내' in h['compactReferenceText'] and '실제 원음 미확인' in h['compactReferenceText']
    assert len(runtime['examples']) == len(baseline['examples'])
    assert runtime['sourceBundleSha256'] == baseline['sourceBundleSha256']
    assert runtime['contextDictionarySha256'] != baseline['contextDictionarySha256']
    assert runtime['humanValidated'] is False and dictionary['humanApproved'] is False
    assert publish(tmp_path) == verify(tmp_path)


def test_hypothesis_source_refs_and_scope_are_not_invented(tmp_path):
    inc = setup(tmp_path); card = inc['statements'][0]['legacyCard']
    for key in ['evidenceRefs', 'sourceRefs', 'supportingReactionIds']:
        h = hypothesis(); h[key] = ['missing']
        with pytest.raises(Exception, match='CRITICISM_REFERENCE_INVALID'):
            validate([h], card)
    h = hypothesis(); h['basis'] = 'SOURCE_INTERPRETATION'
    with pytest.raises(Exception, match='CRITICISM_SOURCE_REQUIRED'): validate([h], card)
    h['basis'] = 'AUDIENCE_REACTION_INTERPRETATION'
    with pytest.raises(Exception, match='CRITICISM_REACTION_REQUIRED'): validate([h], card)
    reaction = card['reactions'][0]
    reaction['stage'] = 'POST_CONTROVERSY_RESPONSE'
    h['supportingReactionIds'] = [reaction['id']]
    with pytest.raises(Exception, match='CRITICISM_REACTION_SCOPE'): validate([h], card)


def test_boundaries_are_mandatory_and_compact_text_is_not_truncated(tmp_path):
    card = setup(tmp_path)['statements'][0]['legacyCard']; h = hypothesis()
    h['normalContrast'] = []
    with pytest.raises(Exception, match='CRITICISM_BOUNDARY_REQUIRED'): validate([h], card)
    h = hypothesis(); h['text'] = 'x' * 350
    with pytest.raises(Exception, match='CRITICISM_REFERENCE_TOO_LONG'): validate([h], card)
    h = hypothesis()
    with pytest.raises(Exception, match='CRITICISM_HYPOTHESIS_IDENTITY'): validate([h, copy.deepcopy(h)], card)


def test_migration_keeps_hypotheses_and_rejects_two_different_copies(tmp_path):
    inc = setup(tmp_path); inc['statements'][0]['criticismHypotheses'] = [hypothesis()]
    cards, plan, dictionary, catalog = reassemble(tmp_path, [inc])
    converted = to_incident(inc['incidentId'], cards['incidents'][0], plan, dictionary, catalog)
    assert converted['statements'][0]['criticismHypotheses'] == [hypothesis()]
    assert from_incident(converted)[2] == dictionary['cards']
    converted['statements'][0]['criticismHypotheses'] = []
    with pytest.raises(Exception, match='CRITICISM_HYPOTHESES_CONFLICT'): from_incident(converted)
