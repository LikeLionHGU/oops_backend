import copy
import json
from decimal import Decimal
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from bound_review_contract import prepare_attempt, receive
from context_reselection import eligible, selection_request
from fixed_review_suite import digest
from prepare_context_reselection import prepare, score
from run_issue_scoped_comparison import execute
from test_bound_review_contract import setup, response
from test_scoped_input_study import mock_scoped
from test_bound_comparison_runner import fake_audit


def initial(decision='UNCERTAIN'):
    p, _, scope, base, out = setup()
    out['assessment'].update(decision=decision, category=None, target=None, targetType=None,
        targetReason=None, score=None, missingInformation=['지칭과 정상 설명 연결이 필요함']
        if decision == 'UNCERTAIN' else [])
    out['usedSelectionIndexes'] = [0, 2]
    ticket = prepare_attempt(base, scope, 'initial-fixture', judgment_variant='CLOSED_SCOPE')
    return ticket, receive(ticket, ticket['requestBody'], response(out))


def frozen_fixture():
    ticket, receipt = initial()
    passed, pass_receipt = initial('PASS')
    unit = dict(case='B', repeat=1, arm='CANDIDATE', stages=[dict(localAttempt=ticket, boundReceipt=receipt)])
    skip = dict(case='N1', repeat=1, arm='CANDIDATE', stages=[dict(localAttempt=passed, boundReceipt=pass_receipt)])
    source = dict(artifactHash='fixture', remainingBudgetUsd='0.4999360', reservedCostUsd='0.3450801',
        source=dict(cases=[dict(name='B', payload=ticket['originalInput'])]),
        config=dict(maxCompletionTokens=8000, pricing=dict(inputUsdPerMillion='0.1', outputUsdPerMillion='0.4')),
        authorization={}, authorizationHash='fixture', additionalFundingHash='fixture')
    report = dict(artifactHash='fixture', runKind='LIVE', status='COMPLETED_PENDING_JAVA_AUDIT',
        unknownCostAttempts=0, consumedReservationUsd='0.3450801', calls=[unit,skip])
    with patch('compare_scoped_inputs.validate'), patch('prepare_context_reselection.SOURCE_FROZEN_HASH','fixture'), \
         patch('prepare_context_reselection.SOURCE_REPORT_HASH', digest(report)):
        return prepare(source,report)


def mock_reselection(key, body, timeout=90):
    incoming = json.loads(body['messages'][1]['content'])
    if 'previousEvidence' not in incoming:
        return mock_scoped(key,body,timeout)
    c = incoming['original']['candidates'][0]
    output = dict(candidateId=c['candidateId'], anchorId=c['anchorId'], issueSummary='fixture only', selections=[
        dict(segmentId=c['anchorId'],role='PRIMARY',spanId='FULL'),
        dict(segmentId='b',role='CONTEXT',spanId='FULL')])
    result = response(output)
    result['usage'] = dict(prompt_tokens=100,completion_tokens=100)
    return result


class ContextReselectionTests(unittest.TestCase):
    def test_only_uncertain_once_no_pass_trigger_or_second_loop(self):
        t,r = initial(); self.assertTrue(eligible(t,r)); self.assertFalse(eligible(t,r,reselections_used=1))
        with self.assertRaises(ValueError): selection_request(t,r,reselections_used=1)
        t,r = initial('PASS'); self.assertFalse(eligible(t,r))
        with self.assertRaises(ValueError): selection_request(t,r)
        with self.assertRaises(ValueError): eligible(t,r,reselections_used=True)
        p,_,s,b,o = setup(); t=prepare_attempt(b,s,'review',judgment_variant='CLOSED_SCOPE')
        self.assertFalse(eligible(t,receive(t,t['requestBody'],response(o))))

    def test_reselection_keeps_allowed_original_and_neutral_context_without_old_verdict(self):
        t,r=initial(); before=copy.deepcopy((t,r)); body=selection_request(t,r)
        p=json.loads(body['messages'][1]['content'])
        self.assertEqual(p['original']['raw'],t['originalInput']['raw'])
        self.assertEqual(p['missingInformation'],r['modelOutput']['assessment']['missingInformation'])
        self.assertEqual(p['previousEvidence'],t['selectedScope']['evidence'])
        self.assertNotIn('hypothesisNotEvidence',p['original']['candidates'][0])
        self.assertNotIn('proposedEvidence',p['original']['candidates'][0])
        self.assertNotIn('assessment',p)
        for k in ('model','reasoning_effort','store','max_completion_tokens','response_format'):
            self.assertEqual(body[k],t['baseRequest'][k])
        self.assertIn('긍정 평가',body['messages'][0]['content'])
        self.assertEqual((t,r),before)

    def test_changed_receipt_and_oversized_missing_request_rejected(self):
        t,r=initial(); r['modelOutput']['assessment']['missingInformation']=['forged']
        with self.assertRaises(ValueError): selection_request(t,r)
        t,r=initial(); out=copy.deepcopy(r['modelOutput']); out['assessment']['missingInformation']=['x'*401]
        with self.assertRaises(ValueError): receive(t,t['requestBody'],response(out))

    def test_plan_reserves_both_stages_and_records_pass_skipped(self):
        f=frozen_fixture()
        total=sum(Decimal(s['reservedCostUsd']) for j in f['jobs'] for s in j['stages'])
        self.assertEqual(total,Decimal(f['reservedCostUsd']))
        self.assertEqual(Decimal(f['remainingBudgetUsd']),Decimal('0.4999360')-total)
        self.assertEqual([s['stage'] for s in f['jobs'][0]['stages']],['SELECT','JUDGE'])
        self.assertEqual(f['skipped'][0]['decision'],'PASS')
        self.assertFalse(f['normalControlsIncluded']); self.assertEqual(f['maxApiCalls'],2)

    def test_mock_transition_preserves_old_new_and_closed_judgment(self):
        f=frozen_fixture()
        with patch('prepare_context_reselection.from_source',return_value=f), tempfile.TemporaryDirectory() as tmp:
            report=execute(f,Path(tmp)/'datasets/run',None,mock_reselection,simulate=True)
            scored=score(f,report,fake_audit(report))
            self.assertTrue(scored['mechanicalContractGateMet'])
            self.assertIsNone(scored['knownUsageRateEquivalentUsd'])
            self.assertEqual(scored['remainingBudgetUsd'],'0.4999360')
            bad=copy.deepcopy(report);bad['calls'][0]['contextTransition']['previousScopeHash']='changed'
            with self.assertRaises(ValueError): score(f,bad,fake_audit(bad))
            bad=copy.deepcopy(report);bad['calls'][0]['stages'][1]['usage']['completion_tokens']=1
            with self.assertRaises(ValueError): score(f,bad,fake_audit(bad))
        u=report['calls'][0]; self.assertEqual(u['status'],'CONTRACT_ONLY')
        self.assertEqual(u['contextTransition']['reselectionsUsed'],1)
        self.assertEqual(u['contextTransition']['previousEvidence'],f['jobs'][0]['previousAttempt']['selectedScope']['evidence'])
        self.assertFalse(report['freshInitialJudgments']); self.assertFalse(report['normalControlsIncluded'])
        self.assertEqual(report['apiCalls'],0); self.assertIsNone(report['qualityGateMet'])
        self.assertNotIn('original',json.loads(u['stages'][1]['body']['messages'][1]['content']))
        self.assertEqual(u['stages'][1]['boundReceipt']['modelOutput']['assessment']['decision'],'UNCERTAIN')

    def test_invalid_selection_stops_before_judge_no_repair(self):
        def invalid(key,body,timeout=90):
            r=mock_reselection(key,body,timeout)
            output=json.loads(r['choices'][0]['message']['content'])
            output['anchorId']='invented'
            r['choices'][0]['message']['content']=json.dumps(output)
            return r
        f=frozen_fixture()
        with patch('prepare_context_reselection.from_source',return_value=f), tempfile.TemporaryDirectory() as tmp:
            r=execute(f,Path(tmp)/'datasets/run',None,invalid,simulate=True)
        self.assertEqual(r['calls'][0]['status'],'SELECTION_REJECTED')
        self.assertEqual(len(r['calls'][0]['stages']),1)

    def test_modified_plan_rejected_before_callback(self):
        f=frozen_fixture(); bad=copy.deepcopy(f); bad['jobs'][0]['judgmentVariant']='RELATION_CHECKLIST'
        with patch('prepare_context_reselection.from_source',return_value=f), tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                execute(bad,Path(tmp)/'datasets/run',None,lambda *a,**kw:self.fail('callback'),simulate=True)
            self.assertFalse((Path(tmp)/'datasets/run').exists())
