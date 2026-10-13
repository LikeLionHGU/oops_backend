import copy
import json
import sys
from pathlib import Path
from unittest.mock import patch
import pytest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from fixed_review_suite import digest
from test_confirm_incident_stability import fixture
from confirm_incident_stability import freeze as confirmed
from run_explicit_pass_stability import freeze as explicit
from run_source_faithfulness_comparison import freeze as faithful,AUTHORIZATION
from run_automatic_anchor_coupling import freeze,score
from reanchor_incident_scope import reanchor
from run_issue_scoped_comparison import execute
from test_scoped_input_study import mock_scoped
from compare_recorded_candidates import audit_export
from compare_review_prompts import digest as audit_digest


def frozen():
    s,g,p=fixture();parent=faithful(explicit(confirmed(s,g,p,'.6'),'.3'),AUTHORIZATION)
    passed=dict(results=[dict(arm='CANDIDATE',accepted=True,matchedDevelopmentDecision=True)]*27)
    source=confirmed(parent,passed,p,'.6');before=copy.deepcopy(source)
    # Synthetic fixture IDs replace only the recorded starting anchor, not live prepare().
    with patch('run_automatic_anchor_coupling.reanchor',side_effect=lambda b,s,i:reanchor(b,s,'b' if i=='stt-2240' else i)):
        f=freeze(source,'.4')
    assert source==before
    return f


def callback(key,body,timeout=90):
    p=json.loads(body['messages'][1]['content'])
    if 'quotes' not in p:return mock_scoped(key,body,timeout)
    return dict(model='gpt-6-luna',usage=dict(prompt_tokens=100,completion_tokens=100),
        choices=[dict(finish_reason='stop',message=dict(content=json.dumps(
            dict(selectedSegmentId=p['quotes'][0]['segmentId'],reason='fixture selection'))))])


def audit(report):
    e=audit_export(report);e.update(trialSchemaVersion='recorded-flow-regression-1',repetitionsPerCondition=3)
    return dict(sourceReportSha256=audit_digest(e),results=[dict(case=u['case'],arm=u['arm'],repeat=u['repeat'],
        accepted='output' in u,decision=u['stages'][-1].get('boundReceipt',{}).get('modelOutput',{}).get('assessment',{}).get('decision'))
        for u in report['calls']])


def test_dynamic_anchor_request_and_audit_proposal_share_same_source(tmp_path):
    f=frozen();assert f['maxApiCalls']==54 and f['automaticDiscoveryEvaluated'] is False
    with patch('run_automatic_anchor_coupling.prepare',return_value=f):
        r=execute(f,tmp_path/'datasets/run',None,callback,simulate=True)
        s=score(f,r,audit(r));assert s['mechanicalContractGateMet'] and s['semanticApprovalPending']
        assert all(x['matchedDevelopmentDecision'] is None for x in s['results'])
        assert all(u['anchorAction']=='REANCHORED' for u in r['calls'] if u['case']=='F2')
        original=copy.deepcopy(r);normalized=audit_export(r);assert original==r
        legacy=copy.deepcopy(r);legacy.pop('anchorCouplingStudy')
        with pytest.raises(ValueError):audit_export(legacy)
        for call in normalized['calls']:
            assert call['output']['verifications'][0]['assessment']['segmentId']==call['actualCandidateProposal']['anchorId']
        for change in ('proposal','body','duplicates','usage','authorization','audit-hash'):
            changed=copy.deepcopy(r);a=audit(changed)
            if change=='proposal':changed['calls'][0]['actualCandidateProposal']['reason']='not original'
            if change=='body':changed['calls'][0]['stages'][1]['body']['model']='other'
            if change=='duplicates':a['results'].append(copy.deepcopy(a['results'][0]))
            if change=='usage':changed['calls'][0]['stages'][0]['usage']['prompt_tokens']=999999
            if change=='authorization':changed['authorizationHash']='other'
            if change=='audit-hash':a['sourceReportSha256']=digest(audit_export(changed))
            with pytest.raises(ValueError):score(f,changed,a)


def test_unknown_anchor_stops_before_judgment_without_repair(tmp_path):
    f=frozen()
    def bad(key,body,timeout=90):
        r=callback(key,body,timeout);r['choices'][0]['message']['content']=json.dumps(dict(selectedSegmentId='invented',reason='bad'))
        return r
    with patch('run_automatic_anchor_coupling.prepare',return_value=f):
        r=execute(f,tmp_path/'datasets/run',None,bad,simulate=True)
        assert r['simulatedCalls']==27 and all(u['status']=='ANCHOR_REJECTED' and 'output' not in u for u in r['calls'])
        with pytest.raises(ValueError):score(f,r,audit(r))
