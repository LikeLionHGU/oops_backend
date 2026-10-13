import copy
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from decimal import Decimal

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from fixed_review_suite import digest
from compare_review_prompts import digest as audit_digest
from run_target_focus_comparison import freeze as freeze_prior, audit_input
from run_target_focus_regression import freeze, validate, score
from run_issue_scoped_comparison import execute
from test_run_target_focus_comparison import inputs
from test_context_reselection import initial
from test_scoped_input_study import mock_scoped


def source():
    prior=freeze_prior(*inputs(),'.4')
    ticket,_=initial('PASS')
    return prior,'fixture-report',dict(C=copy.deepcopy(ticket),D=copy.deepcopy(ticket))


def audit(report):
    return dict(sourceReportSha256=audit_digest(audit_input(report)),results=[dict(case=u['case'],arm=u['arm'],
        repeat=u['repeat'],accepted='output' in u,
        decision=u['stages'][0].get('boundReceipt',{}).get('modelOutput',{}).get('assessment',{}).get('decision'))
        for u in report['calls']])


class FocusRegressionTests(unittest.TestCase):
    def test_42_pinned_judgments_same_five_inputs_and_cd_no_source_mutation(self):
        args=source(); before=copy.deepcopy(args); f=freeze(*args,'.3')
        self.assertEqual(args,before); self.assertEqual(len(f['jobs']),42)
        self.assertEqual(f['repetitionsPerCondition'],3)
        self.assertEqual(Decimal(f['reservedCostUsd'])+Decimal(f['remainingBudgetUsd']),Decimal('.3'))
        self.assertEqual({(j['case'],j['arm'],j['repeat']) for j in f['jobs']},
                         {(c['name'],a,n) for c in f['source']['cases'] for a in ('BASELINE','CANDIDATE') for n in (1,2,3)})
        for case in f['source']['cases']:
            for arm in ('BASELINE','CANDIDATE'):
                bodies=[j['stages'][0]['body'] for j in f['jobs'] if (j['case'],j['arm'])==(case['name'],arm)]
                self.assertTrue(all(b==bodies[0] for b in bodies))
        for j in args[0]['jobs']:
            current=next(x for x in f['jobs'] if (x['case'],x['arm'],x['repeat'])==(j['case'],j['arm'],1))
            self.assertEqual(current['stages'][0]['body'],j['stages'][0]['body'])

    def test_missing_cd_changed_scope_or_budget_blocks(self):
        for mode in ('missing','scope','budget','increase'):
            f,h,t=source(); available='.3'
            if mode=='missing': t.pop('C')
            if mode=='scope': t['C']['selectedScope']['evidence'][0]['quote']='not original'
            if mode=='budget': available='.01'
            if mode=='increase': available='1'
            with self.subTest(mode=mode),self.assertRaises(ValueError): freeze(f,h,t,available)

    def test_shared_mock_run_all_repeats_audit_metadata_and_accounting(self):
        args=source()
        with patch('run_target_focus_regression.source_inputs',return_value=args),tempfile.TemporaryDirectory() as tmp:
            f=freeze(*args,'.3');validate(f)
            with patch('run_issue_scoped_comparison.read_key',side_effect=AssertionError('key read')):
                r=execute(f,Path(tmp)/'datasets/run',None,mock_scoped,simulate=True)
            s=score(f,r,audit(r))
            self.assertEqual(r['apiCalls'],0);self.assertEqual(r['simulatedCalls'],42)
            self.assertTrue(s['mechanicalContractGateMet']);self.assertEqual(len(s['summary']),14)
            self.assertEqual(audit_input(r)['trialSchemaVersion'],'recorded-flow-regression-1')
            self.assertTrue(all(x['observations']==3 for x in s['summary']))
            self.assertTrue(all(x['developmentDecisionMatches'] is None for x in s['summary']))
            self.assertFalse(s['runtimeUseAuthorized']);self.assertIsNone(s['semanticQualityGateMet'])

    def test_changed_recipe_blocks_before_callback_output_or_key(self):
        args=source()
        with patch('run_target_focus_regression.source_inputs',return_value=args),tempfile.TemporaryDirectory() as tmp:
            f=freeze(*args,'.3'); f['jobs'].pop(); out=Path(tmp)/'datasets/run'
            with patch('run_issue_scoped_comparison.read_key',side_effect=AssertionError('key read')):
                with self.assertRaises(ValueError): execute(f,out,None,lambda *a,**kw:self.fail('called'),simulate=True)
            self.assertFalse(out.exists())

    def test_wrong_repeat_missing_audit_or_changed_response_refused(self):
        args=source()
        with patch('run_target_focus_regression.source_inputs',return_value=args),tempfile.TemporaryDirectory() as tmp:
            f=freeze(*args,'.3'); r=execute(f,Path(tmp)/'datasets/run',None,mock_scoped,simulate=True)
            for mode in ('repeat','audit','output','partial'):
                changed=copy.deepcopy(r)
                if mode=='repeat': changed['calls'][0]['repeat']=2
                if mode=='output': changed['calls'][0]['output']['verifications'][0]['assessment']['reason']='changed'
                if mode=='partial': changed['calls'].pop()
                a=audit(changed)
                if mode=='audit': a['results'].append(copy.deepcopy(a['results'][0]))
                with self.subTest(mode=mode),self.assertRaises(ValueError): score(f,changed,a)
