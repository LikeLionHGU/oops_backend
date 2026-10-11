from pathlib import Path
import sys
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from semantic_boundary_guidance import prompt,prepare,JUDGMENT,TARGET


class GuidanceTests(unittest.TestCase):
    def test_one_change_only_preserves_existing_policy_contract_and_reference(self):
        policy='original judgment\nJSON contract unchanged\n'
        reference='# 범용 맥락 참고 기준\nreference unchanged'
        for mode,delta,other in [('JUDGMENT',JUDGMENT,TARGET),('TARGET',TARGET,JUDGMENT)]:
            result=prompt(policy+reference,mode)
            self.assertTrue(result.startswith(policy))
            self.assertTrue(result.endswith(reference))
            self.assertIn(delta,result)
            self.assertNotIn(other,result)

    def test_rejects_combined_mode_or_ambiguous_reference(self):
        for system,mode in [('no boundary','JUDGMENT'),('# 범용 맥락 참고 기준'*2,'TARGET'),
                            ('# 범용 맥락 참고 기준','BOTH')]:
            with self.assertRaises(ValueError): prompt(system,mode)

    def test_preparation_preserves_inputs_and_does_not_authorize_paid_calls(self):
        import copy
        from test_prepare_evidence_selection_trial import setup
        source=setup()[0]
        original=copy.deepcopy(source)
        value=prepare(source)
        self.assertEqual(source,original)
        self.assertEqual(len(value['comparisons']),14)
        self.assertFalse(value['apiExecutionEnabled'])
        self.assertIsNone(value['reservedCostUsd'])
        for c in value['comparisons']:
            for key in c['baselineBody']:
                if key!='messages': self.assertEqual(c['baselineBody'][key],c['candidateBody'][key])
            self.assertEqual(c['baselineBody']['messages'][1],c['candidateBody']['messages'][1])
