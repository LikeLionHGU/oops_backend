import copy
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prepare_target_focus_comparison import DELTA, build_preview, focused_base, prepare
from test_context_boundary_packet import fixture


def source():
    records, _, _ = fixture()
    ticket = records[0]['attempt']
    frozen = dict(source=dict(cases=[dict(name='sample', developmentExpectation='REVIEW_REQUIRED')]),
                  jobs=[dict(case='sample', arm='BASELINE'), dict(case='sample', arm='CANDIDATE')])
    report = dict(calls=[dict(case='sample', arm=arm, stages=[dict(localAttempt=copy.deepcopy(ticket))])
                         for arm in ('BASELINE', 'CANDIDATE')])
    scored = dict(complete=True, mechanicalContractGateMet=True, runKind='LIVE', results=[
        dict(case='sample', arm=arm, assessment=dict(decision='PASS', reason='original reason'))
        for arm in ('BASELINE', 'CANDIDATE')])
    return frozen, report, scored


class TargetFocusPreviewTests(unittest.TestCase):
    def test_isolated_block_preserves_source_and_deduplicates_inputs(self):
        inputs = source(); before = copy.deepcopy(inputs)
        result = build_preview(*inputs)
        self.assertEqual(inputs, before)
        self.assertEqual(result['plannedApiCalls'], 2)
        self.assertEqual(result['historicalObservations'], 2)
        group = result['comparisons'][0]
        candidate = copy.deepcopy(group['candidateRequest'])
        candidate['messages'][0]['content'] = candidate['messages'][0]['content'].replace(DELTA, '', 1)
        self.assertEqual(candidate, group['baselineRequest'])
        self.assertFalse(result['executionEnabled']); self.assertEqual(result['reservedCostUsd'], '0')
        self.assertIsNone(result['semanticQualityGateMet'])
        self.assertIn('NORMAL_CONTROL_MISSING', result['blockingReasons'])
        self.assertEqual(group['observations'][0]['originalAssessment']['reason'], 'original reason')

    def test_partial_mock_or_rejected_source_cannot_be_evidence(self):
        for field, value in [('complete', False), ('mechanicalContractGateMet', False), ('runKind', 'MOCK_OFFLINE')]:
            f, r, s = source(); s[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError): build_preview(f, r, s)

    def test_missing_or_invented_expectation_is_not_default_pass(self):
        for value in (None, 'UNKNOWN'):
            f, r, s = source(); f['source']['cases'][0]['developmentExpectation'] = value
            with self.assertRaises(ValueError): build_preview(f, r, s)

    def test_identical_input_conflicting_labels_is_refused(self):
        f, r, s = source()
        f['source']['cases'].append(dict(name='normal', developmentExpectation='PASS'))
        f['jobs'][1]['case'] = r['calls'][1]['case'] = s['results'][1]['case'] = 'normal'
        with self.assertRaisesRegex(ValueError, 'conflicting'): build_preview(f, r, s)

    def test_duplicate_or_missing_reference_boundary_is_refused(self):
        base = source()[1]['calls'][0]['stages'][0]['localAttempt']['baseRequest']
        for text in ('missing', '# 범용 맥락 참고 기준\n# 범용 맥락 참고 기준'):
            bad = copy.deepcopy(base); bad['messages'][0]['content'] = text
            with self.assertRaises(ValueError): focused_base(bad)
        with self.assertRaises(ValueError): focused_base(focused_base(base))

    def test_full_source_verifier_runs_before_preview(self):
        f, r, s = source()
        with patch('prepare_target_focus_comparison.score', return_value=s) as verifier:
            prepare(f, r, {'audit': 'fixture'})
            verifier.assert_called_once_with(f, r, {'audit': 'fixture'})
        with patch('prepare_target_focus_comparison.score', side_effect=ValueError('changed source')):
            with self.assertRaisesRegex(ValueError, 'changed source'): prepare(f, r, {})

    def test_only_system_delta_no_label_or_assessment_in_request(self):
        f, r, s = source(); draft = build_preview(f, r, s)
        for group in draft['comparisons']:
            candidate = group['candidateRequest']
            self.assertEqual(candidate['messages'][1], group['baselineRequest']['messages'][1])
            self.assertNotIn('original reason', str(candidate))
            self.assertNotIn('developmentExpectedDecision', str(candidate))
