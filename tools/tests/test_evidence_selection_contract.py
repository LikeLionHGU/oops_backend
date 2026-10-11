import copy
from pathlib import Path
import sys
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from evidence_selection_contract import adapt,prompt,VERSION
from compare_recorded_candidates import checked_output
from test_target_wire_contract import fixture


def selection():
    out,payload = fixture()
    payload['candidates'][0]['anchorId'] = 'a'
    a = out['verifications'][0]['assessment']
    a['segmentId'] = 'a'
    for e in a['evidence']: e.pop('quote')
    return out,payload


class SelectionTests(unittest.TestCase):
    def test_hydrates_full_raw_without_mutation(self):
        out,payload = selection()
        original = copy.deepcopy(out)
        a = adapt(out,payload)['verifications'][0]['assessment']
        self.assertEqual(out,original)
        self.assertEqual(a['evidenceText'],'메뉴가 의미가 없어')
        self.assertEqual(a['targetMention'],'메뉴가 의미가 없어')
        self.assertEqual(a['targetRelation'],'CONTEXTUAL')
        for e in a['evidence']:
            self.assertEqual(e['quote'],next(r['text'] for r in payload['raw'] if r['id'] == e['segmentId']))

    def test_rejects_generated_text_wrong_selection_duplicate_or_overflow(self):
        for mode in ('quote','evidenceText','duplicate','budget','anchor','primary','outside','ambiguous-input','legacy','missing-target'):
            out,payload = selection()
            a = out['verifications'][0]['assessment']
            if mode == 'quote': a['evidence'][0]['quote'] = '메뉴'
            if mode == 'evidenceText': a['evidenceText'] = '메뉴'
            if mode == 'duplicate': a['evidence'].append(copy.deepcopy(a['evidence'][0]))
            if mode == 'budget': a['evidence'] *= 3
            if mode == 'anchor': a['segmentId'] = 'b'
            if mode == 'primary': a['evidence'][1]['segmentId'] = 'b'
            if mode == 'outside': a['evidence'][0]['segmentId'] = 'unknown'
            if mode == 'ambiguous-input': payload['raw'].append(copy.deepcopy(payload['raw'][0]))
            if mode == 'legacy': a['targetMention'] = None
            if mode == 'missing-target': a['evidence'][0]['role'] = 'CONTEXT'
            with self.subTest(mode=mode), self.assertRaises(ValueError): adapt(out,payload)

    def test_rejected_response_retains_original_no_output(self):
        import json
        out,payload = selection()
        out['verifications'][0]['assessment']['evidence'][0]['quote'] = 'invented'
        result = {'outputAdapter':VERSION,'providerResponse':{'choices':[{'finish_reason':'stop','message':{'content':json.dumps(out)}}]}}
        checked_output(result,payload)
        self.assertEqual(result['wireOutput'],out)
        self.assertNotIn('output',result)
        self.assertEqual(result['status'],'PYTHON_WIRE_REJECTED')

    def test_prompt_keeps_reference_and_judgment(self):
        system = ('판단 지침\nevidence는 최대 8개로 old\ncategory는 검토 이유\n'
                  '"evidenceText":"PRIMARY 인용","quote":"PRIMARY 인용",\n'
                  '# 대상 필드 형식 예시\nold\n# 범용 맥락 참고 기준\nref unchanged')
        result = prompt(system)
        self.assertTrue(result.startswith('판단 지침\n'))
        self.assertTrue(result.endswith('# 범용 맥락 참고 기준\nref unchanged'))
        self.assertNotIn('"quote"',result)
        self.assertNotIn('"evidenceText"',result)

    def test_reference_is_not_rewritten(self):
        system = ('판단\nevidence는 최대 8개로 old\ncategory는 검토 이유\n'
                  '# 대상 필드 형식 예시\nold\n# 범용 맥락 참고 기준\n'
                  '"evidenceText":"PRIMARY 인용","quote":"PRIMARY 인용",')
        self.assertEqual(prompt(system).split('# 범용 맥락 참고 기준')[1],
                         system.split('# 범용 맥락 참고 기준')[1])

    def test_selection_diagnostics_do_not_expect_quotes(self):
        from diagnose_candidate_output import diagnose
        out,payload = selection()
        result = diagnose(out['verifications'][0]['assessment'],payload['raw'],wire=True,selection=True)
        self.assertEqual(result['defects'],[])
