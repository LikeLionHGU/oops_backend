import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from target_wire_contract import adapt,prompt


def fixture():
    raw = [{'id':'a','text':'메뉴가 의미가 없어'},{'id':'b','text':'주는 대로 먹어야 돼'}]
    payload = {'raw':raw,'candidates':[{'candidateId':'c','segmentIds':['a','b']}]}
    a = {'decision':'REVIEW_REQUIRED','target':'해당 장소의 메뉴','evidence':[
        {'segmentId':'a','quote':'메뉴','role':'TARGET'},
        {'segmentId':'a','quote':'메뉴가 의미가 없어','role':'PRIMARY'},
        {'segmentId':'b','quote':'주는 대로 먹어야 돼','role':'CONTEXT'}]}
    return {'verifications':[{'candidateId':'c','assessment':a}]},payload


class WireTests(unittest.TestCase):
    def test_derives_mechanical_relation_without_changing_quotes_or_target(self):
        output,payload = fixture()
        original = copy.deepcopy(output)
        result = adapt(output,payload)['verifications'][0]['assessment']
        self.assertEqual(original,output)
        self.assertEqual(output['verifications'][0]['assessment']['evidence'],result['evidence'])
        self.assertEqual('메뉴',result['targetMention'])
        self.assertEqual('CONTEXTUAL',result['targetRelation'])
        output['verifications'][0]['assessment']['target'] = '메뉴'
        self.assertEqual('EXPLICIT',adapt(output,payload)['verifications'][0]['assessment']['targetRelation'])

    def test_never_repairs_wrong_row_legacy_fields_or_budget_overflow(self):
        for mode in ('id','legacy','budget','ambiguous','outside','missing-context'):
            output,payload = fixture()
            a = output['verifications'][0]['assessment']
            if mode == 'id': a['evidence'][0]['segmentId'] = 'b'
            if mode == 'legacy': a['targetRelation'] = 'EXPLICIT'
            if mode == 'budget': a['evidence'] *= 3
            if mode == 'ambiguous': a['evidence'].append(copy.deepcopy(a['evidence'][0]))
            if mode == 'outside': payload['candidates'][0]['segmentIds'] = ['a']
            if mode == 'missing-context': a['evidence'].pop()
            with self.assertRaises(ValueError): adapt(output,payload)

    def test_non_target_decisions_do_not_invent_target(self):
        for decision in ('PASS','UNCERTAIN','REVIEW_REQUIRED'):
            output,payload = fixture()
            a = output['verifications'][0]['assessment']
            a.update(decision=decision,target=None)
            a['evidence'] = a['evidence'][1:]
            result = adapt(output,payload)['verifications'][0]['assessment']
            self.assertIsNone(result['targetMention'])
            self.assertIsNone(result['targetRelation'])

    def test_prompt_preserves_judgment_prefix_and_reference_suffix(self):
        prefix = '판단 지침\nevidence는 최대 8개로 old\n'
        suffix = '# 범용 맥락 참고 기준\ncontextReference={"unchanged":true}'
        system = prefix + 'category는 검토 이유\n"targetRelation":null,"targetMention":null,\n# 대상 필드 형식 예시\nold example\n' + suffix
        value = prompt(system)
        self.assertTrue(value.startswith('판단 지침\n'))
        self.assertTrue(value.endswith(suffix))
        self.assertNotIn('old example',value)
        self.assertNotIn('"targetRelation":null',value)
