import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from fixed_review_suite import digest
from prepare_flow_regression import OLD,prepare as flow_prepare
from run_issue_scoped_comparison import AUTHORIZATION,execute,prepare,validate
from test_prepare_flow_regression import inputs
from issue_scoped_review import hydrate_selection
from reservation_ledger import seed


def ledger(tmp, artifact):
    path=Path(tmp)/'datasets/reservations.sqlite3'
    seed(path,artifact['authorization'],[dict(artifactHash='fixture-history',reservedCostUsd='0',reportHash='fixture')])
    return path


def source():
    initial,auth=inputs()
    system=('공통 정책\n# 후보 근거 검증\n'+OLD+'\n'
            '각 candidateId에 정확히 하나의 assessment old\nevidence는 최대 8개로 old\n'
            'category는 검토 이유 CAT\nscore는 old\n# 대상 필드 형식 예시\nold\n'
            '# 범용 맥락 참고 기준\nreference unchanged')
    for r in initial['requests']: r['body']['messages'][0]['content']=system
    initial['artifactHash']=digest({k:v for k,v in initial.items() if k!='artifactHash'})
    low=flow_prepare(initial,auth,variant='REASONING_LOW')
    controls=dict(schemaVersion='owner-closer-normal-boundaries-1',ownerApproved=True,
                  reviewStatus='OWNER_DEVELOPMENT_ONLY',independentGoldApprovals=0,
                  controls=[dict(name=n,decision='PASS',segments=['first','second','third']) for n in ('N5','N6')])
    return flow_prepare(low,auth,variant='TARGET_WIRE',source_arm='CANDIDATE',
                        owner_controls=controls,candidate_only=True,max_completion_tokens=8000)


def response(key,body,timeout=90):
    payload=json.loads(body['messages'][1]['content'])
    if 'original' in payload:
        original=payload['original']; scope=payload['selectedScope']; c=original['candidates'][0]
        out=dict(candidateId=c['candidateId'],scopeHash=scope['scopeHash'],
                 relevance=[dict(selectionIndex=i,relation='SAME_ISSUE',reason='fixture relation')
                            for i in range(len(scope['evidence']))],
                 usedSelectionIndexes=list(range(len(scope['evidence']))),
                 assessment=dict(decision='UNCERTAIN',reason='fixture missing context',alternativeInterpretation=None,
                                 category=None,target=None,targetType=None,targetReason=None,score=None,
                                 missingInformation=['fixture missing information']))
    elif '# 후보 쟁점의 근거 선택' in body['messages'][0]['content']:
        c=payload['candidates'][0];r=next(r for r in payload['raw'] if r['id']==c['anchorId'])
        out=dict(candidateId=c['candidateId'],anchorId=c['anchorId'],issueSummary='fixture issue',
                 selections=[dict(segmentId=r['id'],start=0,end=len(r['text']),role='PRIMARY')])
    else:
        c=payload['candidates'][0];r=next(r for r in payload['raw'] if r['id']==c['anchorId'])
        out=dict(verifications=[dict(candidateId=c['candidateId'],assessment=dict(segmentId=r['id'],decision='UNCERTAIN',
             reason='fixture missing context',evidenceText=r['text'],evidence=[dict(segmentId=r['id'],quote=r['text'],role='PRIMARY')],
             target=None,missingInformation=['fixture missing information']))])
    return dict(model='gpt-6-luna',usage=dict(prompt_tokens=100,completion_tokens=100),
                choices=[dict(finish_reason='stop',message=dict(content=json.dumps(out)))])


class ComparisonTests(unittest.TestCase):
    def test_freeze_reserves_all_three_stages_before_any_calls(self):
        s=source();old=copy.deepcopy(s);a=prepare(s,AUTHORIZATION)
        self.assertEqual(s,old);validate(a)
        self.assertEqual(a['plannedUnits'],54);self.assertEqual(a['maxApiCalls'],81)
        self.assertLess(float(a['reservedCostUsd']),1)
        self.assertEqual(len(a['jobs']),54)
        self.assertTrue(all(len(j['stages'])==(1 if j['arm']=='BASELINE' else 2) for j in a['jobs']))
        self.assertEqual(a['authorization']['previousRemainingUsd'],'0.0257290')
        self.assertIsNone(a['qualityGateMet'])

    def test_execution_records_stages_without_semantic_approval(self):
        a=prepare(source(),AUTHORIZATION)
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'datasets'/'run'
            r=execute(a,path,'fake',response,ledger_path=ledger(tmp,a))
            from compare_recorded_candidates import audit_export
            disk=json.loads((path/'report.json').read_text())
            expected=audit_export(disk)
            expected.update(trialSchemaVersion='recorded-flow-regression-1',repetitionsPerCondition=3)
            self.assertEqual(json.loads((path/'audit-input.json').read_text()),expected)
        self.assertEqual(r['apiCalls'],81)
        self.assertEqual(r['unknownCostAttempts'],0)
        self.assertEqual(r['status'],'COMPLETED_PENDING_JAVA_AUDIT')
        self.assertEqual(len(r['calls']),54)
        self.assertIsNone(r['qualityGateMet'])
        for c in r['calls']:
            if c['arm']=='CANDIDATE':
                self.assertEqual(c['stages'][1]['body']['model'],'gpt-6-luna')
                self.assertIsNone(c['reviewPacket']['semanticQualityGateMet'])
                self.assertFalse(c['reviewPacket']['runtimeUseAuthorized'])

    def test_selection_rejection_skips_second_stage_without_repair(self):
        def bad_selection(key,body,timeout=90):
            r=response(key,body,timeout)
            if '# 후보 쟁점의 근거 선택' in body['messages'][0]['content']:
                o=json.loads(r['choices'][0]['message']['content']);o['selections'][0]['end']=999
                r['choices'][0]['message']['content']=json.dumps(o)
            return r
        with tempfile.TemporaryDirectory() as tmp:
            a=prepare(source(),AUTHORIZATION)
            r=execute(a,Path(tmp)/'datasets'/'run','fake',bad_selection,ledger_path=ledger(tmp,a))
        self.assertEqual(r['apiCalls'],54)
        self.assertEqual(r['status'],'COMPLETED_PENDING_JAVA_AUDIT')
        self.assertTrue(all(c['status']=='SELECTION_REJECTED' and 'output' not in c
                            for c in r['calls'] if c['arm']=='CANDIDATE'))

    def test_transport_unknown_stops_without_retry_or_resume(self):
        a=prepare(source(),AUTHORIZATION)
        # Test a serial stop; concurrency is separately frozen and bounded.
        def timeout(*args,**kwargs): raise TimeoutError('fixture timeout')
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'datasets'/'run'
            r=execute(a,path,'fake',timeout,ledger_path=ledger(tmp,a))
            with self.assertRaises(ValueError):execute(a,path,'fake',lambda *a,**k:self.fail('retry'))
        self.assertEqual(r['status'],'PARTIAL')
        self.assertGreaterEqual(r['unknownCostAttempts'],1)
        self.assertLessEqual(r['apiCalls'],3)
        self.assertEqual(r['reservedCostUsd'],a['reservedCostUsd'])

    def test_tampered_frozen_or_authorization_fails_before_transport(self):
        for mode in ('auth','prompt','budget','concurrency','output-limit'):
            a=prepare(source(),AUTHORIZATION)
            if mode=='auth':a['authorization']['userAuthorized']=False
            if mode=='prompt':a['jobs'][0]['stages'][0]['body']['messages'][0]['content']+='changed'
            if mode=='budget':a['reservedCostUsd']='.01'
            if mode=='concurrency':a['maxConcurrency']=100
            if mode=='output-limit':a['config']['maxCompletionTokens']=99999
            with self.subTest(mode=mode),tempfile.TemporaryDirectory() as tmp,self.assertRaises(ValueError):
                execute(a,Path(tmp)/'datasets'/'run','fake',lambda *a,**k:self.fail('paid call'))

    def test_dynamic_stage_request_fits_reserved_envelope(self):
        from issue_scoped_review import judgment_request
        from run_boundary_comparison import reserve
        a=prepare(source(),AUTHORIZATION)
        for j in a['jobs']:
            if j['arm']!='CANDIDATE':continue
            payload=json.loads(j['baseBody']['messages'][1]['content']);c=payload['candidates'][0]
            row=next(r for r in payload['raw'] if r['id']==c['anchorId'])
            scope=hydrate_selection(dict(candidateId=c['candidateId'],anchorId=c['anchorId'],issueSummary='fixture',
                selections=[dict(segmentId=row['id'],start=0,end=len(row['text']),role='PRIMARY')]),payload)
            body=judgment_request(j['baseBody'],scope)
            tokens,_=reserve(body,a['config'])
            self.assertLessEqual(tokens,j['stages'][1]['reservedInputTokensHeuristic'])
