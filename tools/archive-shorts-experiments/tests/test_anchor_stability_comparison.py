import copy
import json
from pathlib import Path
import sys
from unittest.mock import patch
import pytest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from run_anchor_stability_comparison import freeze,AUTHORIZATION
from run_incident_reference_comparison import freeze as original,AUTHORIZATION as old_auth,score
from stabilize_incident_reference import freeze as primary,development_gate
from test_incident_reference_runner import inputs
from run_issue_scoped_comparison import execute
from test_scoped_input_study import mock_scoped
from test_run_target_focus_regression import audit


def fixture():
    source=primary(original(*inputs(),old_auth),'.8','COMPACT_REVIEW_1')
    j=next(j for j in source['jobs'] if j['case']=='F2' and j['arm']=='CANDIDATE')
    anchor=next(e['segmentId'] for e in j['fixedSelectedScope']['evidence'] if e['role']=='CONTEXT')
    return source,anchor


def test_fixed_input_reference_model_and_18_jobs_with_separate_approval():
    source,anchor=fixture();before=copy.deepcopy(source);f=freeze(source,AUTHORIZATION,anchor)
    assert source==before and f['plannedUnits']==18
    assert f['authorization']!=source['authorization'] and f['availableBudgetUsd']=='1.00'
    base=next(j for j in f['jobs'] if j['case']=='F2-original')
    new=next(j for j in f['jobs'] if j['case']=='F2')
    assert base['baseBody']['messages'][0]==new['baseBody']['messages'][0]
    assert json.loads(base['baseBody']['messages'][1]['content'])['raw']==json.loads(new['baseBody']['messages'][1]['content'])['raw']
    assert [(e['segmentId'],e['quote']) for e in base['fixedSelectedScope']['evidence']]==[(e['segmentId'],e['quote']) for e in new['fixedSelectedScope']['evidence']]
    with pytest.raises(ValueError):freeze(source,dict(AUTHORIZATION,additionalBudgetUsd='2.00'),anchor)


def test_all_mock_units_auditable_but_no_semantic_success(tmp_path):
    source,anchor=fixture();f=freeze(source,AUTHORIZATION,anchor)
    with patch('run_anchor_stability_comparison.prepare',return_value=f):
        r=execute(f,tmp_path/'datasets/run',None,mock_scoped,simulate=True)
        s=score(f,r,audit(r));assert len(s['results'])==18 and s['mechanicalContractGateMet']
        assert not development_gate(s)
