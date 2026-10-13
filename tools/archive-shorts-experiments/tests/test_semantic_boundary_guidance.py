from pathlib import Path
import sys
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from semantic_boundary_guidance import prompt,prepare,freeze,JUDGMENT,TARGET


def frozen_inputs():
    from test_prepare_evidence_selection_trial import setup
    from prepare_evidence_selection_trial import prepare as selection_prepare
    source,prior,auth=setup()
    selected=selection_prepare(source,prior,auth)
    report=dict(artifactHash=selected['artifactHash'],reservedCostUsd='0.1829613',
                status='COMPLETED_PENDING_JAVA_AUDIT',calls=[{}]*42)
    return selected,prepare(selected),[prior,report],auth


class GuidanceTests(unittest.TestCase):
    def test_freeze_and_fake_execution_keep_modes_separate(self):
        import tempfile
        from decimal import Decimal
        from compare_recorded_candidates import execute
        from test_compare_recorded_candidates import response
        artifact=freeze(*frozen_inputs())
        self.assertEqual(artifact['remainingBudgetUsd'],'0.1340921')
        self.assertLessEqual(Decimal(artifact['reservedTotalCostUsd']),Decimal('0.1340921'))
        self.assertEqual(artifact['repetitionsPerCondition'],1)
        with tempfile.TemporaryDirectory() as tmp:
            report=execute(artifact,Path(tmp)/'datasets'/'run',None,response, simulate=True)
        self.assertEqual(len(report['calls']),28)
        for mode in ('JUDGMENT','TARGET'):
            self.assertEqual(sum(c['comparisonMode']==mode for c in report['calls']),14)

    def test_invalid_draft_or_prior_ledger_cannot_freeze(self):
        for mode in ('draft','ledger'):
            args=frozen_inputs()
            if mode=='draft': args[1]['comparisons'][0]['candidateBody']['temperature']=0.9
            else: args[2][1]['reservedCostUsd']='0'
            with self.assertRaises(ValueError): freeze(*args)

    def test_reset_budget_or_combined_guidance_fails_before_transport(self):
        import tempfile
        from fixed_review_suite import digest
        from compare_recorded_candidates import execute
        for mode in ('budget','guidance'):
            artifact=freeze(*frozen_inputs())
            if mode=='budget': artifact['remainingBudgetUsd']='.5'
            else:
                row=next(r for r in artifact['requests'] if r['arm']=='CANDIDATE')
                row['body']['messages'][0]['content']+=TARGET
            artifact['artifactHash']=digest({k:v for k,v in artifact.items() if k!='artifactHash'})
            with tempfile.TemporaryDirectory() as tmp, self.assertRaises(ValueError):
                execute(artifact,Path(tmp)/'datasets'/'run',None,lambda *args:self.fail('paid call'), simulate=True)

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
