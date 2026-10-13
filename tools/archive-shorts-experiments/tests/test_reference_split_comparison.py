import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import run_reference_split_comparison as tool
from test_split_context_reference_draft import fixture as archive_fixture
from test_context_reselection import initial
from test_scoped_input_study import mock_scoped
from test_run_target_focus_comparison import audit
from run_issue_scoped_comparison import execute


def inputs():
    base = archive_fixture()
    rule = base['guidelines'][0]
    rule.update(axis='TARGET_TREATMENT', channels=['SPEECH'], requiredEvidence='fixture evidence',
                missingContext='fixture missing')
    base['examples'][1]['context']['flow'] = ['different preserved text']
    candidate, _ = tool.split(base)
    ticket, _ = initial('PASS')
    body = copy.deepcopy(ticket['baseRequest'])
    prefix, _ = body['messages'][0]['content'].split('# 범용 맥락 참고 기준', 1)
    body['messages'][0]['content'] = prefix + '# 범용 맥락 참고 기준\ncontextReference=' + json.dumps(
        dict(reviewGuidelines=tool.prompt_rules(base, 'SPEECH'), referenceContexts=[
            dict(mechanismIds=e['mechanismIds'], context=e['context']) for e in base['examples']]),
        ensure_ascii=False)
    auth = dict(userAuthorized=True, additionalBudgetUsd='1.00', schemaVersion='fixture-only')
    cases, jobs = [], []
    for n in tool.NAMES:
        cases.append(dict(name=n, payload=copy.deepcopy(ticket['originalInput']),
                          developmentExpectation='PASS' if n in ('F4', 'F5') else 'REVIEW_REQUIRED'))
        jobs.append(dict(case=n, arm='BASELINE', repeat=1, baseBody=copy.deepcopy(body),
                         fixedSelectedScope=copy.deepcopy(ticket['selectedScope'])))
    source = dict(source=dict(cases=cases), jobs=jobs, remainingBudgetUsd='.4', authorization=auth,
                  authorizationHash=tool.digest(auth), additionalFundingHash='fixture',
                  config=dict(maxCompletionTokens=8000, pricing=dict(inputUsdPerMillion='.1', outputUsdPerMillion='.5')))
    return source, base, candidate


class ReferenceSplitTests(unittest.TestCase):
    def test_only_reference_rules_and_c_ownership_change(self):
        args = inputs(); before = copy.deepcopy(args)
        frozen = tool.freeze(*args, '.1')
        self.assertEqual(args, before)
        self.assertEqual(frozen['plannedUnits'], 10)
        for n in tool.NAMES:
            pair = [j for j in frozen['jobs'] if j['case'] == n]
            self.assertEqual(pair[0]['fixedSelectedScope'], pair[1]['fixedSelectedScope'])
            self.assertEqual(pair[0]['stages'][0]['body']['messages'][1:], pair[1]['stages'][0]['body']['messages'][1:])
            refs = []
            for j in pair:
                prefix, tail = j['stages'][0]['body']['messages'][0]['content'].split('contextReference=', 1)
                ref, end = json.JSONDecoder().raw_decode(tail)
                refs.append((prefix, tail[end:], [e['context'] for e in ref['referenceContexts']]))
            self.assertEqual(refs[0], refs[1])
        self.assertFalse(frozen['newCasesIncluded'])

    def test_unknown_context_new_cases_and_insufficient_budget_refused(self):
        for mode in ('context', 'new-case', 'budget', 'increase'):
            source, base, candidate = inputs(); available = '.1'
            if mode == 'context': base['examples'][0]['context']['flow'] = ['unmatched']
            if mode == 'new-case': candidate['examples'].append(dict(id='extra'))
            if mode == 'budget': available = '.001'
            if mode == 'increase': available = '.5'
            with self.subTest(mode=mode), self.assertRaises(ValueError):
                tool.freeze(source, base, candidate, available)

    def test_mock_run_checks_ten_contracts_without_paid_calls(self):
        args = inputs()
        with patch('run_reference_split_comparison.source_inputs', return_value=args), tempfile.TemporaryDirectory() as tmp:
            frozen = tool.freeze(*args, '.1')
            report = execute(frozen, Path(tmp)/'datasets/run', None, mock_scoped, simulate=True)
            result = tool.score(frozen, report, audit(report))
            self.assertTrue(result['mechanicalContractGateMet'])
            self.assertEqual(report['simulatedCalls'], 10)
            self.assertEqual(report['apiCalls'], 0)
            self.assertIsNone(result['semanticQualityGateMet'])

    def test_changed_frozen_blocks_before_key_and_execution(self):
        args = inputs()
        with patch('run_reference_split_comparison.source_inputs', return_value=args), tempfile.TemporaryDirectory() as tmp:
            frozen = tool.freeze(*args, '.1'); frozen['jobs'][0]['judgmentVariant'] = 'TARGET_FOCUS_CLOSED_SCOPE'
            with patch('run_issue_scoped_comparison.read_key', side_effect=AssertionError('key loaded')):
                with self.assertRaises(ValueError):
                    execute(frozen, Path(tmp)/'datasets/run', None, mock_scoped, simulate=True)

    def test_report_identity_and_missing_java_rows_refused(self):
        args = inputs()
        with patch('run_reference_split_comparison.source_inputs', return_value=args), tempfile.TemporaryDirectory() as tmp:
            frozen = tool.freeze(*args, '.1')
            report = execute(frozen, Path(tmp)/'datasets/run', None, mock_scoped, simulate=True)
            for mode in ('identity', 'hash', 'missing-row'):
                changed = copy.deepcopy(report)
                if mode == 'identity': changed['referenceSplitStudy'] = 'different'
                if mode == 'hash': changed['previewHash'] = 'different'
                checked = audit(changed)
                if mode == 'missing-row': checked['results'].pop()
                with self.subTest(mode=mode), self.assertRaises(ValueError):
                    tool.score(frozen, changed, checked)
