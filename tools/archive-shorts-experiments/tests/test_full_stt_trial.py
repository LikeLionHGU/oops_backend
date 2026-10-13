import copy
import json
import sys
from pathlib import Path
from unittest.mock import patch
import pytest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from compare_review_prompts import digest as audit_digest
from test_confirm_incident_stability import fixture
from confirm_incident_stability import freeze as confirmed
from run_explicit_pass_stability import freeze as explicit
from run_full_stt_trial import freeze,freeze_follow,freeze_catalog,AUTHORIZATION,execute,score,audit_exports,load_report
from full_stt_contract import accept_discovery
from test_scoped_input_study import mock_scoped


def plan():
    s,g,p=fixture();parent=explicit(confirmed(s,g,p,'.6'),'.3')
    raw=json.loads(parent['jobs'][0]['baseBody']['messages'][1]['content'])['raw']
    transcript=[{k:r[k] for k in ('startMs','endMs','text')} for r in raw]
    before=copy.deepcopy((parent,transcript));f=freeze(parent,transcript,AUTHORIZATION)
    assert before==(parent,transcript);return f


def response(output):
    return dict(model='gpt-6-luna',usage=dict(prompt_tokens=100,completion_tokens=100),
        choices=[dict(finish_reason='stop',message=dict(content=json.dumps(output)))])


def mock(key,body,timeout=90):
    p=json.loads(body['messages'][1]['content'])
    if set(p)=={'raw'}:
        r=p['raw'][0]
        return response(dict(reviewedSegmentIds=[r['id'] for r in p['raw']],candidates=[dict(anchorId=r['id'],
            axis='EXPRESSION_CONTENT',reason='fixture only',evidence=[dict(segmentId=r['id'],quote=r['text'])])],uncertainSegments=[],truncated=False))
    if 'selectedScope' in p:return mock_scoped(key,body,timeout)
    if 'targetSpans' in p:
        c=p['original']['candidates'][0]
        return response(dict(candidateId=c['candidateId'],anchorId=c['anchorId'],issueSummary='fixture only',
            selections=[dict(segmentId=c['anchorId'],role='PRIMARY',spanId='FULL')]))
    c=p['candidates'][0];r=next(r for r in p['raw'] if r['id']==c['anchorId'])
    return response(dict(candidateId=c['candidateId'],anchorId=c['anchorId'],issueSummary='fixture only',
        selections=[dict(segmentId=r['id'],start=0,end=len(r['text']),role='PRIMARY')]))


def audits(r):
    return [dict(sourceReportSha256=audit_digest(e),results=[dict(case=c['case'],accepted='output' in c,
        decision=c.get('output',{}).get('verifications',[dict(assessment={})])[0]['assessment'].get('decision')) for c in e['calls']]) for e in audit_exports(r)]


def test_entire_raw_no_candidate_or_label_in_discovery_and_exact_new_budget():
    f=plan();assert f['maxApiCalls']==117 and f['reservedCostUsd']=='0.6669'
    for u in f['units']:
        p=json.loads(u['discoveryBody']['messages'][1]['content'])
        assert set(p)=={'raw'} and p['raw']==u['raw']
    assert len(f['units'])==9 and sum(u['name']=='V1' for u in f['units'])==3


def test_source_quote_and_time_boundary_rejected_without_repair():
    f=plan();raw=f['units'][0]['raw'];out=json.loads(mock(None,f['units'][0]['discoveryBody'])['choices'][0]['message']['content'])
    before=copy.deepcopy(out);assert accept_discovery(out,raw)['payloads'];assert out==before
    for mode in ('quote','id','duplicate','unread'):
        changed=copy.deepcopy(out)
        if mode=='quote':changed['candidates'][0]['evidence'][0]['quote']='invented'
        if mode=='id':changed['candidates'][0]['anchorId']='unknown'
        if mode=='duplicate':changed['candidates'].append(copy.deepcopy(changed['candidates'][0]))
        if mode=='unread':changed['reviewedSegmentIds']=[]
        with pytest.raises(ValueError):accept_discovery(changed,raw)


def test_mock_full_chain_roundtrip_java_association_and_tampering(tmp_path):
    f=plan()
    with patch('run_full_stt_trial.prepare',return_value=f),patch('run_full_stt_trial.read_key',side_effect=AssertionError('key read')):
        r=execute(f,tmp_path/'datasets/mock',simulate=True,call=mock);assert r==load_report(tmp_path/'datasets/mock/report-index.json')
        s=score(f,r,audits(r));assert s['javaAccepted']==s['javaTotal']==9 and s['semanticQualityGateMet'] is None
        for mode in ('scope','request','usage','audit','extra'):
            changed=copy.deepcopy(r);a=audits(changed)
            if mode=='scope':changed['units'][0]['candidates'][0]['scope']['evidence'][0]['quote']='invented'
            if mode=='request':changed['attempts'][0]['body']['model']='other'
            if mode=='usage':changed['attempts'][0]['estimatedCostUsd']='0'
            if mode=='audit':a[0]['sourceReportSha256']='other'
            if mode=='extra':changed['attempts'].append(copy.deepcopy(changed['attempts'][0]))
            with pytest.raises(ValueError):score(f,changed,a)


def test_invented_selection_preserved_as_failure_not_generated_scope(tmp_path):
    f=plan()
    def bad(key,body,timeout=90):
        r=mock(key,body,timeout);p=json.loads(body['messages'][1]['content'])
        if 'candidates' in p:
            o=json.loads(r['choices'][0]['message']['content']);o['selections'][0]['end']=99999;r=response(o)
        return r
    with patch('run_full_stt_trial.prepare',return_value=f):
        r=execute(f,tmp_path/'datasets/mock',simulate=True,call=bad)
        s=score(f,r,audits(r));assert s['javaAccepted']==0
        assert all(c['status']=='SELECTION_REJECTED' and 'scope' not in c and 'receipt' not in c for u in r['units'] for c in u['candidates'])


def test_no_candidate_and_coverage_gap_do_not_become_model_pass(tmp_path):
    f=plan()
    def empty(key,body,timeout=90):
        p=json.loads(body['messages'][1]['content'])
        assert set(p)=={'raw'}
        return response(dict(reviewedSegmentIds=[p['raw'][0]['id']],candidates=[],uncertainSegments=[],truncated=False))
    with patch('run_full_stt_trial.prepare',return_value=f):
        r=execute(f,tmp_path/'datasets/mock',simulate=True,call=empty);s=score(f,r,[])
        assert r['simulatedCalls']==9 and s['javaTotal']==0
        assert s['units'][0]['unreviewedIds'] and not s['units'][0]['candidates']
        assert s['semanticQualityGateMet'] is None and s['units'][0]['semanticIssueMatched'] is None


def test_changed_plan_blocks_before_key_callback_and_output(tmp_path):
    f=plan();changed=copy.deepcopy(f);changed['units'][0]['raw'][0]['text']='changed'
    with patch('run_full_stt_trial.prepare',return_value=f),patch('run_full_stt_trial.read_key',side_effect=AssertionError('key read')):
        with pytest.raises(ValueError):execute(changed,tmp_path/'datasets/run',simulate=True,call=mock)
        assert not (tmp_path/'datasets/run').exists()


def test_follow_only_adds_evidence_instruction_no_source_or_model_change(tmp_path):
    from full_stt_contract import EVIDENCE_BUDGET
    f=plan();before=copy.deepcopy(f);follow=freeze_follow(f,'.3331')
    assert f==before and follow['reservedCostUsd']=='0.2793' and follow['maxApiCalls']==57
    for old,new in zip(f['units'],follow['units'],strict=True):
        body=copy.deepcopy(new['discoveryBody']);body['messages'][0]['content']=body['messages'][0]['content'].replace(EVIDENCE_BUDGET,'')
        assert body==old['discoveryBody'] and old['raw']==new['raw'] and old['baseBody']==new['baseBody']
    with pytest.raises(ValueError):freeze_follow(f,'1.0')
    with patch('run_full_stt_trial.prepare_follow',return_value=follow):
        r=execute(follow,tmp_path/'datasets/mock',simulate=True,call=mock)
        s=score(follow,r,audits(r));assert s['javaTotal']==s['javaAccepted']==9


def test_catalog_reuses_recorded_payloads_and_existing_hydrator(tmp_path):
    f=freeze_follow(plan(),'.3331')
    def source_mock(key,body,timeout=90):
        p=json.loads(body['messages'][1]['content'])
        if set(p)=={'raw'} and p['raw'][0]['id']!='a':
            return response(dict(reviewedSegmentIds=[r['id'] for r in p['raw']],candidates=[],uncertainSegments=[],truncated=False))
        return mock(key,body,timeout)
    with patch('run_full_stt_trial.prepare_follow',return_value=f):r=execute(f,tmp_path/'datasets/source',simulate=True,call=source_mock)
    normals={u['name']:json.loads(u['baseBody']['messages'][1]['content']) for u in f['units'] if u['name'] in {'Control-Review','Control-SingleMenu'}}
    new=freeze_catalog(f,r,'.0538',normals);assert new['maxApiCalls']==10 and new['reservedCostUsd']=='0.0490'
    assert all(u['fixedPayloads'] for u in new['units']) and new['videoRepetitions']==0
    with patch('run_full_stt_trial.prepare_catalog',return_value=new):
        result=execute(new,tmp_path/'datasets/catalog',simulate=True,call=mock);s=score(new,result,audits(result))
        assert s['javaAccepted']==s['javaTotal']==5 and result['simulatedCalls']==10
        assert all(a['stage']!='DISCOVER' for a in result['attempts'])
        for u in result['units']:
            c=u['candidates'][0];q=next(e for e in c['scope']['evidence'] if e['role']=='PRIMARY')
            raw=next(r for r in c['payload']['raw'] if r['id']==q['segmentId']);assert q['quote']==raw['text']
