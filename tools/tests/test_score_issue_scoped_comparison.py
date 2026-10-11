import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from compare_recorded_candidates import audit_export
from compare_review_prompts import digest as audit_digest
from run_issue_scoped_comparison import AUTHORIZATION,execute,prepare
from score_issue_scoped_comparison import score
from test_run_issue_scoped_comparison import source,response


def fixture():
    frozen=prepare(source(),AUTHORIZATION)
    with tempfile.TemporaryDirectory() as tmp:
        report=execute(frozen,Path(tmp)/'datasets'/'run','fake',response)
    exported=audit_export(json.loads(json.dumps(report)))
    exported.update(trialSchemaVersion='recorded-flow-regression-1',repetitionsPerCondition=3)
    audit=dict(sourceReportSha256=audit_digest(exported),results=[
        dict(case=r['case'],arm=r['arm'],repeat=r['repeat'],accepted=True,decision='UNCERTAIN',failureCode='NONE')
        for r in report['calls']])
    return frozen,report,audit


class ScoreTests(unittest.TestCase):
    def test_complete_score_separates_semantics_and_two_stage_cost(self):
        args=fixture();before=copy.deepcopy(args);result=score(*args)
        self.assertEqual(args,before)
        self.assertEqual(len(result['results']),54)
        self.assertFalse(result['developmentRegressionGateMet'])
        self.assertIsNone(result['semanticQualityGateMet'])
        self.assertFalse(result['runtimeUseAuthorized'])
        self.assertEqual([r['apiCalls'] for r in result['armCosts']],[27,54])
        self.assertTrue(all(r['semanticIssueMatched'] is None for r in result['results']))

    def test_partial_missing_reordered_or_wrong_audit_is_refused(self):
        for mode in ('partial','missing','order','audit','duplicate-audit'):
            frozen,report,audit=fixture()
            if mode=='partial':report['status']='PARTIAL'
            if mode=='missing':report['calls'].pop()
            if mode=='order':report['calls'][0],report['calls'][1]=report['calls'][1],report['calls'][0]
            if mode=='audit':audit['sourceReportSha256']='wrong'
            if mode=='duplicate-audit':audit['results'].append(copy.deepcopy(audit['results'][0]))
            with self.subTest(mode=mode),self.assertRaises(ValueError):score(frozen,report,audit)

    def test_stage_or_scope_rewrite_is_refused_even_after_new_audit_hash(self):
        for mode in ('scope','stage','output'):
            frozen,report,audit=fixture()
            c=next(r for r in report['calls'] if r['arm']=='CANDIDATE')
            if mode=='scope':c['selectedScope']['evidence'][0]['quote']='invented'
            if mode=='stage':c['stages'][1]['body']['messages'][0]['content']+='changed'
            if mode=='output':c['output']['verifications'][0]['assessment']['reason']='changed'
            exported=audit_export(report)
            exported.update(trialSchemaVersion='recorded-flow-regression-1',repetitionsPerCondition=3)
            audit['sourceReportSha256']=audit_digest(exported)
            with self.subTest(mode=mode),self.assertRaises(ValueError):score(frozen,report,audit)
