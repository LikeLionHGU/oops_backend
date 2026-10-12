import copy
import sys
from pathlib import Path
from unittest.mock import patch
import pytest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from stabilize_incident_reference import freeze,development_gate
from run_incident_reference_comparison import freeze as original,AUTHORIZATION,score
from test_incident_reference_runner import inputs
from run_issue_scoped_comparison import execute
from test_scoped_input_study import mock_scoped
from test_run_target_focus_regression import audit
from bound_review_contract import prepare_attempt,validate_attempt
from mechanism_focus_policy import DELTA
from fixed_review_suite import digest


def test_faithful_card_and_scope_are_identical_only_variant_changes():
    source=original(*inputs(),AUTHORIZATION);before=copy.deepcopy(source)
    f=freeze(source,'.8');assert source==before
    for case in f['source']['cases']:
        pair=[j for j in f['jobs'] if j['case']==case['name'] and j['repeat']==1]
        assert pair[0]['baseBody']==pair[1]['baseBody']
        assert pair[0]['fixedSelectedScope']==pair[1]['fixedSelectedScope']
        assert {j['judgmentVariant'] for j in pair}=={'CLOSED_SCOPE','TARGET_FOCUS_CLOSED_SCOPE'}


def test_insufficient_balance_or_changed_plan_refused(tmp_path):
    source=original(*inputs(),AUTHORIZATION)
    with pytest.raises(ValueError):freeze(source,'.001')
    with pytest.raises(ValueError):freeze(source,'1.00')
    f=freeze(source,'.8');f['jobs'][0]['judgmentVariant']='CLOSED_SCOPE_CHANGED'
    with patch('stabilize_incident_reference.source_inputs',return_value=source):
        with pytest.raises(ValueError):execute(f,tmp_path/'datasets/run',None,mock_scoped,simulate=True)


def test_mock_cannot_meet_development_gate(tmp_path):
    source=original(*inputs(),AUTHORIZATION);f=freeze(source,'.8')
    with patch('stabilize_incident_reference.source_inputs',return_value=source):
        r=execute(f,tmp_path/'datasets/run',None,mock_scoped,simulate=True)
        s=score(f,r,audit(r));assert s['mechanicalContractGateMet']
        assert not development_gate(s)


def test_mechanism_delta_preserves_all_input_and_contract_and_rejects_tampering():
    source=original(*inputs(),AUTHORIZATION)
    f=freeze(source,'.8','MECHANISM_FOCUS_1')
    for j in f['jobs']:
        a=prepare_attempt(j['baseBody'],j['fixedSelectedScope'],'test',
                          judgment_variant=j['judgmentVariant'])
        validate_attempt(a)
        if j['arm']=='CANDIDATE':
            before=prepare_attempt(j['baseBody'],j['fixedSelectedScope'],'test',
                                   judgment_variant='TARGET_FOCUS_CLOSED_SCOPE')
            body=copy.deepcopy(a['requestBody'])
            assert body['messages'][0]['content'].count(DELTA)==1
            body['messages'][0]['content']=body['messages'][0]['content'].replace(DELTA,'')
            assert body==before['requestBody']
            assert a['originalInput']==before['originalInput']
            assert a['selectedScope']==before['selectedScope']
            a['mechanismFocusPolicyHash']='changed'
            a['attemptHash']=digest({k:v for k,v in a.items() if k!='attemptHash'})
            with pytest.raises(ValueError):validate_attempt(a)


def test_mechanism_recipe_executes_all_mock_units(tmp_path):
    source=original(*inputs(),AUTHORIZATION);f=freeze(source,'.8','MECHANISM_FOCUS_1')
    with patch('stabilize_incident_reference.prepare',return_value=f):
        r=execute(f,tmp_path/'datasets/run',None,mock_scoped,simulate=True)
        s=score(f,r,audit(r))
        assert len(s['results'])==30 and s['mechanicalContractGateMet']
        assert not development_gate(s)


def test_compact_replaces_only_criteria_and_preserves_output_contract_and_input(tmp_path):
    from compact_review_policy import CRITERIA
    source=original(*inputs(),AUTHORIZATION);f=freeze(source,'.8','COMPACT_REVIEW_1')
    for j in f['jobs']:
        if j['arm']!='CANDIDATE':continue
        a=prepare_attempt(j['baseBody'],j['fixedSelectedScope'],'test',judgment_variant=j['judgmentVariant'])
        b=prepare_attempt(j['baseBody'],j['fixedSelectedScope'],'test',judgment_variant='CLOSED_SCOPE')
        marker='# 선택 근거를 별도 검증하고 판단'
        assert a['requestBody']['messages'][0]['content'].split(marker,1)[1]==b['requestBody']['messages'][0]['content'].split(marker,1)[1]
        assert a['requestBody']['messages'][1]==b['requestBody']['messages'][1]
        assert a['requestBody']['messages'][0]['content'].startswith(CRITERIA)
        validate_attempt(a)
    with patch('stabilize_incident_reference.prepare',return_value=f):
        r=execute(f,tmp_path/'datasets/run',None,mock_scoped,simulate=True)
        assert score(f,r,audit(r))['mechanicalContractGateMet']


def test_order_and_normal_relation_bundle_is_full_54_without_raw_changes(tmp_path):
    from test_confirm_incident_stability import fixture as controls_fixture
    from confirm_incident_stability import freeze as confirmation
    source,passed,packet=controls_fixture();extended=confirmation(source,passed,packet,'.6')
    f=freeze(extended,'.4','CHRONOLOGY_1')
    assert len(f['jobs'])==54 and f['plannedUnits']==54
    with patch('stabilize_incident_reference.prepare',return_value=f):
        r=execute(f,tmp_path/'datasets/run',None,mock_scoped,simulate=True)
        assert score(f,r,audit(r))['mechanicalContractGateMet']
