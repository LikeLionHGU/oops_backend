import copy
from pathlib import Path
import sys
import tempfile
import unittest
from decimal import Decimal

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import prepare_target_wire_trial as tool
import compare_recorded_candidates as executor
from fixed_review_suite import digest
from test_compare_recorded_candidates import fixture,response


def inputs():
    pattern = executor.prepare(*fixture())
    system = ('judgment\nevidence는 최대 8개로 old\ncategory는 검토 이유\n'
              '# 대상 필드 형식 예시\nold\n# 범용 맥락 참고 기준\nunchanged')
    for r in pattern['requests']: r['body']['messages'][0]['content'] = system
    pattern['artifactHash'] = digest({k:v for k,v in pattern.items() if k != 'artifactHash'})
    boundary = copy.deepcopy(pattern)
    boundary['cases'] = [copy.deepcopy(pattern['cases'][1])]
    for name in ('N1','N2','N3','N4'):
        case = copy.deepcopy(pattern['cases'][0]); case['name'] = name
        boundary['cases'].append(case)
        for arm in ('BASELINE','CANDIDATE'):
            row = copy.deepcopy(pattern['requests'][0]); row.update(case=name,arm=arm)
            boundary['requests'].append(row)
    boundary['artifactHash'] = digest({k:v for k,v in boundary.items() if k != 'artifactHash'})
    prior = {'artifactHash':boundary['artifactHash'],'reservedCostUsd':'0.0537028',
             'status':'COMPLETED_PENDING_JAVA_AUDIT'}
    authorization = {'schemaVersion':'additional-evaluation-budget-1','additionalBudgetUsd':'.5',
                     'priorReservedCostUsd':'0.4994146','userAuthorized':True}
    return pattern,boundary,prior,authorization


class PreparationTests(unittest.TestCase):
    def test_new_budget_and_fixed_inputs_with_fake_execution(self):
        args = inputs()
        original = copy.deepcopy(args)
        artifact = tool.prepare(*args)
        self.assertEqual(original,args)
        self.assertEqual(42,len(artifact['requests']))
        self.assertLess(Decimal(artifact['reservedTotalCostUsd']),Decimal('.5'))
        for a,b in zip(artifact['requests'][::2],artifact['requests'][1::2]):
            self.assertEqual(a['body']['messages'][1],b['body']['messages'][1])
            self.assertNotEqual(a.get('outputAdapter'),b.get('outputAdapter'))
        with tempfile.TemporaryDirectory() as tmp:
            result = executor.execute(artifact,Path(tmp)/'datasets'/'run','fake',response)
        self.assertEqual('COMPLETED_PENDING_JAVA_AUDIT',result['status'])
        self.assertEqual(42,len(result['calls']))
        self.assertTrue(all('wireOutput' in c for c in result['calls'] if c['arm'] == 'CANDIDATE'))

    def test_unconfirmed_authorization_and_changed_source_fail_before_execution(self):
        for mode in ('authorization','hash','previous'):
            args = list(inputs())
            if mode == 'authorization': args[3]['userAuthorized'] = False
            if mode == 'hash': args[0]['cases'][0]['name'] = 'changed'
            if mode == 'previous': args[2]['artifactHash'] = 'wrong'
            with self.assertRaises(ValueError): tool.prepare(*args)

    def test_legacy_output_is_not_repaired_under_new_adapter(self):
        artifact = tool.prepare(*inputs())
        def legacy(key,body):
            result = response(key,body)
            import json
            output = json.loads(result['choices'][0]['message']['content'])
            output['verifications'][0]['assessment']['targetRelation'] = None
            result['choices'][0]['message']['content'] = json.dumps(output)
            return result
        with tempfile.TemporaryDirectory() as tmp:
            result = executor.execute(artifact,Path(tmp)/'datasets'/'run','fake',legacy)
        candidate = [r for r in result['calls'] if r['arm'] == 'CANDIDATE']
        self.assertTrue(all(r['status'] == 'PYTHON_WIRE_REJECTED' for r in candidate))
        self.assertTrue(all('output' not in r for r in candidate))
        exported = executor.audit_export(result)
        self.assertTrue(all(r['error'] == 'WIRE_ADAPTATION_REJECTED' for r in exported['calls'] if r['arm'] == 'CANDIDATE'))
