import copy
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fixed_review_suite import digest
from issue_scoped_review import (adapt_judgment, hydrate_selection, judgment_request,
                                 prompts, review_packet, selection_request, validate_scope)


def fixture():
    payload = {'raw': [{'id': 'a', 'text': '이 장소의 선택을 무시한다'},
                       {'id': 'b', 'text': '같은 선택에 대한 설명'},
                       {'id': 'other', 'text': '다른 메뉴를 평가한다'}],
               'candidates': [{'candidateId': 'issue', 'anchorId': 'a', 'axis': 'TARGET_TREATMENT',
                               'segmentIds': ['a', 'b', 'other'],
                               'hypothesisNotEvidence': '선택에 관한 평가 연결을 확인한다',
                               'proposedEvidence': [{'segmentId': 'a', 'quote': '이 장소'},
                                                    {'segmentId': 'b', 'quote': '설명'}]}]}
    selection = {'candidateId': 'issue', 'anchorId': 'a', 'issueSummary': '선택 평가 연결',
                 'selections': [{'segmentId': 'a', 'start': 0, 'end': 15, 'role': 'PRIMARY'},
                                {'segmentId': 'a', 'start': 0, 'end': 4, 'role': 'TARGET'},
                                {'segmentId': 'b', 'start': 0, 'end': 13, 'role': 'CONTEXT'}]}
    # Offsets derive from raw, not generated quote correction.
    selection['selections'][0]['end'] = len(payload['raw'][0]['text'])
    selection['selections'][2]['end'] = len(payload['raw'][1]['text'])
    scope = hydrate_selection(selection, payload)
    assessment = {'decision': 'REVIEW_REQUIRED', 'reason': '현재 선택을 낮추는 실제 연결',
                  'alternativeInterpretation': '단순 설명인지 대조한다', 'category': 'BELITTLEMENT',
                  'target': '해당 장소의 선택', 'targetType': 'REGION',
                  'targetReason': '현재 장소의 선택이라는 연결', 'score': None, 'missingInformation': []}
    judgment = {'candidateId': 'issue', 'scopeHash': scope['scopeHash'],
                'relevance': [{'selectionIndex': i, 'relation': 'SAME_ISSUE', 'reason': '현재 쟁점의 원문 연결'}
                              for i in range(3)], 'usedSelectionIndexes': [0, 1, 2], 'assessment': assessment}
    return payload, selection, scope, judgment


class IssueScopedReviewTests(unittest.TestCase):
    def test_hydration_and_adapter_are_immutable_and_legacy_compatible(self):
        payload, selection, scope, judgment = fixture()
        originals = copy.deepcopy((payload, selection, scope, judgment))
        out = adapt_judgment(judgment, scope, payload)
        a = out['verifications'][0]['assessment']
        self.assertEqual((payload, selection, scope, judgment), originals)
        self.assertEqual(a['segmentId'], 'a')
        self.assertEqual(a['evidenceText'], payload['raw'][0]['text'])
        self.assertEqual(a['targetMention'], '이 장소')
        self.assertEqual(a['targetRelation'], 'CONTEXTUAL')
        self.assertIsNone(a['context'])
        self.assertIsNone(a['reading'])
        self.assertFalse(scope['semanticReviewApproved'])

    def test_wrong_topic_or_uncertain_support_is_rejected_not_changed_to_pass(self):
        for relation in ('OTHER_ISSUE', 'UNSURE'):
            payload, _, scope, judgment = fixture()
            judgment['relevance'][2]['relation'] = relation
            original = copy.deepcopy(judgment)
            with self.subTest(relation=relation), self.assertRaises(ValueError):
                adapt_judgment(judgment, scope, payload)
            self.assertEqual(original, judgment)

    def test_unrelated_selection_can_remain_visible_without_supporting_assessment(self):
        payload, selection, _, judgment = fixture()
        selection['selections'].append({'segmentId': 'other', 'start': 0,
                                        'end': len(payload['raw'][2]['text']), 'role': 'CONTEXT'})
        scope = hydrate_selection(selection, payload)
        judgment['scopeHash'] = scope['scopeHash']
        judgment['relevance'].append({'selectionIndex': 3, 'relation': 'OTHER_ISSUE', 'reason': '별도 메뉴 평가'})
        out = adapt_judgment(judgment, scope, payload)
        self.assertNotIn('other', [e['segmentId'] for e in out['verifications'][0]['assessment']['evidence']])
        self.assertIn('other', [e['segmentId'] for e in scope['evidence']])

    def test_offsets_are_code_points_including_emoji(self):
        payload, selection, _, _ = fixture()
        payload['raw'][0]['text'] = '🙂 지역 이야기'
        selection['selections'][0].update(start=0, end=2)
        selection['selections'][1].update(start=2, end=4)
        scope = hydrate_selection(selection, payload)
        self.assertEqual(scope['evidence'][0]['quote'], '🙂 ')
        self.assertEqual(scope['evidence'][1]['quote'], '지역')

    def test_selection_rejects_generated_quote_offsets_duplicates_anchor_and_budget(self):
        for mode in ('quote', 'decision', 'offset', 'boolean', 'empty', 'duplicate', 'target',
                     'outside', 'anchor', 'no-primary', 'budget', 'duplicate-raw'):
            payload, selection, _, _ = fixture()
            if mode == 'quote': selection['selections'][0]['quote'] = 'invented'
            if mode == 'decision': selection['decision'] = 'REVIEW_REQUIRED'
            if mode == 'offset': selection['selections'][0]['end'] = 999
            if mode == 'boolean': selection['selections'][0]['start'] = False
            if mode == 'empty': selection['selections'][0]['end'] = 0
            if mode == 'duplicate': selection['selections'].append(copy.deepcopy(selection['selections'][0]))
            if mode == 'target': selection['selections'][2]['role'] = 'TARGET'
            if mode == 'outside': payload['candidates'][0]['segmentIds'].remove('b')
            if mode == 'anchor': selection['anchorId'] = 'b'
            if mode == 'no-primary': selection['selections'][0]['role'] = 'CONTEXT'
            if mode == 'budget': selection['selections'] *= 3
            if mode == 'duplicate-raw': payload['raw'].append(copy.deepcopy(payload['raw'][0]))
            with self.subTest(mode=mode), self.assertRaises(ValueError): hydrate_selection(selection, payload)

    def test_scope_tampering_or_input_replacement_is_rejected_even_with_rehashed_scope(self):
        for mode in ('quote', 'input', 'approved', 'hash'):
            payload, _, scope, _ = fixture()
            if mode == 'quote': scope['evidence'][0]['quote'] = 'invented'
            if mode == 'input': payload['raw'][0]['text'] += 'changed'
            if mode == 'approved': scope['semanticReviewApproved'] = True
            if mode == 'hash': scope['scopeHash'] = 'wrong'
            else: scope['scopeHash'] = digest({k:v for k,v in scope.items() if k != 'scopeHash'})
            with self.subTest(mode=mode), self.assertRaises(ValueError): validate_scope(scope, payload)

    def test_judgment_cannot_reselect_rewrite_or_bypass_linkage(self):
        for mode in ('hash', 'id', 'quote', 'role', 'primary', 'duplicate', 'range', 'boolean',
                     'missing-relevance', 'duplicate-relevance', 'invented-relation', 'target'):
            payload, _, scope, judgment = fixture()
            if mode == 'hash': judgment['scopeHash'] = 'wrong'
            if mode == 'id': judgment['candidateId'] = 'different'
            if mode == 'quote': judgment['assessment']['evidenceText'] = 'invented'
            if mode == 'role': judgment['assessment']['evidence'] = []
            if mode == 'primary': judgment['usedSelectionIndexes'] = [1, 2]
            if mode == 'duplicate': judgment['usedSelectionIndexes'] = [0, 0]
            if mode == 'range': judgment['usedSelectionIndexes'] = [0, 8]
            if mode == 'boolean': judgment['usedSelectionIndexes'] = [True, 0]
            if mode == 'missing-relevance': judgment['relevance'].pop()
            if mode == 'duplicate-relevance': judgment['relevance'][1]['selectionIndex'] = 0
            if mode == 'invented-relation': judgment['relevance'][1]['relation'] = 'APPROVED'
            if mode == 'target': judgment['usedSelectionIndexes'] = [0, 2]
            with self.subTest(mode=mode), self.assertRaises(ValueError): adapt_judgment(judgment, scope, payload)

    def test_pass_is_not_forced_and_needs_normal_contrast_and_context(self):
        payload, _, scope, judgment = fixture()
        a = judgment['assessment']
        a.update(decision='PASS', category=None, target=None, targetType=None, targetReason=None)
        judgment['usedSelectionIndexes'] = [0, 2]
        out = adapt_judgment(judgment, scope, payload)
        self.assertEqual(out['verifications'][0]['assessment']['decision'], 'PASS')
        for mode in ('contrast', 'context', 'target-field'):
            bad = copy.deepcopy(judgment)
            if mode == 'contrast': bad['assessment']['alternativeInterpretation'] = None
            if mode == 'context': bad['usedSelectionIndexes'] = [0]
            if mode == 'target-field': bad['assessment']['target'] = 'unexpected'
            with self.subTest(mode=mode), self.assertRaises(ValueError): adapt_judgment(bad, scope, payload)

    def test_expression_does_not_require_target_and_uncertain_is_not_repaired(self):
        payload, selection, _, judgment = fixture()
        payload['candidates'][0]['axis'] = 'EXPRESSION_CONTENT'
        scope = hydrate_selection(selection, payload)
        judgment['scopeHash'] = scope['scopeHash']
        judgment['usedSelectionIndexes'] = [0, 2]
        a = judgment['assessment']
        a.update(category='GRAPHIC_METAPHOR', target=None, targetType=None, targetReason=None)
        self.assertIsNone(adapt_judgment(judgment, scope, payload)['verifications'][0]['assessment']['targetMention'])
        a.update(decision='UNCERTAIN', category=None, missingInformation=['대상 연결 정보가 부족하다'])
        out = adapt_judgment(judgment, scope, payload)
        self.assertEqual(out['verifications'][0]['assessment']['decision'], 'UNCERTAIN')

    def test_prompts_preserve_full_input_and_reference_only_in_judgment(self):
        payload, _, scope, _ = fixture()
        system = ('공통 판단 기준\n# 후보 근거 검증\n원문 판단 기준\n'
                  '각 candidateId에 정확히 하나의 assessment old\ncategory는 검토 이유 CAT\nscore는 old\n'
                  '# 범용 맥락 참고 기준\nreference unchanged')
        base = {'model': 'gpt-6-luna', 'reasoning_effort': 'low', 'max_completion_tokens': 8000,
                'response_format': {'type': 'json_object'}, 'store': False,
                'messages': [{'role': 'system', 'content': system},
                             {'role': 'user', 'content': json.dumps(payload, ensure_ascii=False)}]}
        original = copy.deepcopy(base)
        first = selection_request(base)
        second = judgment_request(base, scope)
        self.assertEqual(base, original)
        self.assertEqual(first['messages'][1], base['messages'][1])
        self.assertEqual(json.loads(second['messages'][1]['content'])['original'], payload)
        self.assertEqual(first['model'], second['model'])
        self.assertNotIn('reference unchanged', first['messages'][0]['content'])
        self.assertTrue(second['messages'][0]['content'].endswith('reference unchanged'))
        self.assertNotIn('assessment old', second['messages'][0]['content'])
        with self.assertRaises(ValueError): prompts('unrecognized old prompt')

    def test_same_issue_claim_is_not_semantic_approval_even_when_model_borrows_other_topic(self):
        payload, selection, _, judgment = fixture()
        selection['selections'][2].update(segmentId='other', end=len(payload['raw'][2]['text']))
        scope = hydrate_selection(selection, payload)
        judgment['scopeHash'] = scope['scopeHash']
        # A model can falsely label a menu evaluation as the candidate issue.
        # Literal-ID/role validity cannot establish the truth of that relation.
        packet = review_packet(judgment, scope, payload)
        self.assertTrue(packet['mechanicalContractOnly'])
        self.assertIsNone(packet['semanticQualityGateMet'])
        self.assertFalse(packet['runtimeUseAuthorized'])
        self.assertTrue(packet['currentJavaValidationRequired'])
        self.assertIsNone(packet['semanticReview']['candidateIssueMatched'])
        self.assertEqual(packet['originalInput'], payload)
        self.assertEqual(packet['judgmentOutput'], judgment)

    def test_hydrated_result_passes_existing_python_literal_contract(self):
        from compare_recorded_candidates import checked_output
        payload, _, scope, judgment = fixture()
        out = adapt_judgment(judgment, scope, payload)
        result = {'providerResponse': {'choices': [{'finish_reason': 'stop',
                  'message': {'content': json.dumps(out)}}]}}
        checked_output(result, payload)
        self.assertEqual(result['status'], 'PYTHON_EVIDENCE_CHECK_ONLY')

    def test_draft_does_not_leak_expected_labels_or_authorize_execution(self):
        from test_prepare_flow_regression import inputs
        from prepare_flow_regression import prepare
        from issue_scoped_review import prepare_draft
        source = prepare(*inputs(), variant='REASONING_LOW')
        system = ('공통 정책\n# 후보 근거 검증\n판단 기준\n'
                  '각 candidateId에 정확히 하나의 assessment old\ncategory는 검토 이유 CAT\nscore는 old\n'
                  '# 범용 맥락 참고 기준\nreference unchanged')
        for r in source['requests']:
            r['body']['messages'][0]['content'] = system
            r['requestHash'] = digest(r['body'])
        source['artifactHash'] = digest({k:v for k,v in source.items() if k != 'artifactHash'})
        original = copy.deepcopy(source)
        result = prepare_draft(source)
        self.assertEqual(source, original)
        self.assertEqual(len(result['drafts']), 7)
        self.assertFalse(result['executionEnabled'])
        self.assertIsNone(result['reservationUsd'])
        self.assertIsNone(result['qualityGateMet'])
        for row in result['drafts']:
            self.assertNotIn('developmentExpectation', json.dumps(row['selectionRequest']))
            self.assertIsNone(row['semanticReview']['evidenceRelationsVerified'])
        source['requests'][0]['body']['model'] = 'changed'
        with self.assertRaises(ValueError): prepare_draft(source)
