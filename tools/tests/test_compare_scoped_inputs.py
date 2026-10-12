import copy
from decimal import Decimal
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from fixed_review_suite import digest
from fixed_scope_diagnostic import prepare as original
from compare_scoped_inputs import prepare,validate,score,AUTHORIZATION
from run_issue_scoped_comparison import execute
from test_fixed_scope_diagnostic import seed_fixture
from test_scoped_input_study import mock_scoped
from test_bound_comparison_runner import fake_audit


class PairedScopeTests(unittest.TestCase):
    def test_exact_budget_and_same_scope_model_reference_baseline_are_preserved(self):
        seed=seed_fixture()
        with patch('fixed_scope_diagnostic.SEED_HASH',digest(seed)):
            source=original(seed);auth=dict(AUTHORIZATION,previousRemainingUsd=source['remainingBudgetUsd'])
            with patch('compare_scoped_inputs.AUTHORIZATION',auth),patch('compare_scoped_inputs.SOURCE_HASH',source['artifactHash']):
                f=prepare(source,auth);validate(f)
                self.assertEqual(f['plannedUnits'],24)
                self.assertEqual(Decimal(f['remainingBudgetUsd']),Decimal('1')-Decimal(f['reservedCostUsd']))
                for j in f['jobs']:
                    old=next(x for x in source['jobs'] if x['case']==j['case'] and x['repeat']==j['repeat'])
                    self.assertEqual(j['fixedSelectedScope'],old['fixedSelectedScope'])
                    self.assertEqual(j['baseBody'],old['baseBody'])
                    if j['arm']=='BASELINE':self.assertEqual(j['stages'],old['stages'])
                with self.assertRaises(ValueError):prepare(source,dict(auth,userAuthorized=False))
                changed=copy.deepcopy(f);changed['jobs'][0]['judgmentVariant']='RAW_REMOVED'
                with self.assertRaises(ValueError):validate(changed)

    def test_mock_both_versions_link_receipts_without_accuracy_claim(self):
        seed=seed_fixture()
        with patch('fixed_scope_diagnostic.SEED_HASH',digest(seed)),tempfile.TemporaryDirectory() as tmp:
            source=original(seed);auth=dict(AUTHORIZATION,previousRemainingUsd=source['remainingBudgetUsd'])
            with patch('compare_scoped_inputs.AUTHORIZATION',auth),patch('compare_scoped_inputs.SOURCE_HASH',source['artifactHash']):
                f=prepare(source,auth);r=execute(f,Path(tmp)/'datasets'/'paired',None,mock_scoped,simulate=True)
                s=score(f,r,fake_audit(r));self.assertIsNone(s['diagnosticExpectedDecisionsMet'])
                self.assertIsNone(s['semanticQualityGateMet']);self.assertFalse(s['runtimeUseAuthorized'])
                self.assertEqual(len(s['summary']),8)
                self.assertTrue(all(c['apiAttempts']==0 for c in s['armCosts']))
                self.assertEqual({u['stages'][0]['localAttempt']['schemaVersion'] for u in r['calls']},
                                 {'server-bound-review-draft-3','server-bound-review-draft-4'})
                changed=copy.deepcopy(r);changed['calls'][0]['stages'][0]['localAttempt']=copy.deepcopy(r['calls'][1]['stages'][0]['localAttempt'])
                with self.assertRaises(ValueError):score(f,changed,fake_audit(changed))
