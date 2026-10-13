import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import compare_recorded_candidates as tool


def fixture():
    requests = []
    for i in range(3):
        raw = [{'id': f'original-{i}', 'startMs': 0, 'endMs': 1000, 'text': 'fixture text'}]
        candidate = {'candidateId': f'candidate-{i+4}', 'anchorId': raw[0]['id'],
                     'axis': 'TARGET_TREATMENT', 'segmentIds': [raw[0]['id']],
                     'hypothesisNotEvidence': 'fixture hypothesis', 'contextExpanded': False,
                     'contextLimited': False,
                     'proposedEvidence': [{'segmentId': raw[0]['id'], 'quote': 'fixture text'}]}
        payload = json.dumps({'raw': raw, 'candidates': [candidate], 'promptRevision': 'fixture'})
        body = json.dumps({'model': 'gpt-6-luna', 'temperature': 0.1, 'reasoning_effort': 'none',
                           'response_format': {'type': 'json_object'}, 'messages': [
                               {'role': 'system', 'content': 'fixture policy\nJSON'},
                               {'role': 'user', 'content': payload}]})
        requests.append({'system': 'fixture policy', 'systemSha256': tool.text_hash('fixture policy'),
                         'input': payload, 'inputSha256': tool.text_hash(payload),
                         'providerRequestBody': body, 'providerRequestSha256': tool.text_hash(body)})
    trace = {'success': True, 'data': {'available': True, 'snapshot': {
        'truncated': False, 'droppedRequests': 0, 'requests': requests}}}
    previous = {'status': 'COMPLETED_CONTRACT_CHECK_ONLY', 'unknownCostAttempts': 0,
                'reservedCostUsd': '0.09846'}
    return trace, previous, {'inputUsdPerMillion': 0.1, 'outputUsdPerMillion': 0.5}


def response(key, body):
    payload = json.loads(body['messages'][1]['content'])
    c = payload['candidates'][0]
    assessment = {'segmentId': c['anchorId'], 'decision': 'PASS', 'reason': 'fixture reason',
                  'evidenceText': 'fixture text', 'evidence': [
                      {'segmentId': c['anchorId'], 'quote': 'fixture text', 'role': 'PRIMARY'}]}
    return {'model': 'gpt-6-luna', 'usage': {'prompt_tokens': 100, 'completion_tokens': 100},
            'choices': [{'finish_reason': 'stop', 'message': {'content': json.dumps({
                'verifications': [{'candidateId': c['candidateId'], 'assessment': assessment}]})}}]}


class RecordedComparisonTests(unittest.TestCase):
    def test_only_delta_differs_and_original_user_request_is_preserved(self):
        trace, previous, pricing = fixture()
        original = copy.deepcopy(trace)
        artifact = tool.prepare(trace, previous, pricing)
        self.assertEqual(trace, original)
        self.assertEqual(18, len(artifact['requests']))
        baseline, candidate = artifact['requests'][:2]
        body = copy.deepcopy(baseline['body'])
        body['messages'][0]['content'] += tool.DELTA
        self.assertEqual(body, candidate['body'])
        self.assertEqual(trace['data']['snapshot']['requests'][0]['input'], body['messages'][1]['content'])
        self.assertFalse(artifact['normalControlsIncluded'])

    def test_corrupt_or_partial_trace_rejected(self):
        for mutation in ('hash', 'truncated'):
            trace, previous, pricing = fixture()
            if mutation == 'hash': trace['data']['snapshot']['requests'][0]['inputSha256'] = 'wrong'
            else: trace['data']['snapshot']['truncated'] = True
            with self.assertRaises(ValueError): tool.prepare(trace, previous, pricing)

    def test_budget_exhaustion_rejected(self):
        trace, previous, pricing = fixture()
        previous['reservedCostUsd'] = '0.5'
        with self.assertRaises(ValueError): tool.prepare(trace, previous, pricing)

    def test_interrupted_reservation_is_not_refunded(self):
        prior = {'schemaVersion': 'recorded-candidate-results-1', 'status': 'PARTIAL',
                 'reservedCostUsd': '0.0836046', 'unknownCostAttempts': 1}
        artifact = tool.prepare(*fixture(), prior_attempt=prior)
        self.assertEqual('0.3179354', artifact['remainingBudgetUsd'])
        self.assertEqual(tool.digest(prior), artifact['priorInterruptedReportHash'])
        prior['reservedCostUsd'] = '0.45'
        with self.assertRaises(ValueError): tool.prepare(*fixture(), prior_attempt=prior)

    def test_execution_and_audit_mapping_preserve_quotes(self):
        artifact = tool.prepare(*fixture())
        with tempfile.TemporaryDirectory() as root:
            report = tool.execute(artifact, Path(root) / 'datasets' / 'run', None, response, simulate=True)
        self.assertEqual('COMPLETED_PENDING_JAVA_AUDIT', report['status'])
        self.assertEqual(18, len(report['calls']))
        original = copy.deepcopy(report)
        adapted = tool.audit_export(report)
        self.assertEqual(original, report)
        self.assertEqual('candidate-1', adapted['cases'][0]['payload']['candidates'][0]['candidateId'])
        self.assertEqual('stt-replay-0', adapted['calls'][0]['output']['verifications'][0]['assessment']['segmentId'])
        self.assertEqual('fixture text', adapted['calls'][0]['output']['verifications'][0]['assessment']['evidenceText'])

    def test_transport_failure_stops_without_retry(self):
        attempts = []
        def failing(key, body):
            attempts.append(body)
            raise TimeoutError()
        with tempfile.TemporaryDirectory() as root:
            report = tool.execute(tool.prepare(*fixture()), Path(root) / 'datasets' / 'run', None, failing, simulate=True)
        self.assertEqual(1, len(attempts))
        self.assertEqual(1, report['unknownCostAttempts'])
        self.assertEqual('PARTIAL', report['status'])

    def test_invalid_evidence_is_retained_without_repeating_request(self):
        def invalid(key, body):
            result = response(key, body)
            output = json.loads(result['choices'][0]['message']['content'])
            output['verifications'][0]['assessment']['evidence'][0]['quote'] = 'not in raw'
            result['choices'][0]['message']['content'] = json.dumps(output)
            return result
        with tempfile.TemporaryDirectory() as root:
            report = tool.execute(tool.prepare(*fixture()), Path(root) / 'datasets' / 'run', None, invalid, simulate=True)
        self.assertEqual(18, len(report['calls']))
        self.assertTrue(all(c['status'] == 'PYTHON_EVIDENCE_REJECTED' for c in report['calls']))
        self.assertEqual(0, report['unknownCostAttempts'])

    def test_resume_only_calls_unfinished_suffix(self):
        artifact = tool.prepare(*fixture())
        with tempfile.TemporaryDirectory() as root:
            report = tool.execute(artifact, Path(root) / 'datasets' / 'run', None, response, simulate=True)
            report['calls'] = report['calls'][:10]
            report['status'] = 'PARTIAL'
            attempts = []
            def counted(key, body):
                attempts.append(body)
                return response(key, body)
            resumed = tool.execute(artifact, Path(root) / 'datasets' / 'resume', None, counted, report, simulate=True)
        self.assertEqual(8, len(attempts))
        self.assertEqual(18, len(resumed['calls']))
        self.assertEqual(tool.digest(report), resumed['resumedReportHash'])

    def test_unexpected_model_stops_but_preserves_known_usage(self):
        def wrong_model(key, body):
            result = response(key, body)
            result['model'] = 'unexpected-model'
            return result
        with tempfile.TemporaryDirectory() as root:
            report = tool.execute(tool.prepare(*fixture()), Path(root) / 'datasets' / 'run', None, wrong_model, simulate=True)
        self.assertEqual(1, len(report['calls']))
        self.assertEqual(0, report['unknownCostAttempts'])
        self.assertEqual('FAILED_STOP', report['calls'][0]['status'])
