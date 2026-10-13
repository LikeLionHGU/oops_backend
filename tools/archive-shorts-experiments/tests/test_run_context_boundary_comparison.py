import copy
from decimal import Decimal
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import urllib.error

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from context_boundary_packet import prepare as prepare_packet
from fixed_review_suite import digest
from compare_review_prompts import digest as audit_digest
from run_context_boundary_comparison import prepare, score, audit_input
from run_issue_scoped_comparison import execute
from test_context_boundary_packet import fixture
from test_scoped_input_study import mock_scoped


def frozen_fixture():
    records,spec,config=fixture()
    records += [copy.deepcopy(r) for r in records]
    spec['conditions'] += [copy.deepcopy(s) for s in spec['conditions']]
    for i,(r,s) in enumerate(zip(records,spec['conditions'])):
        r['id']=s['id']=f'condition-{i+1}'
    packet=prepare_packet(records,spec,config)
    prior=dict(artifactHash='fixture',reservedCostUsd='0.0315734',remainingBudgetUsd='0.4683626',
        allHistoryReservedUsd='4.4935198',authorization={},authorizationHash='fixture',additionalFundingHash='fixture')
    report=dict(artifactHash='fixture',runKind='LIVE',status='COMPLETED_PENDING_JAVA_AUDIT',
        apiCalls=4,unknownCostAttempts=0,consumedReservationUsd=prior['reservedCostUsd'])
    scored=dict(remainingBudgetUsd=prior['remainingBudgetUsd'])
    with patch('run_context_boundary_comparison.PACKET_HASH',digest(packet)), \
         patch('run_context_boundary_comparison.PRIOR_REPORT_HASH',digest(report)), \
         patch('run_context_boundary_comparison.PRIOR_SCORE_HASH',digest(scored)):
        return prepare(packet,records,spec,prior,report,scored)


def audit_fixture(report):
    return dict(sourceReportSha256=audit_digest(audit_input(report)),results=[
        dict(case=u['case'],arm=u['arm'],repeat=1,accepted='output' in u) for u in report['calls']])


class BoundaryExecutionTests(unittest.TestCase):
    def test_reserve_all_eight_without_refund_or_prior_budget_rollover(self):
        f=frozen_fixture()
        self.assertEqual(f['maxApiCalls'],8);self.assertEqual(f['repetitionsPerCondition'],1)
        total=sum(Decimal(j['stages'][0]['reservedCostUsd']) for j in f['jobs'])
        self.assertEqual(total,Decimal(f['reservedCostUsd']))
        self.assertEqual(Decimal(f['remainingBudgetUsd']),Decimal('0.4683626')-total)
        self.assertEqual(Decimal(f['allHistoryReservedUsd']),Decimal('4.4935198')+total)

    def test_mock_receipts_score_and_precise_trial_metadata(self):
        f=frozen_fixture()
        with patch('run_context_boundary_comparison.from_source',return_value=f),tempfile.TemporaryDirectory() as tmp:
            r=execute(f,Path(tmp)/'datasets/run',None,mock_scoped,simulate=True)
            s=score(f,r,audit_fixture(r))
            self.assertTrue(s['mechanicalContractGateMet']);self.assertEqual(r['simulatedCalls'],8)
            self.assertEqual(r['apiCalls'],0);self.assertEqual(r['repetitionsPerCondition'],1)
            self.assertEqual(s['reservedCostUsd'],'0');self.assertIsNone(s['semanticQualityGateMet'])
            self.assertFalse(s['automaticSelectionEvaluated']);self.assertFalse(s['runtimeUseAuthorized'])
            self.assertEqual(audit_input(r)['trialSchemaVersion'],'recorded-boundary-input-1')
            bad=copy.deepcopy(r);bad['calls'][0]['stages'][0]['body']['store']=True
            with self.assertRaises(ValueError):score(f,bad,audit_fixture(bad))
            bad=copy.deepcopy(r);bad['calls'][0]['output']['verifications'][0]['assessment']['reason']='rewritten'
            with self.assertRaises(ValueError):score(f,bad,audit_fixture(bad))

    def test_changed_plan_blocks_before_calls_and_directory(self):
        f=frozen_fixture();bad=copy.deepcopy(f);bad['executionEnabled']=True;bad['reservedCostUsd']='0'
        with patch('run_context_boundary_comparison.from_source',return_value=f),tempfile.TemporaryDirectory() as tmp:
            out=Path(tmp)/'datasets/run'
            with self.assertRaises(ValueError):execute(bad,out,None,lambda *a,**kw:self.fail('called'),simulate=True)
            self.assertFalse(out.exists())

    def test_http_failure_stops_once_and_unknown_cost_is_not_zero_success(self):
        called=[]
        def fail(key,body,timeout=90):
            called.append(body)
            raise urllib.error.HTTPError('https://unused.invalid',429,'hidden',{},None)
        f=frozen_fixture()
        with patch('run_context_boundary_comparison.from_source',return_value=f),tempfile.TemporaryDirectory() as tmp:
            r=execute(f,Path(tmp)/'datasets/run',None,fail,simulate=True)
            self.assertEqual(len(called),1);self.assertEqual(r['unknownCostAttempts'],1)
            with self.assertRaises(ValueError):score(f,r,audit_fixture(r))
            s=score(f,r,audit_fixture(r),allow_partial=True)
            self.assertFalse(s['complete']);self.assertFalse(s['mechanicalContractGateMet'])
            self.assertEqual(s['unknownCostAttempts'],1);self.assertIsNone(s['results'][0]['assessment'])
