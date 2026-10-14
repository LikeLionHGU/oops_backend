import copy
import json
import sys
from pathlib import Path
from unittest.mock import patch
import pytest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from test_incidents_migration import _synthetic
from incident_build_definition import extract, load, emit
from incidents_migration import to_incident, reassemble


def setup_definition(root):
    ci,g,d,cat=_synthetic();cards=dict(incidents=[ci],schemaVersion='fixture')
    definition=extract(cards,g,d,cat)
    (root/'definitions').mkdir();(root/'definitions/build.json').write_text(json.dumps(definition))
    return to_incident(ci['familyId'],ci,g,d,cat),definition,(cards,g,d,cat)


def test_build_has_no_legacy_reads_and_preserves_source(tmp_path):
    incident,definition,old=setup_definition(tmp_path);before=copy.deepcopy((incident,definition))
    with patch('incidents_migration.load',side_effect=AssertionError('legacy read')):
        actual=reassemble(tmp_path,[incident])
    assert actual==old and before==(incident,definition)
    assert not list(tmp_path.glob('drafts/*'))


def test_missing_definition_never_falls_back(tmp_path):
    with pytest.raises(Exception,match='BUILD_DEFINITION_REQUIRED'):reassemble(tmp_path,[])


def test_removed_case_has_no_stale_source_ids(tmp_path):
    incident,_,_=setup_definition(tmp_path);incident['statements']=incident['statements'][:1]
    _,plan,_,_=reassemble(tmp_path,[incident])
    assert plan['mechanisms'][0]['sourceCaseIds']==['demo-A']
    assert plan['mechanisms'][1]['sourceCaseIds']==[]


def test_duplicate_incident_and_statement_block(tmp_path):
    incident,_,_=setup_definition(tmp_path)
    with pytest.raises(Exception,match='DUPLICATE_INCIDENT'):reassemble(tmp_path,[incident,copy.deepcopy(incident)])
    incident['statements'].append(copy.deepcopy(incident['statements'][0]))
    with pytest.raises(Exception,match='STATEMENT_IDENTITY_INVALID'):reassemble(tmp_path,[incident])


def test_definition_does_not_contain_case_payload(tmp_path):
    _,definition,_=setup_definition(tmp_path);definition['cards']['incidents']=[{}]
    (tmp_path/'definitions/build.json').write_text(json.dumps(definition))
    with pytest.raises(Exception,match='MUST_NOT_CONTAIN_CASE_DATA'):load(tmp_path)


def test_new_incident_can_be_added_without_catalog_template(tmp_path):
    incident,_,_=setup_definition(tmp_path);new=copy.deepcopy(incident);new['incidentId']='new'
    new['catalogMeta']['familyId']='new';new['catalogMeta']['caseIds']=['new-A'];new['catalogMeta']['collectionId']=None
    new['statements']=new['statements'][:1];s=new['statements'][0];s['id']='new-A';s['legacyCard']['id']='new-A';s['legacyCard']['familyId']='new'
    cards,plan,_,catalog=reassemble(tmp_path,[new,incident])
    assert [i['familyId'] for i in cards['incidents']]==['demo-topic','new']
    assert plan['mechanisms'][0]['sourceCaseIds']==['demo-A','demo-B','new-A']
    assert catalog['incidents'][-1]['collectionId'] is None


def test_actual_compilers_match_without_legacy_files(tmp_path):
    from test_build_review_guidelines import GuidelineCompilerTests
    from build_context_dictionary import compile_dictionary
    from build_review_guidelines import compile_guidelines
    from build_incident_dataset import generate, _strip_ts
    fixture=GuidelineCompilerTests();fixture.setUp();cards=fixture.bundle;g=fixture.plan
    d=dict(schemaVersion='context-dictionary-plan-1',status='ASSISTANT_DRAFT',humanApproved=False,
           cards={'synthetic-card':dict(topicTags=['합성 주제'],mechanismIds=['mechanism'],documents=[])})
    ci=cards['incidents'][0]
    cat=dict(incidents=[dict(familyId=ci['familyId'],caseIds=['synthetic-card'],collectionId='collection',
                            evidenceLevel='REPORTED_CONTEXT_ONLY',selectedReactions=4)])
    definition=extract(cards,g,d,cat);incident=to_incident(ci['familyId'],ci,g,d,cat)
    (tmp_path/'definitions').mkdir();(tmp_path/'definitions/build.json').write_text(json.dumps(definition))
    (tmp_path/'incidents').mkdir();(tmp_path/'incidents'/f'{incident["incidentId"]}.json').write_text(json.dumps(incident))
    source=(json.dumps(cards,ensure_ascii=False,indent=2)+'\n').encode()
    dictionary=compile_dictionary(cards,d,g,source)
    expected=compile_guidelines(cards,g,source,dictionary)
    with patch('incidents_migration.load',side_effect=AssertionError('legacy read')):
        actual,_=generate(tmp_path)
    assert _strip_ts(actual)==_strip_ts(expected)
    assert json.dumps(_strip_ts(actual),ensure_ascii=False,indent=2)==json.dumps(_strip_ts(expected),ensure_ascii=False,indent=2)
    assert set(p.name for p in tmp_path.iterdir())=={'definitions','incidents'}


def test_emit_requires_explicit_new_file_and_never_overwrites(tmp_path):
    incident,definition,old=setup_definition(tmp_path)
    for rel,value in zip(('drafts/cards.json','guidelines/plan.json','research/context-dictionary-plan.json','catalog.json'),old):
        p=tmp_path/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(value))
    with pytest.raises(FileExistsError):emit(tmp_path)
    assert load(tmp_path)==definition


def test_each_mechanism_retains_its_own_source_order(tmp_path):
    ci,g,d,cat=_synthetic();g['mechanisms'][0]['sourceCaseIds']=['demo-B','demo-A']
    cards=dict(incidents=[ci]);definition=extract(cards,g,d,cat)
    incident=to_incident(ci['familyId'],ci,g,d,cat)
    _,actual,_,_=reassemble(tmp_path,[incident],definition)
    assert actual==g
