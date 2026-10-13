"""Old runner live gates tested with synthetic keys/callbacks and temporary ledgers."""
from decimal import Decimal
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import run_boundary_comparison as window
import compare_recorded_candidates as recorded
from fixed_review_suite import read
from reservation_ledger import seed, balance, claim_legacy
from test_run_boundary_comparison import packet, config, response as window_response
from test_compare_recorded_candidates import fixture, response as recorded_response


def approval(artifact, name='fixture'):
    return dict(schemaVersion='legacy-execution-authorization-1', userAuthorized=True,
                additionalBudgetUsd='1.00', approvedArtifactHash=artifact['artifactHash'], approvalId=name)


def initialize(path, artifact, name='fixture'):
    auth=approval(artifact, name)
    seed(path, auth, [dict(artifactHash='fixture-history-'+name, reservedCostUsd='0', reportHash='fixture-history')])
    return auth


class LegacyGateTests(unittest.TestCase):
    def test_both_runners_require_separate_approval_before_key_callback_or_output(self):
        for runner, artifact in ((window, window.freeze(packet(), config())), (recorded, recorded.prepare(*fixture()))):
            with self.subTest(runner=runner.__name__), tempfile.TemporaryDirectory() as tmp:
                output=Path(tmp)/'datasets/run'
                with patch.object(runner, 'read_key', side_effect=AssertionError('key loaded')):
                    with self.assertRaises(ValueError):
                        runner.execute(artifact, output, None, lambda *a: self.fail('callback'), ledger_path=Path(tmp)/'datasets/ledger.sqlite3')
                self.assertFalse(output.exists())

    def test_uninitialized_approval_is_blocked_and_not_auto_seeded(self):
        artifact=window.freeze(packet(), config())
        with tempfile.TemporaryDirectory() as tmp:
            output=Path(tmp)/'datasets/run'; path=Path(tmp)/'datasets/ledger.sqlite3'
            with patch.object(window, 'read_key', side_effect=AssertionError('key loaded')):
                with self.assertRaises(ValueError):
                    window.execute(artifact, output, None, lambda *a:self.fail('callback'), authorization=approval(artifact), ledger_path=path)
            self.assertFalse(output.exists())

    def test_window_claims_before_loading_key_and_cannot_repeat_in_new_directory(self):
        artifact=window.freeze(packet(), config())
        total=sum(Decimal(r['reservedCostUsd']) for r in artifact['requests'])
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'datasets/ledger.sqlite3'; auth=initialize(path, artifact)
            def load_key(*a, **kw):
                self.assertEqual(Decimal(balance(path, auth)), Decimal('1')-total)
                return 'synthetic-test-key'
            with patch.object(window, 'read_key', side_effect=load_key) as loader:
                report=window.execute(artifact, Path(tmp)/'datasets/run', None, window_response, authorization=auth, ledger_path=path)
                self.assertEqual(report['apiCalls'], 4)
                self.assertEqual(report['consumedReservationUsd'], str(total))
                self.assertEqual(read(Path(tmp)/'datasets/run/reservation.json')['artifactHash'], artifact['artifactHash'])
                with self.assertRaises(ValueError):
                    window.execute(artifact, Path(tmp)/'datasets/second', None, lambda *a:self.fail('repeat'), authorization=auth, ledger_path=path)
                self.assertEqual(loader.call_count, 1)

    def test_failure_keeps_full_recorded_reservation_and_live_resume_is_blocked(self):
        artifact=recorded.prepare(*fixture())
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'datasets/ledger.sqlite3'; auth=initialize(path, artifact)
            def fail(*a): raise TimeoutError('SECRET_DO_NOT_SAVE')
            report=recorded.execute(artifact, Path(tmp)/'datasets/run', 'synthetic-key', fail, authorization=auth, ledger_path=path)
            self.assertEqual(report['unknownCostAttempts'], 1)
            self.assertEqual(Decimal(balance(path, auth)), Decimal('1')-Decimal(artifact['reservedTotalCostUsd']))
            self.assertNotIn('SECRET', str(report))
            with self.assertRaises(ValueError):
                recorded.execute(artifact, Path(tmp)/'datasets/resume', None, lambda *a:self.fail('resume'), report, authorization=auth, ledger_path=path)
            self.assertFalse((Path(tmp)/'datasets/resume').exists())

    def test_missing_key_after_claim_does_not_refund_or_allow_retry(self):
        artifact=window.freeze(packet(), config())
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'datasets/ledger.sqlite3'; auth=initialize(path, artifact)
            with patch.object(window, 'read_key', side_effect=ValueError('missing key')):
                with self.assertRaises(ValueError): window.execute(artifact, Path(tmp)/'datasets/run', None, window_response, authorization=auth, ledger_path=path)
            self.assertLess(Decimal(balance(path, auth)), Decimal('1'))
            self.assertFalse((Path(tmp)/'datasets/run').exists())
            with self.assertRaises(ValueError):
                window.execute(artifact, Path(tmp)/'datasets/retry', None, window_response, authorization=auth, ledger_path=path)

    def test_new_approval_does_not_make_consumed_artifact_reusable(self):
        artifact=window.freeze(packet(), config())
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'datasets/ledger.sqlite3'
            first=initialize(path, artifact, 'first'); second=initialize(path, artifact, 'second')
            claim_legacy(path, artifact, first, '.1', Path(tmp)/'datasets/first')
            with self.assertRaises(ValueError): claim_legacy(path, artifact, second, '.1', Path(tmp)/'datasets/second')

    def test_mock_requires_callback_no_key_and_never_touches_ledger(self):
        artifact=window.freeze(packet(), config())
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'datasets/ledger.sqlite3'
            for key, callback in ((None, window.transport), ('unexpected-key', window_response)):
                with self.assertRaises(ValueError):
                    window.execute(artifact, Path(tmp)/'datasets/bad', key, callback, simulate=True, ledger_path=path)
            report=window.execute(artifact, Path(tmp)/'datasets/mock', None, window_response, simulate=True, ledger_path=path)
            self.assertEqual(report['runKind'], 'MOCK_OFFLINE'); self.assertEqual(report['apiCalls'], 0)
            self.assertEqual(report['estimatedCostUsd'], '0'); self.assertEqual(report['consumedReservationUsd'], '0')
            self.assertFalse(path.exists())

    def test_recorded_reservation_total_cannot_be_rehashed_lower(self):
        from fixed_review_suite import digest
        artifact=recorded.prepare(*fixture()); artifact['reservedTotalCostUsd']='0.000001'
        artifact['artifactHash']=digest({k:v for k,v in artifact.items() if k!='artifactHash'})
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                recorded.execute(artifact, Path(tmp)/'datasets/mock', None, lambda *a:self.fail('callback'), simulate=True)
