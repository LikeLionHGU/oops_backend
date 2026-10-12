import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import run_boundary_comparison as tool
from fixed_review_suite import review_packet, digest
from review_boundary_pairs import comparison_draft
from test_fixed_review_suite import fixture
from test_review_boundary_pairs import boundary


def packet():
    suite = fixture()
    result = review_packet(suite, ['pisik-D', 'pisik-yeongyang-normal_control-3'])
    result['boundary-pairs.json'] = boundary(suite)
    result['comparison-draft.json'] = comparison_draft(result, result['boundary-pairs.json'], 1)
    return result


def config():
    value = tool.configuration_template()
    value.update({'maxApiCalls': 4, 'maxEstimatedCostUsd': '1', 'baselinePrompt': 'fixture policy A',
                  'candidatePrompt': 'fixture policy B', 'referenceSnapshot': []})
    value['pricing'] = {'inputUsdPerMillion': '1', 'outputUsdPerMillion': '2',
                        'sourceUrl': 'https://example.test/fixture-pricing', 'verifiedOn': 'fixture-not-real-prices'}
    return value


def response(key, body):
    return {'model': 'fixture', 'usage': {'prompt_tokens': 100, 'completion_tokens': 30},
            'choices': [{'finish_reason': 'stop', 'message': {'content': json.dumps({
                'decision': 'PASS', 'reason': 'fixture only', 'missingInformation': [], 'issues': []})}}]}


class ExecutionTests(unittest.TestCase):
    def test_incomplete_setup_is_blocked(self):
        with self.assertRaises(ValueError): tool.freeze(packet(), tool.configuration_template())
        for field, value in [('maxApiCalls', None), ('maxEstimatedCostUsd', 'NaN'),
                             ('referenceSnapshot', None), ('baselinePrompt', None),
                             ('maxCompletionTokens', True)]:
            c = config()
            c[field] = value
            with self.assertRaises(ValueError): tool.freeze(packet(), c)

    def test_request_contract_preserves_synthetic_times_and_excludes_labels(self):
        artifact = tool.freeze(packet(), config())
        self.assertEqual(4, len(artifact['requests']))
        for item in artifact['requests']:
            payload = json.loads(item['body']['messages'][1]['content'])
            self.assertEqual([], payload['contextReference'])
            self.assertNotIn('caseId', payload)
            self.assertNotIn('developmentExpectedDecision', str(payload))
            if payload['inputMode'] == 'SYNTHETIC_TEXT':
                self.assertIsNone(payload['raw'][0]['startMs'])
            self.assertFalse(item['body']['store'])
            self.assertTrue(item['body']['response_format']['json_schema']['strict'])

    def test_literal_quote_checks_not_semantic_judgment(self):
        value = {'decision': 'REVIEW_REQUIRED', 'reason': 'fixture', 'missingInformation': [],
                 'issues': [{'axis': 'EXPRESSION_CONTENT', 'reason': 'fixture', 'normalInterpretation': 'fixture',
                             'evidence': [{'segmentId': 's', 'quote': 'original'}]}]}
        self.assertTrue(tool.validate_output(value, [{'id': 's', 'text': 'original text'}]))
        value['issues'][0]['evidence'][0]['quote'] = 'invented'
        with self.assertRaises(ValueError): tool.validate_output(value, [{'id': 's', 'text': 'original text'}])
        value['decision'] = 'PASS'
        with self.assertRaises(ValueError): tool.validate_output(value, [{'id': 's', 'text': 'original text'}])
        value.update({'decision': 'UNCERTAIN', 'issues': []})
        with self.assertRaises(ValueError): tool.validate_output(value, [])

    def test_fake_run_completes_without_accuracy_promotion(self):
        calls = []
        def fake(key, body):
            calls.append(body)
            return response(key, body)
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / 'datasets' / 'run'
            report = tool.execute(tool.freeze(packet(), config()), output, None, fake, simulate=True)
            self.assertEqual(4, len(calls))
            self.assertEqual('COMPLETED_CONTRACT_CHECK_ONLY', report['status'])
            self.assertIsNone(report['qualityGateMet'])
            self.assertIsNone(report['actualBilledCostUsd'])
            self.assertTrue(all(c['targetIssueFound'] is None for c in report['calls']))
            self.assertEqual(0o600, (output / 'report.json').stat().st_mode & 0o777)
            with self.assertRaises(FileExistsError): tool.execute(tool.freeze(packet(), config()), output, None, fake, simulate=True)
            self.assertEqual(4, len(calls))

    def test_call_and_reserved_money_caps(self):
        for cap, budget, expected in [(1, '1', 1), (4, '0.0000001', 0)]:
            c = config()
            c.update({'maxApiCalls': cap, 'maxEstimatedCostUsd': budget})
            calls = []
            def fake(key, body):
                calls.append(1)
                return response(key, body)
            with tempfile.TemporaryDirectory() as tmp:
                report = tool.execute(tool.freeze(packet(), c), Path(tmp) / 'datasets' / 'run', None, fake, simulate=True)
                self.assertEqual(expected, len(calls))
                self.assertEqual('BUDGET_LIMIT', report['stopReason'])

    def test_failure_is_reserved_and_never_retried_or_logged_with_secret(self):
        calls = []
        def fake(key, body):
            calls.append(1)
            raise RuntimeError('SECRET_DO_NOT_LOG')
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / 'datasets' / 'run'
            report = tool.execute(tool.freeze(packet(), config()), output, None, fake, simulate=True)
            self.assertEqual(1, len(calls))
            self.assertEqual(1, report['unknownCostAttempts'])
            self.assertFalse(report['estimateCompleteForAttemptedCalls'])
            self.assertNotIn('SECRET', json.dumps(report))
            self.assertTrue((output / 'attempt-001.json').exists())

    def test_refusal_incomplete_usage_and_bad_json_stop(self):
        for kind in ('refusal', 'incomplete', 'usage', 'json'):
            def fake(key, body):
                value = response(key, body)
                if kind == 'refusal': value['choices'][0]['message']['refusal'] = 'refused'
                if kind == 'incomplete': value['choices'][0]['finish_reason'] = 'length'
                if kind == 'usage': value['usage'] = {}
                if kind == 'json': value['choices'][0]['message']['content'] = '{'
                return value
            with tempfile.TemporaryDirectory() as tmp:
                report = tool.execute(tool.freeze(packet(), config()), Path(tmp) / 'datasets' / 'run', None, fake, simulate=True)
                self.assertEqual(1, report['apiAttempts'])
                self.assertEqual('FAILED_STOP', report['calls'][0]['status'])

    def test_tampered_artifact_rejected_before_request(self):
        artifact = tool.freeze(packet(), config())
        artifact['requests'][0]['body']['model'] = 'changed'
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / 'datasets' / 'run'
            with self.assertRaises(ValueError): tool.execute(artifact, output, None, lambda *_: self.fail('called'), simulate=True)
            self.assertFalse(output.exists())

    def test_packet_hash_verification(self):
        p = packet()
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp) / 'datasets'
            for name, value in p.items(): tool.write_private(directory / name, value)
            self.assertEqual(p['manifest.json'], tool.load_packet(directory)['manifest.json'])
            p['comparison-draft.json']['jobs'][0]['arm'] = 'WRONG'
            (directory / 'comparison-draft.json').unlink()
            tool.write_private(directory / 'comparison-draft.json', p['comparison-draft.json'])
            with self.assertRaises(ValueError): tool.load_packet(directory)

    def test_rehashed_underreservation_is_rejected_before_any_call(self):
        artifact = tool.freeze(packet(), config())
        artifact['requests'][0]['reservedCostUsd'] = '0.0000001'
        artifact['artifactHash'] = digest({k: v for k, v in artifact.items() if k != 'artifactHash'})
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                tool.execute(artifact, Path(tmp) / 'datasets' / 'run', None, lambda *_: self.fail('called'), simulate=True)

    def test_reservation_overrun_stops_next_call(self):
        def fake(key, body):
            value = response(key, body)
            value['usage']['completion_tokens'] = 999999
            return value
        with tempfile.TemporaryDirectory() as tmp:
            report = tool.execute(tool.freeze(packet(), config()), Path(tmp) / 'datasets' / 'run', None, fake, simulate=True)
            self.assertEqual(1, report['apiAttempts'])
            self.assertEqual('FAILED_STOP', report['calls'][0]['status'])
            self.assertIsNotNone(report['calls'][0]['estimatedCostUsd'])
