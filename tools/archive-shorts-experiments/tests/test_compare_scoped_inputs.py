import copy
import json
from decimal import Decimal
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from fixed_review_suite import digest
from fixed_scope_diagnostic import prepare as original
from compare_scoped_inputs import prepare,prepare_full,validate,score,AUTHORIZATION,FULL_SOURCE_HASHES
from run_issue_scoped_comparison import execute
from test_fixed_scope_diagnostic import seed_fixture
from test_scoped_input_study import mock_scoped
from test_bound_comparison_runner import fake_audit
from test_run_issue_scoped_comparison import source as nine_case_source
from issue_scoped_review import hydrate_selection


class PairedScopeTests(unittest.TestCase):
    def test_full_followup_debits_first_reservation_and_preserves_nine_cases(self):
        seed=seed_fixture()
        with patch('fixed_scope_diagnostic.SEED_HASH',digest(seed)),tempfile.TemporaryDirectory() as tmp:
            first_source=original(seed);auth=dict(AUTHORIZATION,previousRemainingUsd=first_source['remainingBudgetUsd'])
            with patch('compare_scoped_inputs.AUTHORIZATION',auth),patch('compare_scoped_inputs.SOURCE_HASH',first_source['artifactHash']):
                first=prepare(first_source,auth)
                meta=dict(schemaVersion=first['schemaVersion'],artifactHash=first['artifactHash'],
                    authorizationHash=first['authorizationHash'],additionalFundingHash=first['additionalFundingHash'],
                    status='COMPLETED_PENDING_JAVA_AUDIT',runKind='LIVE',reservedCostUsd=first['reservedCostUsd'],
                    consumedReservationUsd=first['reservedCostUsd'],apiCalls=24,unknownCostAttempts=0)
                source=nine_case_source();templates={r['case']:r['body'] for r in source['requests'] if r['repeat']==1}
                records=[]
                for n in range(1,4):
                    for c in source['cases']:
                        base=templates[c['name']];p=json.loads(base['messages'][1]['content']);candidate=p['candidates'][0]
                        row=next(r for r in p['raw'] if r['id']==candidate['anchorId'])
                        scope=hydrate_selection(dict(candidateId=candidate['candidateId'],anchorId=candidate['anchorId'],
                            issueSummary='fixture',selections=[dict(segmentId=row['id'],start=0,end=len(row['text']),role='PRIMARY')]),p)
                        records.append(dict(case=c['name'],repeat=n,baseBody=base,selectedScope=scope,sourceAttemptHash='fixture'))
                full_seed=dict(schemaVersion='full-scoped-regression-seed-1',hashes=FULL_SOURCE_HASHES,
                    cases=source['cases'],config=source['config'],records=records)
                with patch('compare_scoped_inputs.FULL_SEED_HASH',digest(full_seed)),patch('compare_scoped_inputs.FIRST_REPORT_HASH',digest(meta)):
                    f=prepare_full(first_source,full_seed,meta,auth);validate(f)
                    self.assertEqual(f['plannedUnits'],54)
                    self.assertEqual(Decimal(f['remainingBudgetUsd']),Decimal('1')-Decimal(first['reservedCostUsd'])-Decimal(f['reservedCostUsd']))
                    r=execute(f,Path(tmp)/'datasets'/'full',None,mock_scoped,simulate=True)
                    from private_report_bundle import load as load_bundle
                    self.assertEqual(load_bundle(Path(tmp)/'datasets'/'full'/'report-index.json'),r)
                    s=score(f,r,fake_audit(r))
                    self.assertEqual(len(s['summary']),18)
                    self.assertTrue(all(x['developmentExpectedDecision']=='REVIEW_REQUIRED' for x in s['results'] if x['case']=='C'))
                    self.assertTrue(all(x['developmentExpectedDecision']=='PASS' for x in s['results'] if x['case']=='N1'))
                    with self.assertRaises(ValueError):prepare_full(first_source,full_seed,dict(meta,status='PARTIAL'),auth)
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
