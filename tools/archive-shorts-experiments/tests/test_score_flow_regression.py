import copy
from pathlib import Path
import sys
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from prepare_flow_regression import prepare
from test_prepare_flow_regression import inputs
from fixed_review_suite import digest
from compare_recorded_candidates import audit_export
from compare_review_prompts import digest as audit_digest
from score_flow_regression import score,compare_requests


def fixture():
    f=prepare(*inputs())
    calls=[]; results=[]
    for r in f['requests']:
        wanted='REVIEW_REQUIRED' if r['case'] in {'B','C','D'} else 'PASS'
        call={k:r[k] for k in ('case','arm','repeat','requestHash')}
        cid=next(c for c in f['cases'] if c['name']==r['case'])['payload']['candidates'][0]['candidateId']
        call['output']={'verifications':[dict(candidateId=cid,assessment=dict(segmentId=next(
            c for c in f['cases'] if c['name']==r['case'])['payload']['candidates'][0]['anchorId'],evidence=[]))]}
        calls.append(call)
        results.append({**{k:r[k] for k in ('case','arm','repeat')},'decision':wanted,'accepted':True,'failureCode':'NONE'})
    report=dict(artifactHash=f['artifactHash'],trialSchemaVersion=f['schemaVersion'],authorizationHash=f['authorizationHash'],
                status='COMPLETED_PENDING_JAVA_AUDIT',calls=calls,cases=copy.deepcopy(f['cases']))
    audit=dict(sourceReportSha256=audit_digest(audit_export(report)),results=results)
    return f,report,audit


class ScoreTests(unittest.TestCase):
    def test_development_gate_does_not_authorize_semantics_or_runtime(self):
        args=fixture(); original=copy.deepcopy(args)
        result=score(*args)
        self.assertTrue(result['developmentRegressionGateMet'])
        self.assertIsNone(result['semanticQualityGateMet'])
        self.assertFalse(result['runtimeUseAuthorized'])
        self.assertEqual(original,args)

    def test_any_b_miss_normal_false_positive_or_contract_rejection_fails_gate(self):
        for name,decision,accepted in [('B','PASS',True),('N3','REVIEW_REQUIRED',True),('C','REVIEW_REQUIRED',False)]:
            f,r,a=fixture()
            row=next(row for row in a['results'] if row['arm']=='CANDIDATE' and row['case']==name)
            row.update(decision=decision,accepted=accepted)
            self.assertFalse(score(f,r,a)['developmentRegressionGateMet'])

    def test_missing_or_reordered_responses_and_audit_cannot_be_ignored(self):
        for mode in ('order','audit','source'):
            f,r,a=fixture()
            if mode=='order': r['calls'].reverse()
            elif mode=='audit': a['results'].pop()
            else: a['sourceReportSha256']='wrong'
            with self.assertRaises(ValueError): score(f,r,a)

    def test_request_equivalence_checks_all_repeats_and_whole_provider_body(self):
        f=prepare(*inputs()); other=copy.deepcopy(f)
        self.assertTrue(compare_requests(f,other,left_arm='BASELINE')['sameProviderBody'])
        other['requests'][0]['body']['temperature']=.9
        other['artifactHash']=digest({k:v for k,v in other.items() if k!='artifactHash'})
        with self.assertRaises(ValueError): compare_requests(f,other,left_arm='BASELINE')
