import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from fixed_review_suite import digest
from issue_scoped_review import judgment_request as original_judgment
from run_issue_scoped_comparison import AUTHORIZATION,execute,prepare,prepare_catalog,validate
from span_catalog_review import catalog,hydrate,judgment_request,selection_request
from test_issue_scoped_review import fixture
from test_run_issue_scoped_comparison import source,response


def catalog_response(key,body,timeout=90):
    p=json.loads(body['messages'][1]['content'])
    if 'targetSpans' not in p:return response(key,body,timeout)
    c=p['original']['candidates'][0]
    out=dict(candidateId=c['candidateId'],anchorId=c['anchorId'],issueSummary='fixture',
             selections=[dict(segmentId=c['anchorId'],role='PRIMARY',spanId='FULL')])
    return dict(model='gpt-6-luna',usage=dict(prompt_tokens=100,completion_tokens=100),
                choices=[dict(finish_reason='stop',message=dict(content=json.dumps(out)))])


class CatalogTests(unittest.TestCase):
    def test_catalog_uses_actual_word_spans_and_hydrates_without_generated_offsets(self):
        payload,_,_,_=fixture();before=copy.deepcopy(payload)
        out=dict(candidateId='issue',anchorId='a',issueSummary='fixture',selections=[
            dict(segmentId='a',role='PRIMARY',spanId='FULL'),
            dict(segmentId='a',role='TARGET',spanId='w0-1'),
            dict(segmentId='b',role='CONTEXT',spanId='FULL')])
        scope=hydrate(out,payload)
        self.assertEqual(scope['evidence'][1]['quote'],'이 장소의')
        self.assertEqual(scope['evidence'][0]['quote'],payload['raw'][0]['text'])
        self.assertEqual(payload,before)
        self.assertNotIn('start',out['selections'][0])
        for sid,spans in catalog(payload).items():
            row=next(r for r in payload['raw'] if r['id']==sid)
            for span in spans.values():self.assertEqual(span['text'],row['text'][span['start']:span['end']])

    def test_invalid_catalog_ids_roles_generated_fields_and_primary_spans_fail(self):
        for mode in ('unknown','outside','offset','quote','primary-span','target-full'):
            payload,_,_,_=fixture()
            out=dict(candidateId='issue',anchorId='a',issueSummary='fixture',selections=[dict(segmentId='a',role='PRIMARY',spanId='FULL')])
            q=out['selections'][0]
            if mode=='unknown':q.update(role='TARGET',spanId='unknown')
            if mode=='outside':q['segmentId']='outside'
            if mode=='offset':q['start']=0
            if mode=='quote':q['quote']='invented'
            if mode=='primary-span':q['spanId']='w0-0'
            if mode=='target-full':q['role']='TARGET'
            with self.subTest(mode=mode),self.assertRaises(ValueError):hydrate(out,payload)

    def test_catalog_prompts_preserve_raw_reference_and_old_prompt_version(self):
        s=source();base=s['requests'][0]['body'];old=copy.deepcopy(base)
        p=json.loads(base['messages'][1]['content']);c=p['candidates'][0]
        scope=hydrate(dict(candidateId=c['candidateId'],anchorId=c['anchorId'],issueSummary='fixture',
                    selections=[dict(segmentId=c['anchorId'],role='PRIMARY',spanId='FULL')]),p)
        first=selection_request(base);second=judgment_request(base,scope)
        self.assertEqual(json.loads(first['messages'][1]['content'])['original'],p)
        self.assertIn('targetSpans',json.loads(first['messages'][1]['content']))
        self.assertIn('PERSON/GROUP/REGION',second['messages'][0]['content'])
        marker='# 범용 맥락 참고 기준'
        self.assertEqual(second['messages'][0]['content'].split(marker)[1],base['messages'][0]['content'].split(marker)[1])
        self.assertNotIn('대상 및 단계 연결 계약 보완',original_judgment(base,scope)['messages'][0]['content'])
        self.assertEqual(base,old)

    def test_followup_debits_first_reservation_and_fake_executes_only_candidate_arm(self):
        s=source();initial=prepare(s,AUTHORIZATION)
        prior=dict(schemaVersion=initial['schemaVersion'],artifactHash=initial['artifactHash'],
                   authorizationHash=digest(AUTHORIZATION),status='COMPLETED_PENDING_JAVA_AUDIT',
                   reservedCostUsd=initial['reservedCostUsd'])
        a=prepare_catalog(s,AUTHORIZATION,prior);validate(a)
        self.assertEqual(set(a['priorReport']),{'schemaVersion','artifactHash','authorizationHash','status','reservedCostUsd','sourceReportHash'})
        self.assertEqual(a['plannedUnits'],27);self.assertEqual(a['maxApiCalls'],54)
        self.assertTrue(all(j['arm']=='CANDIDATE' for j in a['jobs']))
        self.assertLessEqual(float(a['reservedCostUsd'])+float(prior['reservedCostUsd']),1)
        with tempfile.TemporaryDirectory() as tmp:r=execute(a,Path(tmp)/'datasets'/'run','fake',catalog_response)
        self.assertEqual(r['status'],'COMPLETED_PENDING_JAVA_AUDIT');self.assertEqual(r['apiCalls'],54)
        self.assertIsNone(r['qualityGateMet'])
        from compare_recorded_candidates import audit_export
        from compare_review_prompts import digest as audit_digest
        from score_issue_scoped_comparison import score
        exported=audit_export(json.loads(json.dumps(r)))
        exported.update(trialSchemaVersion='recorded-flow-regression-1',repetitionsPerCondition=3)
        audit=dict(sourceReportSha256=audit_digest(exported),results=[
            dict(case=c['case'],arm=c['arm'],repeat=c['repeat'],decision='UNCERTAIN',accepted=True,failureCode='NONE')
            for c in r['calls']])
        measured=score(a,r,audit)
        self.assertEqual(len(measured['results']),27)
        self.assertEqual(len(measured['armCosts']),1)
        self.assertIsNone(measured['semanticQualityGateMet'])
        prior['reservedCostUsd']='0'
        with self.assertRaises(ValueError):prepare_catalog(s,AUTHORIZATION,prior)
