import copy
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
from decimal import Decimal
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import prepare_pattern_refinement as tool
import run_boundary_comparison as window
from fixed_review_suite import digest
from test_run_boundary_comparison import packet, config
from test_run_boundary_comparison import response
from test_compare_recorded_candidates import fixture as recorded_fixture
import compare_recorded_candidates as recorded


def fixture():
    fields = {f: 'old ' + f for f in tool.FIELDS}
    base = {'guidelines': [{'id': 'pattern', 'axis': 'TARGET_TREATMENT', **fields}],
            'sourceCases': [{'caseId': 'existing'}]}
    plan = {'schemaVersion': 'context-pattern-refinement-1', 'status': 'UNREVIEWED_DRAFT',
            'runtimeUseAuthorized': False, 'trainingUseAuthorized': False, 'humanApproved': False,
            'baseArchiveHash': digest(base), 'refinements': [{'mechanismId': 'pattern',
            'supportingCaseIds': ['existing'], 'fields': {f: 'new ' + f for f in tool.FIELDS}}]}
    return base, plan


class RefinementTests(unittest.TestCase):
    def test_full_preparation_reserves_all_previous_attempts_and_preserves_recorded_user(self):
        base, plan = fixture()
        plan['hypothesis'] = 'fixture only'
        trace, first, pricing = recorded_fixture()
        ref = {'reviewGuidelines': base['guidelines'], 'referenceContexts': ['unchanged']}
        for row in trace['data']['snapshot']['requests']:
            row['system'] = 'policy\ncontextReference=' + json.dumps(ref) + '\nCONTRACT'
            row['systemSha256'] = recorded.text_hash(row['system'])
            body = json.loads(row['providerRequestBody'])
            body['messages'][0]['content'] = row['system']
            row['providerRequestBody'] = json.dumps(body)
            row['providerRequestSha256'] = recorded.text_hash(row['providerRequestBody'])
        previous_window = {'configuration': {**config(), 'pricing': pricing,
                                            'referenceSnapshot': {'guidelines': base['guidelines']}}}
        prior = [first, {'schemaVersion': 'recorded-candidate-results-1', 'status': 'PARTIAL',
                        'reservedCostUsd': '.08', 'unknownCostAttempts': 1},
                 {'status': 'COMPLETED_PENDING_JAVA_AUDIT', 'reservedCostUsd': '.08'}]
        def fake_freeze(p, c):
            self.assertEqual(c['baselinePrompt'], c['candidatePrompt'])
            self.assertEqual(plan['refinements'][0]['fields']['condition'],
                             c['candidateReferenceSnapshot']['guidelines'][0]['condition'])
            return {'artifactHash': 'fixture', 'requests': [{'reservedCostUsd': '.001'}] * 42}
        with patch.object(tool, 'freeze', fake_freeze):
            actual, _, provenance = tool.prepare(trace, base, plan, {}, previous_window, prior)
        self.assertEqual(Decimal('.25846'), Decimal(provenance['priorReservedCostUsd']))
        self.assertLessEqual(Decimal(provenance['cumulativeReservedCostUsd']), Decimal('.5'))
        self.assertEqual(18, len(actual['requests']))
        for request in actual['requests']:
            source = next(c for c in actual['cases'] if c['name'] == request['case'])
            self.assertEqual(source['body']['messages'][1], request['body']['messages'][1])
            self.assertNotIn(recorded.DELTA, request['body']['messages'][0]['content'])
        prior[2]['reservedCostUsd'] = '.32'
        with patch.object(tool, 'freeze', fake_freeze):
            with self.assertRaises(ValueError): tool.prepare(trace, base, plan, {}, previous_window, prior)

    def test_reference_run_verifies_returned_model_without_retry(self):
        c = config()
        c.update(candidatePrompt=c['baselinePrompt'], candidateReferenceSnapshot=['candidate'])
        for correct in (True, False):
            def fake(key, body):
                result = response(key, body)
                if correct: result['model'] = body['model']
                return result
            with tempfile.TemporaryDirectory() as tmp:
                report = window.execute(window.freeze(packet(), c), Path(tmp) / 'datasets' / 'run', 'fake', fake)
            self.assertEqual(4 if correct else 1, report['apiAttempts'])
            self.assertEqual('COMPLETED_CONTRACT_CHECK_ONLY' if correct else 'PARTIAL', report['status'])
    def test_only_four_pattern_fields_change_and_source_is_immutable(self):
        base, plan = fixture()
        original = copy.deepcopy(base)
        result = tool.refine(base, plan)
        self.assertEqual(original, base)
        self.assertEqual(base['sourceCases'], result['sourceCases'])
        self.assertEqual('TARGET_TREATMENT', result['guidelines'][0]['axis'])
        self.assertEqual(plan['refinements'][0]['fields']['condition'], result['guidelines'][0]['condition'])

    def test_unapproved_bounded_existing_pattern_required(self):
        for mutation in ('approval', 'source', 'field', 'size', 'hash', 'multiple'):
            base, plan = fixture()
            row = plan['refinements'][0]
            if mutation == 'approval': plan['humanApproved'] = True
            if mutation == 'source': row['supportingCaseIds'] = ['new-unknown']
            if mutation == 'field': row['fields']['axis'] = 'OTHER'
            if mutation == 'size': row['fields']['condition'] = 'a' * 351
            if mutation == 'hash': plan['baseArchiveHash'] = 'bad'
            if mutation == 'multiple': plan['refinements'].append(copy.deepcopy(row))
            with self.assertRaises(ValueError): tool.refine(base, plan)

    def test_selected_contexts_and_surrounding_contract_preserved(self):
        base, plan = fixture()
        ref = {'reviewGuidelines': base['guidelines'], 'referenceContexts': [{'text': 'immutable'}]}
        prefix, suffix = 'POLICY\ncontextReference=', '\nOUTPUT CONTRACT'
        system = prefix + json.dumps(ref, ensure_ascii=False) + suffix
        changed = tool.replace_reference(system, base, tool.refine(base, plan), plan)
        content, end = json.JSONDecoder().raw_decode(changed[len(prefix):])
        self.assertEqual(ref['referenceContexts'], content['referenceContexts'])
        self.assertEqual(suffix, changed[len(prefix) + end:])
        self.assertTrue(changed.startswith(prefix))
        base['guidelines'][0]['condition'] = 'mismatch'
        with self.assertRaises(ValueError): tool.replace_reference(system, base, base, plan)

    def test_reference_only_window_keeps_raw_and_identical_policy(self):
        c = config()
        c['candidatePrompt'] = c['baselinePrompt']
        c['candidateReferenceSnapshot'] = [{'condition': 'refined'}]
        artifact = window.freeze(packet(), c)
        window.validate_artifact(artifact)
        for request in artifact['requests']:
            user = json.loads(request['body']['messages'][1]['content'])
            self.assertEqual(window.reference_for(c, request['job']['arm']), user['contextReference'])
        artifact['configuration']['candidatePrompt'] += 'changed'
        artifact['artifactHash'] = digest({k:v for k,v in artifact.items() if k != 'artifactHash'})
        with self.assertRaises(ValueError): window.validate_artifact(artifact)

    def test_rehashed_reference_tampering_is_rejected(self):
        c = config()
        c.update(candidatePrompt=c['baselinePrompt'], candidateReferenceSnapshot=['candidate'])
        artifact = window.freeze(packet(), c)
        request = artifact['requests'][0]
        user = json.loads(request['body']['messages'][1]['content'])
        user['contextReference'] = ['other']
        request['body']['messages'][1]['content'] = json.dumps(user)
        request['requestHash'] = digest(request['body'])
        artifact['artifactHash'] = digest({k:v for k,v in artifact.items() if k != 'artifactHash'})
        with self.assertRaises(ValueError): window.validate_artifact(artifact)
