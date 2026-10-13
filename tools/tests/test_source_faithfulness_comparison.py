import copy
from pathlib import Path
import sys
from unittest.mock import patch
import pytest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from test_confirm_incident_stability import fixture
from confirm_incident_stability import freeze as confirmed
from run_explicit_pass_stability import freeze as explicit
from run_source_faithfulness_comparison import freeze,AUTHORIZATION
from source_faithfulness_policy import DELTA
from run_issue_scoped_comparison import execute
from run_incident_reference_comparison import score
from test_scoped_input_study import mock_scoped
from test_run_target_focus_regression import audit


def test_only_explanation_block_changes_under_new_approval(tmp_path):
    s,g,p=fixture();source=explicit(confirmed(s,g,p,'.6'),'.3');before=copy.deepcopy(source)
    f=freeze(source,AUTHORIZATION);assert source==before and f['plannedUnits']==54
    for case in f['source']['cases']:
        pair=[j for j in f['jobs'] if j['case']==case['name'] and j['repeat']==1]
        base=next(j for j in pair if j['arm']=='BASELINE');new=next(j for j in pair if j['arm']=='CANDIDATE')
        body=copy.deepcopy(new['stages'][0]['body'])
        assert body['messages'][0]['content'].count(DELTA)==1
        body['messages'][0]['content']=body['messages'][0]['content'].replace(DELTA,'')
        assert body==base['stages'][0]['body'] and new['fixedSelectedScope']==base['fixedSelectedScope']
    with pytest.raises(ValueError):freeze(source,dict(AUTHORIZATION,additionalBudgetUsd='2.00'))
    with patch('run_source_faithfulness_comparison.prepare',return_value=f):
        r=execute(f,tmp_path/'datasets/run',None,mock_scoped,simulate=True)
        assert score(f,r,audit(r))['mechanicalContractGateMet']
