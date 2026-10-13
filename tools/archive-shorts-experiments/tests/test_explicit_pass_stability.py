import copy
import json
from pathlib import Path
import sys
from unittest.mock import patch
import pytest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from test_confirm_incident_stability import fixture
from confirm_incident_stability import freeze as confirmation
from run_explicit_pass_stability import freeze
from run_issue_scoped_comparison import execute
from run_incident_reference_comparison import score
from test_scoped_input_study import mock_scoped
from test_run_target_focus_regression import audit


def test_only_cap_and_existing_pass_template_change_all_conditions_preserved(tmp_path):
    source,passed,packet=fixture();source=confirmation(source,passed,packet,'.6');before=copy.deepcopy(source)
    f=freeze(source,'.3');assert source==before
    assert f['config']['maxCompletionTokens']==3000 and len(f['jobs'])==27
    for old,new in zip(source['jobs'],f['jobs'],strict=True):
        b=copy.deepcopy(new['baseBody']);b['max_completion_tokens']=old['baseBody']['max_completion_tokens']
        assert b==old['baseBody'] and new['fixedSelectedScope']==old['fixedSelectedScope']
        user=json.loads(new['stages'][0]['body']['messages'][1]['content'])
        if user['candidate']['axis']=='TARGET_TREATMENT' and user['grounding']['passContextSelectionIndexes']:
            assert '"alternativeInterpretation":null' not in new['stages'][0]['body']['messages'][0]['content']
    with pytest.raises(ValueError):freeze(source,'.001')
    with patch('run_explicit_pass_stability.prepare',return_value=f):
        r=execute(f,tmp_path/'datasets/run',None,mock_scoped,simulate=True)
        assert score(f,r,audit(r))['mechanicalContractGateMet']
