import copy
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import extend_context_references as tool


def fixture():
    rule = {'id': 'mechanism', 'axis': 'TARGET_TREATMENT', 'channels': ['SPEECH', 'CAPTION'],
            'condition': 'synthetic condition', 'normalContrast': 'synthetic contrast',
            'requiredEvidence': 'synthetic evidence', 'missingContext': 'synthetic missing',
            'sourceCaseIds': ['old-case']}
    base = {'schemaVersion': 'review-guidelines-4', 'humanValidated': False,
            'status': 'WORKING_REFERENCE_NOT_VALIDATED', 'version': 'fixture',
            'sourceBundleSha256': 'a'*64, 'contextDictionarySha256': 'b'*64,
            'guidelines': [rule], 'sourceCases': [{'caseId': 'old-case', 'familyId': 'old-family'}],
            'examples': [{'id': 'old-case', 'familyId': 'old-family'}]}
    case = {'id': 'new-case', 'familyId': 'new-family', 'split': 'DEVELOPMENT',
            'status': 'UNREVIEWED_DRAFT', 'humanApproved': False, 'originalVerified': False,
            'commentCollectionStatus': 'NOT_COLLECTED', 'timestamps': None,
            'mechanismIds': ['mechanism'], 'sourceIds': ['source'],
            'reportedQuotes': [{'sourceId': 'source', 'kind': 'SOURCE_REPORTED_QUOTE_NOT_TRANSCRIPT', 'text': 'fixture quote'}],
            'contextFlow': ['fixture reported flow'], 'criticismHypotheses': ['fixture interpretation'],
            'missingContext': ['original not verified'], 'interpretationStatus': 'ASSISTANT_DRAFT_NOT_VIDEO_FACT',
            'normalContrast': {'status': 'SYNTHETIC_NOT_HISTORICAL_TRANSCRIPT', 'text': 'fixture normal'}}
    heldout = dict(copy.deepcopy(case), id='heldout-case', familyId='heldout-family', split='HOLDOUT')
    data = {'schemaVersion': 'source-first-context-additions-1', 'version': 'fixture-addition',
            'status': 'UNREVIEWED_DRAFT', 'humanApproved': False,
            'trainingUseAuthorized': False, 'runtimeEligible': False, 'cases': [case, heldout],
            'sources': [{'id': 'source', 'url': 'https://example.test/news', 'materialKind': 'NEWS_BODY',
                         'rightsReviewStatus': 'PENDING', 'retrievedOn': '2026-10-11', 'publishedOn': '2023-10-13'}]}
    return base, data


class SourceFirstTests(unittest.TestCase):
    def test_projection_keeps_patterns_and_base_unchanged(self):
        base, data = fixture()
        before = copy.deepcopy(base)
        result, provenance = tool.project(base, data)
        self.assertEqual(before, base)
        for channel in ('SPEECH', 'CAPTION'):
            self.assertEqual(tool.prompt_rules(base, channel), tool.prompt_rules(result, channel))
        self.assertEqual(['new-case'], provenance['addedReferenceCaseIds'])
        self.assertFalse(provenance['defaultRuntimeChanged'])

    def test_holdout_text_never_enters_projected_reference(self):
        base, data = fixture()
        data['cases'][1]['contextFlow'] = ['SECRET_HELDOUT_FLOW']
        result, provenance = tool.project(base, data)
        self.assertNotIn('SECRET_HELDOUT_FLOW', json.dumps(result))
        self.assertNotIn('heldout-case', json.dumps(result))
        self.assertEqual(['heldout-case'], provenance['withheldCaseIds'])

    def test_approval_and_fake_timestamps_refused(self):
        for key, value in (('humanApproved', True), ('originalVerified', True), ('timestamps', [1, 2])):
            base, data = fixture()
            data['cases'][0][key] = value
            with self.assertRaises(ValueError): tool.project(base, data)

    def test_unknown_sources_mechanisms_or_duplicate_family_refused(self):
        for key, value in (('sourceIds', ['missing']), ('mechanismIds', ['new-pattern']), ('familyId', 'old-family')):
            base, data = fixture()
            data['cases'][0][key] = value
            with self.assertRaises(ValueError): tool.project(base, data)

    def test_synthetic_contrast_cannot_be_historical_fact(self):
        base, data = fixture()
        data['cases'][0]['normalContrast']['status'] = 'VERIFIED_TRANSCRIPT'
        with self.assertRaises(ValueError): tool.project(base, data)

    def test_sources_do_not_enter_model_projection(self):
        result, _ = tool.project(*fixture())
        self.assertNotIn('https://', json.dumps(result))
        self.assertEqual([], result['examples'][-1]['context']['sourceInterpretations'])

    def trace(self):
        payload = {'raw': [{'id': 'a', 'startMs': 0, 'endMs': 1000, 'text': 'fixture raw'}],
                   'candidates': [{'anchorId': 'a', 'proposedEvidence': [{'segmentId': 'a', 'quote': 'fixture raw'}]}]}
        return {'success': True, 'data': {'available': True, 'snapshot': {
            'truncated': False, 'droppedRequests': 0, 'requests': [{'input': json.dumps(payload)}]}}}

    def test_routing_input_preserves_original_text_and_focus(self):
        result = tool.routing_input(self.trace())
        self.assertEqual('fixture raw', result['transcript'][0]['text'])
        self.assertEqual([0], result['candidateQueries'][0]['focusStartMs'])

    def test_partial_quote_cannot_claim_exact_existing_audit(self):
        trace = self.trace()
        payload = json.loads(trace['data']['snapshot']['requests'][0]['input'])
        payload['candidates'][0]['proposedEvidence'][0]['quote'] = 'fixture'
        trace['data']['snapshot']['requests'][0]['input'] = json.dumps(payload)
        with self.assertRaises(ValueError): tool.routing_input(trace)

    def test_unselected_additions_are_not_detection_improvement(self):
        row = {'requestKey': 'query', 'exampleIds': ['old-case'], 'payloadCodePoints': 100, 'baseCodePoints': 50}
        arms = {'baseline': {'selections': [row]}, 'extended': {'selections': [copy.deepcopy(row)]}}
        provenance = {'addedReferenceCaseIds': ['new-case'], 'withheldCaseIds': ['heldout-case']}
        summary = tool.summarize_routing(arms, provenance)
        self.assertFalse(summary['anyNewExampleSelected'])
        self.assertTrue(summary['allSelectedExampleIdsAndSizesUnchanged'])
        self.assertFalse(summary['detectionImprovementMeasured'])
        arms['extended']['selections'][0]['exampleIds'] = ['heldout-case']
        with self.assertRaises(ValueError): tool.summarize_routing(arms, provenance)
