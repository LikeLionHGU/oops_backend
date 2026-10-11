import copy
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from bound_review_contract import (adapt, grounding, judgment_request, prepare_attempt,
                                   receive, validate_attempt, validate_receipt)
from fixed_review_suite import digest
from issue_scoped_review import hydrate_selection
from test_issue_scoped_review import fixture


def setup():
    payload, selection, scope, judgment = fixture()
    base = dict(model='gpt-6-luna', reasoning_effort='low', max_completion_tokens=8000,
                response_format={'type': 'json_object'}, store=False,
                messages=[dict(role='system', content='공통 기준\n# 후보 근거 검증\n판단 기준\n'
                               '각 candidateId에 정확히 하나의 assessment old\n'
                               'category는 검토 이유 CAT\nscore는 old\n# 범용 맥락 참고 기준\nreference unchanged'),
                          dict(role='user', content=json.dumps(payload, ensure_ascii=False))])
    output = {k: copy.deepcopy(v) for k, v in judgment.items() if k != 'scopeHash'}
    return payload, selection, scope, base, output


def response(output):
    return dict(model='gpt-6-luna', choices=[dict(finish_reason='stop',
                message=dict(content=json.dumps(output, ensure_ascii=False)))])


class BoundReviewTests(unittest.TestCase):
    def test_hashes_stay_server_side_and_raw_references_model_settings_stay_fixed(self):
        p, _, scope, base, _ = setup(); before = copy.deepcopy((p, scope, base))
        body = judgment_request(base, scope)
        incoming = json.loads(body['messages'][1]['content'])
        self.assertEqual(incoming['original'], p)
        self.assertEqual(incoming['selectedScope']['evidence'], scope['evidence'])
        self.assertNotIn(scope['scopeHash'], json.dumps(body))
        self.assertNotIn('"scopeHash":', body['messages'][0]['content'])
        self.assertEqual(body['messages'][0]['content'].split('# 범용 맥락 참고 기준')[1],
                         base['messages'][0]['content'].split('# 범용 맥락 참고 기준')[1])
        for key in ('model', 'reasoning_effort', 'max_completion_tokens', 'response_format', 'store'):
            self.assertEqual(body[key], base[key])
        self.assertEqual((p, scope, base), before)

    def test_original_response_is_preserved_and_receipt_recomputes(self):
        _, _, scope, base, out = setup(); raw = response(out); original = copy.deepcopy(raw)
        ticket = prepare_attempt(base, scope, 'test-1')
        r = receive(ticket, ticket['requestBody'], raw); validate_receipt(r, ticket)
        self.assertEqual(raw, original); self.assertEqual(r['providerResponse'], original)
        self.assertNotIn('scopeHash', r['modelOutput'])
        self.assertEqual(r['compatiblePacket']['judgmentOutput']['scopeHash'], scope['scopeHash'])
        self.assertFalse(r['providerAuthenticityVerified']); self.assertFalse(r['runtimeUseAuthorized'])
        self.assertIsNone(r['qualityGateMet'])

    def test_old_hash_bearing_response_is_not_repaired_or_accepted(self):
        _, _, scope, base, out = setup(); ticket = prepare_attempt(base, scope, 'test-1')
        out['scopeHash'] = scope['scopeHash']
        with self.assertRaises(ValueError): receive(ticket, ticket['requestBody'], response(out))

    def test_changed_request_or_scope_and_rehashed_receipt_are_rejected(self):
        _, _, scope, base, out = setup(); ticket = prepare_attempt(base, scope, 'test-1')
        for mode in ('body', 'input', 'scope', 'hash'):
            changed = copy.deepcopy(ticket)
            if mode == 'body': changed['requestBody']['model'] = 'other'
            if mode == 'input': changed['originalInput']['raw'][0]['text'] += 'different'
            if mode == 'scope': changed['selectedScope']['evidence'][0]['quote'] = 'invented'
            if mode == 'hash': changed['requestHash'] = 'wrong'
            changed['attemptHash'] = digest({k: v for k, v in changed.items() if k != 'attemptHash'})
            with self.subTest(mode=mode), self.assertRaises(ValueError): validate_attempt(changed)
        different = copy.deepcopy(ticket['requestBody']); different['store'] = True
        with self.assertRaises(ValueError): receive(ticket, different, response(out))
        r = receive(ticket, ticket['requestBody'], response(out))
        r['compatiblePacket']['compatibleAssessment']['verifications'][0]['assessment']['reason'] = 'changed'
        r['receiptHash'] = digest({k: v for k, v in r.items() if k != 'receiptHash'})
        with self.assertRaises(ValueError): validate_receipt(r, ticket)

    def test_missing_target_does_not_force_pass_and_cannot_be_reclassified_from_context(self):
        p, selection, _, _, out = setup()
        selection['selections'].pop(1); scope = hydrate_selection(selection, p)
        self.assertFalse(grounding(scope, p)['targetSpanSelected'])
        out['relevance'] = [dict(selectionIndex=i, relation='SAME_ISSUE', reason='fixture') for i in range(2)]
        out['usedSelectionIndexes'] = [0, 1]
        with self.assertRaises(ValueError): adapt(out, scope, p)
        out['assessment'].update(decision='UNCERTAIN', category=None, target=None, targetType=None,
                                 targetReason=None, missingInformation=['실제 대상 지칭 근거 누락'])
        result = adapt(out, scope, p)
        self.assertEqual(result['compatibleAssessment']['verifications'][0]['assessment']['decision'], 'UNCERTAIN')
        out['assessment'].update(decision='PASS', missingInformation=[])
        self.assertEqual(adapt(out, scope, p)['compatibleAssessment']['verifications'][0]['assessment']['decision'], 'PASS')

    def test_target_review_must_use_target_and_all_relevance_not_autofilled(self):
        p, _, scope, _, out = setup()
        for mode in ('target-unused', 'target-other', 'missing-relevance', 'target-type', 'score', 'bool-score'):
            bad = copy.deepcopy(out)
            if mode == 'target-unused': bad['usedSelectionIndexes'] = [0, 2]
            if mode == 'target-other': bad['relevance'][1]['relation'] = 'OTHER_ISSUE'
            if mode == 'missing-relevance': bad['relevance'].pop()
            if mode == 'target-type': bad['assessment']['targetType'] = 'PLACE'
            if mode == 'score': bad['assessment']['score'] = 2
            if mode == 'bool-score': bad['assessment']['score'] = True
            with self.subTest(mode=mode), self.assertRaises(ValueError): adapt(bad, scope, p)

    def test_targetless_expression_review_is_valid_but_no_semantic_approval(self):
        p, selection, _, _, out = setup(); p['candidates'][0]['axis'] = 'EXPRESSION_CONTENT'
        selection['selections'].pop(1); scope = hydrate_selection(selection, p)
        out['relevance'].pop(); out['usedSelectionIndexes'] = [0, 1]
        out['assessment'].update(category='GRAPHIC_METAPHOR', target=None, targetType=None, targetReason=None)
        packet = adapt(out, scope, p)
        self.assertIsNone(packet['semanticQualityGateMet'])
        self.assertFalse(packet['runtimeUseAuthorized'])
        self.assertIsNone(packet['compatibleAssessment']['verifications'][0]['assessment']['targetMention'])

    def test_provider_refusal_incomplete_json_duplicate_keys_and_nonfinite_fail_closed(self):
        _, _, scope, base, out = setup(); ticket = prepare_attempt(base, scope, 'test-1')
        for mode in ('refusal', 'length', 'multiple', 'model', 'json', 'duplicate', 'nan', 'tool'):
            raw = response(out)
            message = raw['choices'][0]['message']
            if mode == 'refusal': message['refusal'] = 'refused'
            if mode == 'length': raw['choices'][0]['finish_reason'] = 'length'
            if mode == 'multiple': raw['choices'] *= 2
            if mode == 'model': raw['model'] = 'other'
            if mode == 'json': message['content'] = '{} broken'
            if mode == 'duplicate': message['content'] = '{"candidateId":"issue","candidateId":"other"}'
            if mode == 'nan': message['content'] = '{"score":NaN}'
            if mode == 'tool': message['tool_calls'] = [{}]
            with self.subTest(mode=mode), self.assertRaises(ValueError): receive(ticket, ticket['requestBody'], raw)

    def test_attempt_identity_cross_candidate_output_and_false_semantic_claim_stay_distinct(self):
        _, _, scope, base, out = setup()
        first = prepare_attempt(base, scope, 'attempt-1'); second = prepare_attempt(base, scope, 'attempt-2')
        self.assertNotEqual(first['attemptHash'], second['attemptHash'])
        r = receive(first, first['requestBody'], response(out))
        with self.assertRaises(ValueError): validate_receipt(r, second)
        out['candidateId'] = 'other'
        with self.assertRaises(ValueError): receive(first, first['requestBody'], response(out))
        # Model's SAME_ISSUE claim is never upgraded to a human semantic label.
        out['candidateId'] = 'issue'
        r = receive(first, first['requestBody'], response(out))
        self.assertIsNone(r['compatiblePacket']['semanticReview']['evidenceRelationsVerified'])
