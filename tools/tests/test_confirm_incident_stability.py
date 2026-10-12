import copy
import json
from pathlib import Path
import sys
from unittest.mock import patch
import pytest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from confirm_incident_stability import freeze,gate
from stabilize_incident_reference import freeze as primary
from run_incident_reference_comparison import freeze as original,AUTHORIZATION,score
from test_incident_reference_runner import inputs
from fixed_review_suite import digest
from run_issue_scoped_comparison import execute
from test_scoped_input_study import mock_scoped
from test_run_target_focus_regression import audit


def fixture():
    source=primary(original(*inputs(),AUTHORIZATION),'.8','MECHANISM_FOCUS_1')
    texts=['이 동네에는 햄버거 가게가 없어서 제가 집에서 만들어 먹어요.',
        '이 메뉴는 제 취향에는 안 맞고 가격도 아까웠어요.',
        '할머니가 해주시던 팥죽 맛이 생각나요.',
        '선택 가능한 메뉴가 하나라서 주문 없이 주시는 걸 먹었어요.']
    units=[];entries=[];labels=[]
    for i in range(7):
        inp=dict(mode='SYNTHETIC_TEXT',segments=[dict(text=texts[max(0,i-3)],startMs=None,endMs=None)])
        uid=str(i);units.append(dict(unitId=uid,input=inp));entries.append(dict(unitId=uid,inputHash=digest(inp)))
        labels.append(dict(unitId=uid,reviewStatus='OWNER_DEVELOPMENT_ONLY',
            assessment=dict(inputHash=digest(inp),developmentExpectedDecision='PASS')))
    packet={'model-inputs.json':dict(units=units),'manifest.json':dict(cases=entries),
        'owner-labels.json':dict(labels=labels)}
    passed=dict(results=[dict(arm='CANDIDATE',accepted=True,matchedDevelopmentDecision=True)]*15)
    return source,passed,packet


def test_confirmation_requires_prior_gate_and_unchanged_control_labels():
    s,g,p=fixture()
    with pytest.raises(ValueError):freeze(s,dict(results=[]),p,'.4')
    p['owner-labels.json']['labels'][3]['assessment']['developmentExpectedDecision']='REVIEW_REQUIRED'
    with pytest.raises(ValueError):freeze(s,g,p,'.4')


def test_27_mock_units_preserve_control_text_and_cannot_award_success(tmp_path):
    s,g,p=fixture();before=copy.deepcopy(p);f=freeze(s,g,p,'.4')
    assert p==before and len(f['jobs'])==27 and len(f['source']['cases'])==9
    for i,c in enumerate(f['source']['cases'][5:],3):
        assert c['payload']['raw'][0]['text']==p['model-inputs.json']['units'][i]['input']['segments'][0]['text']
        assert c['timestampsAreSyntheticAuditCoordinates']
    with patch('confirm_incident_stability.prepare',return_value=f):
        r=execute(f,tmp_path/'datasets/run',None,mock_scoped,simulate=True)
        scored=score(f,r,audit(r));assert scored['mechanicalContractGateMet'] and not gate(scored)


def test_paired_anchor_baseline_is_not_a_tenth_confirmation_case():
    s,g,p=fixture();baseline=copy.deepcopy(s['source']['cases'][0])
    baseline['name']='F2-original';s['source']['cases'].insert(0,baseline)
    f=freeze(s,g,p,'.4')
    assert len(f['source']['cases'])==9 and len(f['jobs'])==27
    assert all(c['name']!='F2-original' for c in f['source']['cases'])
