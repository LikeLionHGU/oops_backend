import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from decimal import Decimal

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from compare_recorded_candidates import audit_export
from compare_review_prompts import digest as audit_digest
from fixed_review_suite import digest
from run_issue_scoped_comparison import AUTHORIZATION, BOUND_FUNDING, fund_bound, execute, prepare, prepare_catalog, prepare_bound, validate, transport
from score_issue_scoped_comparison import score
from test_run_issue_scoped_comparison import source


def frozen():
    s = source(); initial = prepare(s, AUTHORIZATION)
    prior = dict(schemaVersion=initial['schemaVersion'], artifactHash=initial['artifactHash'],
                 authorizationHash=digest(AUTHORIZATION), status='COMPLETED_PENDING_JAVA_AUDIT',
                 reservedCostUsd=initial['reservedCostUsd'])
    return prepare_bound(s, AUTHORIZATION, prepare_catalog(s, AUTHORIZATION, prior))


def mock(key, body, timeout=90):
    p = json.loads(body['messages'][1]['content']); c = p['original']['candidates'][0]
    if 'targetSpans' in p:
        out = dict(candidateId=c['candidateId'], anchorId=c['anchorId'], issueSummary='fixture',
                   selections=[dict(segmentId=c['anchorId'], role='PRIMARY', spanId='FULL')])
    else:
        out = dict(candidateId=c['candidateId'], relevance=[dict(selectionIndex=i, relation='SAME_ISSUE', reason='fixture')
                   for i in range(len(p['selectedScope']['evidence']))], usedSelectionIndexes=[0],
                   assessment=dict(decision='UNCERTAIN', reason='fixture uncertainty', alternativeInterpretation=None,
                                   category=None, target=None, targetType=None, targetReason=None,
                                   score=None, missingInformation=['fixture only']))
    return dict(model='gpt-6-luna', usage=dict(prompt_tokens=100, completion_tokens=100),
                choices=[dict(finish_reason='stop', message=dict(content=json.dumps(out)))])


def fake_audit(report):
    exported = audit_export(json.loads(json.dumps(report)))
    exported.update(trialSchemaVersion='recorded-flow-regression-1', repetitionsPerCondition=3)
    return dict(sourceReportSha256=audit_digest(exported), results=[
        dict(case=c['case'], arm=c['arm'], repeat=c['repeat'], accepted='output' in c,
             decision='UNCERTAIN' if 'output' in c else None) for c in report['calls']])


class BoundRunnerTests(unittest.TestCase):
    def test_separate_funding_does_not_rollover_previous_budget_or_modify_bodies(self):
        original = frozen()
        funding = dict(BOUND_FUNDING, previousRemainingUsd=original['remainingBudgetUsd'])
        # Fixture bodies have different lengths/costs than private real inputs.
        with patch('issue_scoped_recipes.BOUND_FUNDING', funding):
            a = fund_bound(original, funding); validate(a)
            self.assertEqual(a['jobs'], original['jobs'])
            self.assertTrue(a['executionEnabled'])
            self.assertEqual(Decimal(a['remainingBudgetUsd']), Decimal('.5')-Decimal(a['reservedCostUsd']))
            self.assertEqual(a['priorBudgetRemainingUsd'], funding['previousRemainingUsd'])
            for field in ('additionalBudgetUsd', 'previousReservedUsd', 'userAuthorized'):
                bad = copy.deepcopy(funding); bad[field] = 'wrong'
                with self.subTest(field=field), self.assertRaises(ValueError): fund_bound(original, bad)
            with self.assertRaises(ValueError): fund_bound(a, funding)

    def test_budget_block_happens_before_callback_and_directory_creation(self):
        a = frozen(); validate(a)
        self.assertFalse(a['budgetSufficient']); self.assertFalse(a['executionEnabled'])
        self.assertGreater(float(a['reservedCostUsd']), float(a['remainingBudgetUsd']))
        self.assertEqual(set(a['priorCatalog']), {'schemaVersion','artifactHash','reservedCostUsd','priorReport'})
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)/'datasets'/'blocked'
            with self.assertRaises(ValueError): execute(a, out, 'unused', lambda *args, **kw: self.fail('called'))
            self.assertFalse(out.exists())

    def test_mock_cannot_use_default_transport_or_key(self):
        with tempfile.TemporaryDirectory() as tmp:
            for key, callback in ((None, transport), ('fake-key', mock)):
                with self.subTest(key=key), self.assertRaises(ValueError):
                    execute(frozen(), Path(tmp)/'datasets'/'blocked', key, callback, simulate=True)

    def test_mock_full_two_stage_receipts_and_audit_export_are_preserved(self):
        a = frozen()
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)/'datasets'/'run'; r = execute(a, out, None, mock, simulate=True)
            disk = json.loads((out/'report.json').read_text())
            self.assertEqual(r['simulatedCalls'], 54); self.assertEqual(r['apiCalls'], 0)
            self.assertEqual(r['consumedReservationUsd'], '0')
            self.assertEqual(r['status'], 'MOCK_COMPLETE_PENDING_JAVA_AUDIT')
            s = score(a, disk, fake_audit(disk))
            self.assertTrue(s['mockContractGateMet']); self.assertIsNone(s['developmentRegressionGateMet'])
            self.assertIsNone(s['semanticQualityGateMet']); self.assertFalse(s['runtimeUseAuthorized'])
            for unit in disk['calls']:
                self.assertNotIn('scopeHash', unit['stages'][1]['rawOutput'])
                self.assertTrue(unit['stages'][1]['boundReceipt']['responsesModified'] is False)

    def test_bad_judgment_is_preserved_not_repaired_or_changed_to_pass(self):
        def bad(key, body, timeout=90):
            r = mock(key, body, timeout)
            if 'grounding' in json.loads(body['messages'][1]['content']):
                out = json.loads(r['choices'][0]['message']['content']); out['relevance'] = []
                r['choices'][0]['message']['content'] = json.dumps(out)
            return r
        a = frozen()
        with tempfile.TemporaryDirectory() as tmp:
            r = execute(a, Path(tmp)/'datasets'/'run', None, bad, simulate=True)
        self.assertTrue(all(u['status']=='JUDGMENT_REJECTED' and 'output' not in u for u in r['calls']))
        self.assertEqual(r['simulatedCalls'], 54)
        self.assertTrue(all(u['stages'][1]['rawOutput']['relevance']==[] for u in r['calls']))
        s = score(a, r, fake_audit(r)); self.assertFalse(s['mockContractGateMet'])

    def test_invalid_selection_skips_judge_and_unknown_transport_stops(self):
        def bad(key, body, timeout=90):
            r = mock(key, body, timeout); out = json.loads(r['choices'][0]['message']['content'])
            out['selections'][0]['spanId'] = 'invented'
            r['choices'][0]['message']['content'] = json.dumps(out); return r
        with tempfile.TemporaryDirectory() as tmp:
            a = frozen(); r = execute(a, Path(tmp)/'datasets'/'bad', None, bad, simulate=True)
            self.assertEqual(r['simulatedCalls'], 27)
            self.assertTrue(all(u['status']=='SELECTION_REJECTED' for u in r['calls']))
            score(a, r, fake_audit(r))
            def timeout(*args, **kw): raise TimeoutError('fixture')
            r = execute(a, Path(tmp)/'datasets'/'timeout', None, timeout, simulate=True)
            self.assertEqual(r['status'], 'MOCK_PARTIAL'); self.assertEqual(r['apiCalls'], 0)
            self.assertGreaterEqual(r['unknownCostAttempts'], 1)

    def test_receipt_raw_output_and_attempt_swaps_are_rejected_even_with_rehashed_audit(self):
        a = frozen()
        with tempfile.TemporaryDirectory() as tmp:
            report = execute(a, Path(tmp)/'datasets'/'run', None, mock, simulate=True)
        for mode in ('receipt', 'raw', 'attempt'):
            r = copy.deepcopy(report); stage = r['calls'][0]['stages'][1]
            if mode=='receipt': stage['boundReceipt']['responsesModified'] = True
            if mode=='raw': stage['rawOutput']['assessment']['reason'] = 'changed'
            if mode=='attempt': stage['localAttempt'] = copy.deepcopy(r['calls'][1]['stages'][1]['localAttempt'])
            with self.subTest(mode=mode), self.assertRaises(ValueError): score(a, r, fake_audit(r))
