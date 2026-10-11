import copy
from decimal import Decimal
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import prepare_candidate_boundary_trial as tool
import compare_recorded_candidates as recorded
from fixed_review_suite import digest
from test_compare_recorded_candidates import fixture, response


def inputs():
    trace, first, pricing = fixture()
    for row in trace['data']['snapshot']['requests']:
        body = json.loads(row['providerRequestBody'])
        payload = json.loads(body['messages'][1]['content'])
        payload['candidates'][0]['proposedEvidence'] = [
            {'segmentId': payload['raw'][0]['id'], 'quote': payload['raw'][0]['text']}]
        row['input'] = json.dumps(payload)
        row['inputSha256'] = recorded.text_hash(row['input'])
        body['messages'][1]['content'] = row['input']
        row['providerRequestBody'] = json.dumps(body)
        row['providerRequestSha256'] = recorded.text_hash(row['providerRequestBody'])
    previous = recorded.prepare(trace, first, pricing)
    previous['comparisonKind'] = 'REFERENCE_PATTERN_ONLY_NOT_PROCEDURE_DELTA'
    reports = [first, {'status':'PARTIAL','reservedCostUsd':'.08'},
               {'status':'COMPLETED_PENDING_JAVA_AUDIT','reservedCostUsd':'.081'},
               {'status':'COMPLETED_PENDING_JAVA_AUDIT','reservedCostUsd':'.082'},
               {'status':'COMPLETED_CONTRACT_CHECK_ONLY','reservedCostUsd':'.083'}]
    previous['priorReservationLedger'] = [{'reportHash':digest(r),'reservedCostUsd':str(Decimal(r['reservedCostUsd']))}
                                          for r in reports[:3]]
    previous['artifactHash'] = digest({k:v for k,v in previous.items() if k != 'artifactHash'})
    reports[3]['artifactHash'] = previous['artifactHash']
    return previous,reports


class BoundaryTrialTests(unittest.TestCase):
    def test_small_trial_isolates_changes_and_excludes_expected_labels(self):
        prior,reports = inputs()
        original = copy.deepcopy(prior)
        artifact = tool.prepare(prior,reports)
        self.assertEqual(original,prior)
        self.assertEqual(12,len(artifact['requests']))
        self.assertEqual(5,len(artifact['cases']))
        self.assertLessEqual(Decimal(artifact['reservedTotalCostUsd']),Decimal(artifact['remainingBudgetUsd']))
        a,b = artifact['requests'][:2]
        self.assertEqual(a['body']['messages'][1],b['body']['messages'][1])
        self.assertEqual(a['body']['messages'][0]['content'] + tool.QUOTE_CONTRACT,
                         b['body']['messages'][0]['content'])
        for case in artifact['cases'][1:]:
            self.assertTrue(case['syntheticTimingOnly'])
            self.assertNotIn('expectedDecision',json.dumps(case['payload']))
            bodies = [r['body'] for r in artifact['requests'] if r['case'] == case['name']]
            self.assertEqual(bodies[0]['messages'][1],bodies[1]['messages'][1])

    def test_missing_duplicate_or_exhausted_ledger_rejected(self):
        for mode in ('missing','duplicate','exhausted','provenance'):
            prior,reports = inputs()
            if mode == 'missing': reports.pop()
            if mode == 'duplicate': reports[4] = reports[0]
            if mode == 'exhausted': reports[4]['reservedCostUsd'] = '.5'
            if mode == 'provenance': reports[3]['artifactHash'] = 'wrong'
            with self.assertRaises(ValueError): tool.prepare(prior,reports)

    def test_fake_execution_completes_and_preserves_normal_scope(self):
        artifact = tool.prepare(*inputs())
        with tempfile.TemporaryDirectory() as tmp:
            report = recorded.execute(artifact,Path(tmp)/'datasets'/'run','fake',response)
        self.assertEqual(12,len(report['calls']))
        self.assertTrue(report['normalControlsIncluded'])
        self.assertEqual('COMPLETED_PENDING_JAVA_AUDIT',report['status'])
        self.assertIsNone(report['qualityGateMet'])

    def test_rehashed_underreservation_rejected_before_provider(self):
        artifact = tool.prepare(*inputs())
        artifact['remainingBudgetUsd'] = '.5'
        artifact['artifactHash'] = digest({k:v for k,v in artifact.items() if k != 'artifactHash'})
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                recorded.execute(artifact,Path(tmp)/'datasets'/'run','fake',lambda *_:self.fail('called'))

    def test_rehashed_user_payload_change_rejected_before_provider(self):
        artifact = tool.prepare(*inputs())
        request = artifact['requests'][0]
        payload = json.loads(request['body']['messages'][1]['content'])
        payload['raw'][0]['text'] = 'different input'
        request['body']['messages'][1]['content'] = json.dumps(payload)
        request['requestHash'] = digest(request['body'])
        artifact['artifactHash'] = digest({k:v for k,v in artifact.items() if k != 'artifactHash'})
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                recorded.execute(artifact,Path(tmp)/'datasets'/'run','fake',lambda *_:self.fail('called'))
