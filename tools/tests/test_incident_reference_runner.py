import copy
import sys
from unittest.mock import patch
import pytest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from run_incident_reference_comparison import freeze,AUTHORIZATION,score
from build_incident_reference import build_card,project
from test_incident_reference import fixture
from test_scoped_input_study import mock_scoped
from test_run_target_focus_regression import audit
from run_issue_scoped_comparison import execute
from reservation_ledger import initialize_fresh,balance,claim


def inputs():
    f,b,s=fixture();card=build_card(f,s)
    return f,b,project(b,card),card


def test_full_30_fixed_requests_reservation_and_no_source_mutation():
    args=inputs();before=copy.deepcopy(args);f=freeze(*args,AUTHORIZATION)
    assert args==before
    assert f['plannedUnits']==30 and f['maxConcurrency']==1
    assert {(j['case'],j['arm'],j['repeat']) for j in f['jobs']}=={
        (n,a,r) for n in ('F2','C','D','F4','F5') for a in ('BASELINE','CANDIDATE') for r in (1,2,3)}
    for case in f['source']['cases']:
        for arm in ('BASELINE','CANDIDATE'):
            bodies=[j['stages'][0]['body'] for j in f['jobs'] if j['case']==case['name'] and j['arm']==arm]
            assert all(b==bodies[0] for b in bodies)


def test_changed_authorization_or_projection_refused():
    args=inputs()
    with pytest.raises(ValueError):freeze(*args,dict(AUTHORIZATION,additionalBudgetUsd='2.00'))
    candidate=copy.deepcopy(args[2]);candidate['guidelines'][0]['condition']='changed'
    with pytest.raises(ValueError):freeze(args[0],args[1],candidate,args[3],AUTHORIZATION)


def test_mock_trial_has_receipts_and_semantics_remain_pending(tmp_path):
    args=inputs();f=freeze(*args,AUTHORIZATION)
    with patch('run_incident_reference_comparison.source_inputs',return_value=args):
        r=execute(f,tmp_path/'datasets/run',None,mock_scoped,simulate=True)
        s=score(f,r,audit(r))
        assert r['apiCalls']==0 and r['simulatedCalls']==30
        assert s['mechanicalContractGateMet'] and s['semanticQualityGateMet'] is None
        assert len(s['summary'])==10 and all(x['observations']==3 for x in s['summary'])
        assert not s['runtimeUseAuthorized']


def test_fresh_budget_does_not_reset_or_accept_changed_same_approval(tmp_path):
    p=tmp_path/'datasets/ledger.sqlite3'
    initialize_fresh(p,AUTHORIZATION)
    f=freeze(*inputs(),AUTHORIZATION)
    claim(p,f,tmp_path/'datasets/run')
    remaining=balance(p,AUTHORIZATION)
    initialize_fresh(p,AUTHORIZATION)
    assert balance(p,AUTHORIZATION)==remaining
    with pytest.raises(ValueError):initialize_fresh(p,dict(AUTHORIZATION,additionalBudgetUsd='2.00'))
    with pytest.raises(ValueError):claim(p,f,tmp_path/'datasets/again')


def test_mutated_frozen_refused_before_key_or_callback(tmp_path):
    args=inputs();f=freeze(*args,AUTHORIZATION);f['jobs'][0]['judgmentVariant']='RAW_REMOVED'
    with patch('run_incident_reference_comparison.source_inputs',return_value=args),\
         patch('run_issue_scoped_comparison.read_key',side_effect=AssertionError('key loaded')):
        with pytest.raises(ValueError):execute(f,tmp_path/'datasets/run',None,mock_scoped,simulate=True)
    assert not (tmp_path/'datasets/run').exists()
