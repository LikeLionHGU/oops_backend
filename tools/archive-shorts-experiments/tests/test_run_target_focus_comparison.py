import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from decimal import Decimal

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from bound_review_contract import prepare_attempt, validate_attempt, receive
from fixed_review_suite import digest
from compare_review_prompts import digest as audit_digest
from prepare_target_focus_comparison import build_preview
from run_target_focus_comparison import freeze, validate, score, audit_input
from run_issue_scoped_comparison import execute
from reservation_ledger import seed, balance
from target_focus_policy import DELTA, focused_request
from test_context_reselection import initial
from test_scoped_input_study import mock_scoped


def inputs():
    ticket, _ = initial('PASS')
    auth=dict(userAuthorized=True,additionalBudgetUsd='1.00',schemaVersion='fixture-only')
    cases, jobs, calls, observed = [], [], [], []
    for i in range(5):
        base=copy.deepcopy(ticket['baseRequest']); base['messages'][0]['content'] += f'\nreference fixture {i}'
        t=prepare_attempt(base,ticket['selectedScope'],f'fixture-{i}',judgment_variant='CLOSED_SCOPE')
        name=f'input-{i}'; wanted='REVIEW_REQUIRED' if i<3 else 'PASS'
        cases.append(dict(name=name,payload=t['originalInput'],developmentExpectation=wanted))
        jobs.append(dict(case=name,arm='BASELINE'))
        calls.append(dict(case=name,arm='BASELINE',stages=[dict(localAttempt=t)]))
        observed.append(dict(case=name,arm='BASELINE',assessment=dict(decision='PASS',reason='fixture only')))
    prior=dict(source=dict(cases=cases),jobs=jobs,remainingBudgetUsd='0.4',authorization=auth,
        authorizationHash=digest(auth),additionalFundingHash='fixture-only',
        config=dict(maxCompletionTokens=8000,pricing=dict(inputUsdPerMillion='.1',outputUsdPerMillion='.5')))
    report=dict(calls=calls)
    s=dict(complete=True,mechanicalContractGateMet=True,runKind='LIVE',results=observed)
    return build_preview(prior,report,s), prior, report


def audit(report):
    return dict(sourceReportSha256=audit_digest(audit_input(report)),results=[dict(
        case=u['case'],arm=u['arm'],repeat=1,accepted='output' in u,
        decision=u['stages'][0].get('boundReceipt',{}).get('modelOutput',{}).get('assessment',{}).get('decision'))
        for u in report['calls']])


class TargetFocusRunnerTests(unittest.TestCase):
    def test_variant_is_single_delta_and_preserves_closed_exposure(self):
        original,_=initial('PASS')
        focused=prepare_attempt(original['baseRequest'],original['selectedScope'],'focus',
                                judgment_variant='TARGET_FOCUS_CLOSED_SCOPE')
        validate_attempt(focused)
        self.assertEqual(focused['requestBody'],focused_request(original['requestBody']))
        self.assertEqual(focused['schemaVersion'],'server-bound-review-draft-5')
        self.assertTrue(focused['exposure']['generatedTextChannelsExcluded'])
        self.assertEqual(focused['originalInput'],original['originalInput'])
        self.assertFalse(focused['runtimeUseAuthorized'])
        received=receive(focused,focused['requestBody'],mock_scoped(None,focused['requestBody']))
        self.assertEqual(received['modelOutput']['assessment']['decision'],'UNCERTAIN')

    def test_freeze_matches_both_reviewed_bodies_and_reserves_all_ten(self):
        args=inputs(); before=copy.deepcopy(args); f=freeze(*args,'0.4')
        self.assertEqual(args,before); self.assertEqual(f['plannedUnits'],10)
        self.assertEqual(f['maxConcurrency'],1)
        self.assertEqual(Decimal(f['reservedCostUsd'])+Decimal(f['remainingBudgetUsd']),Decimal('.4'))
        for i,g in enumerate(args[0]['comparisons']):
            pair=f['jobs'][2*i:2*i+2]
            self.assertEqual({j['arm']:j['stages'][0]['body'] for j in pair},
                             dict(BASELINE=g['baselineRequest'],CANDIDATE=g['candidateRequest']))

    def test_preview_changes_or_insufficient_budget_are_rejected(self):
        for mode in ('changed','candidate','budget','increase'):
            p,f,r=inputs(); remaining='0.4'
            if mode=='changed': p['apiCalls']=1
            if mode=='candidate':
                p['comparisons'][0]['candidateRequest']['messages'][1]['content']+='changed'
                p['artifactHash']=digest({k:v for k,v in p.items() if k!='artifactHash'})
            if mode=='budget': remaining='.00001'
            if mode=='increase': remaining='.5'
            with self.subTest(mode=mode),self.assertRaises(ValueError): freeze(p,f,r,remaining)

    def test_mock_full_run_scores_contracts_not_semantics_and_has_no_key_or_ledger(self):
        args=inputs()
        with patch('run_target_focus_comparison.source_inputs',return_value=args),tempfile.TemporaryDirectory() as tmp:
            f=freeze(*args,'0.4'); validate(f)
            with patch('run_issue_scoped_comparison.read_key',side_effect=AssertionError('key read')):
                r=execute(f,Path(tmp)/'datasets/run',None,mock_scoped,simulate=True)
            s=score(f,r,audit(r))
            self.assertTrue(s['mechanicalContractGateMet']);self.assertIsNone(s['semanticQualityGateMet'])
            self.assertEqual(r['apiCalls'],0);self.assertEqual(r['simulatedCalls'],10)
            self.assertEqual(r['consumedReservationUsd'],'0')
            self.assertTrue(all(x['matchedDevelopmentDecision'] is None for x in s['results']))
            self.assertEqual(audit_input(r)['trialSchemaVersion'],'recorded-target-focus-1')

    def test_changed_execution_plan_blocks_before_key_callback_or_output(self):
        args=inputs()
        with patch('run_target_focus_comparison.source_inputs',return_value=args),tempfile.TemporaryDirectory() as tmp:
            f=freeze(*args,'0.4'); f['jobs'][0]['stages'][0]['body']['store']=True
            out=Path(tmp)/'datasets/run'
            with patch('run_issue_scoped_comparison.read_key',side_effect=AssertionError('key read')):
                with self.assertRaises(ValueError): execute(f,out,None,lambda *a,**kw:self.fail('called'),simulate=True)
            self.assertFalse(out.exists())

    def test_paid_claim_precedes_key_loading_and_cannot_be_reused(self):
        args=inputs()
        with patch('run_target_focus_comparison.source_inputs',return_value=args),tempfile.TemporaryDirectory() as tmp:
            f=freeze(*args,'0.4'); ledger=Path(tmp)/'datasets/ledger.sqlite3'
            seed(ledger,f['authorization'],[dict(artifactHash='prior',reservedCostUsd='.6',reportHash='fixture')])
            out=Path(tmp)/'datasets/run'
            with patch('run_issue_scoped_comparison.read_key',side_effect=RuntimeError('no test key')):
                with self.assertRaises(RuntimeError): execute(f,out,None,lambda *a,**kw:self.fail('called'),ledger_path=ledger)
            self.assertFalse(out.exists())
            self.assertEqual(Decimal(balance(ledger,f['authorization'])),Decimal('.4')-Decimal(f['reservedCostUsd']))
            with patch('run_issue_scoped_comparison.read_key',side_effect=AssertionError('key read')):
                with self.assertRaises(ValueError): execute(f,out,None,lambda *a,**kw:self.fail('called'),ledger_path=ledger)

    def test_scoring_refuses_changed_response_audit_or_accounting(self):
        args=inputs()
        with patch('run_target_focus_comparison.source_inputs',return_value=args),tempfile.TemporaryDirectory() as tmp:
            f=freeze(*args,'0.4'); r=execute(f,Path(tmp)/'datasets/run',None,mock_scoped,simulate=True)
            for mode in ('response','audit','cost','order','stage','labels','reservation'):
                changed=copy.deepcopy(r); a=audit(changed)
                if mode=='response': changed['calls'][0]['output']['verifications'][0]['assessment']['reason']='changed'
                if mode=='audit': a['results'].append(copy.deepcopy(a['results'][0]))
                if mode=='cost': changed['mockTokenRateEquivalentUsd']='0'
                if mode=='order': changed['calls'].reverse()
                if mode=='stage': changed['calls'][0]['stages'][0]['stage']='VERIFY'
                if mode=='labels': changed['cases'][0]['developmentExpectation']='PASS'
                if mode=='reservation': changed['reservedCostUsd']='0'
                if mode!='audit': a=audit(changed)
                with self.subTest(mode=mode),self.assertRaises(ValueError): score(f,changed,a)

    def test_old_closed_attempt_does_not_acquire_delta(self):
        ticket,_=initial('PASS'); validate_attempt(ticket)
        self.assertNotIn(DELTA,ticket['requestBody']['messages'][0]['content'])
