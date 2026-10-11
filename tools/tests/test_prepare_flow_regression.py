import copy
from pathlib import Path
import sys
import tempfile
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from fixed_review_suite import digest
from prepare_flow_regression import OLD,NEW,prepare,validate,prompt
from semantic_boundary_guidance import freeze
from test_semantic_boundary_guidance import frozen_inputs
from compare_recorded_candidates import execute
from test_compare_recorded_candidates import response


def inputs():
    source=freeze(*frozen_inputs())
    for r in source['requests']:
        if r['arm']=='BASELINE': r['body']['messages'][0]['content']=OLD+'\n'+r['body']['messages'][0]['content']
    source['artifactHash']=digest({k:v for k,v in source.items() if k!='artifactHash'})
    auth=dict(schemaVersion='flow-regression-budget-1',additionalBudgetUsd='1.00',
              userAuthorized=True,previousAuthorizationsReservedUsd='0.9924228')
    return source,auth


class FlowTests(unittest.TestCase):
    def test_salience_probe_keeps_original_raw_and_rejects_invented_support(self):
        import json
        from prepare_flow_regression import prepare_salience
        from run_boundary_comparison import reserve
        source,auth=inputs()
        payload=next(c['payload'] for c in source['cases'] if c['name']=='B')
        for sid,text in [('stt-2229','earlier question'),('stt-2233','earlier response')]:
            payload['raw'].append(dict(id=sid,text=text,startMs=0,endMs=1000))
            payload['candidates'][0]['segmentIds'].append(sid)
        for row in source['requests']:
            if row['case']=='B': row['body']['messages'][1]['content']=json.dumps(payload)
        source['artifactHash']=digest({k:v for k,v in source.items() if k!='artifactHash'})
        controls=dict(schemaVersion='owner-closer-normal-boundaries-1',reviewStatus='OWNER_DEVELOPMENT_ONLY',
                      ownerApproved=True,independentGoldApprovals=0,controls=[
                          dict(name=name,decision='PASS',segments=['first','second','third']) for name in ('N5','N6')])
        low=prepare(source,auth,variant='REASONING_LOW')
        expanded=prepare(low,auth,variant='TARGET_WIRE',source_arm='CANDIDATE',owner_controls=controls,
                         candidate_only=True,max_completion_tokens=8000)
        reports=[dict(authorizationHash=digest(auth),status='PARTIAL',reservedCostUsd='.01',trial=i) for i in range(5)]
        plan=dict(schemaVersion='candidate-support-salience-probe-1',sourceArtifactHash=expanded['artifactHash'],
                  diagnosticOnly=True,runtimeUseAuthorized=False,quotes=[dict(segmentId=sid,quote=text)
                    for sid,text in [('stt-2229','earlier question'),('stt-2233','earlier response')]])
        probe=prepare_salience(expanded,auth,reports,plan)
        self.assertEqual(len(probe['requests']),9)
        self.assertTrue(probe['diagnosticOnly'])
        self.assertEqual(next(c['payload']['raw'] for c in probe['cases'] if c['name']=='B'),payload['raw'])
        with tempfile.TemporaryDirectory() as tmp:
            result=execute(probe,Path(tmp)/'datasets'/'run','fake',response)
        self.assertEqual(len(result['calls']),9)
        probe['cases'][0]['payload']['candidates'][0]['proposedEvidence'][0]['quote']='invented'
        with self.assertRaises(ValueError): validate(probe)

    def test_expanded_candidate_only_regression_reserves_reasoning_and_output_budget(self):
        from run_boundary_comparison import reserve
        controls=dict(schemaVersion='owner-closer-normal-boundaries-1',reviewStatus='OWNER_DEVELOPMENT_ONLY',
                      ownerApproved=True,independentGoldApprovals=0,controls=[
                          dict(name=name,decision='PASS',segments=['first','second','third']) for name in ('N5','N6')])
        low=prepare(*inputs(),variant='REASONING_LOW')
        trial=prepare(low,inputs()[1],variant='TARGET_WIRE',source_arm='CANDIDATE',owner_controls=controls,
                      candidate_only=True,max_completion_tokens=8000)
        validate(trial)
        self.assertEqual(len(trial['requests']),27)
        for row in trial['requests']:
            self.assertEqual(str(reserve(row['body'],trial['config'])[1]),row['reservedCostUsd'])
            self.assertEqual(row['body']['max_completion_tokens'],8000)
            self.assertEqual(row['arm'],'CANDIDATE')
        with tempfile.TemporaryDirectory() as tmp:
            result=execute(trial,Path(tmp)/'datasets'/'run','fake',response)
        self.assertEqual(len(result['calls']),27)
        self.assertEqual(result['trialDesign'],'CANDIDATE_ONLY_REGRESSION')
        trial['cases'][-1]['payload']['raw'][0]['text']='changed'
        with self.assertRaises(ValueError): validate(trial)
        controls['ownerApproved']=False
        with self.assertRaises(ValueError): prepare(low,inputs()[1],owner_controls=controls)

    def test_wire_trial_keeps_low_and_does_not_repair_legacy_responses(self):
        low=prepare(*inputs(),variant='REASONING_LOW')
        trial=prepare(low,inputs()[1],variant='TARGET_WIRE',source_arm='CANDIDATE')
        validate(trial)
        def legacy(key,body):
            import json
            result=response(key,body)
            parsed=json.loads(result['choices'][0]['message']['content'])
            parsed['verifications'][0]['assessment']['targetRelation']=None
            result['choices'][0]['message']['content']=json.dumps(parsed)
            return result
        with tempfile.TemporaryDirectory() as tmp:
            result=execute(trial,Path(tmp)/'datasets'/'run','fake',legacy)
        for call in result['calls']:
            if call['arm']=='CANDIDATE':
                self.assertEqual(call['status'],'PYTHON_WIRE_REJECTED')
                self.assertNotIn('output',call)
                self.assertIn('wireOutput',call)
        trial['requests'][1]['outputAdapter']=None
        with self.assertRaises(ValueError): validate(trial)

    def test_followup_isolates_reading_order_on_low_not_combined_with_reasoning(self):
        low=prepare(*inputs(),variant='REASONING_LOW')
        trial=prepare(low,inputs()[1],source_arm='CANDIDATE')
        validate(trial)
        for r in trial['requests']:
            self.assertEqual(r['body']['reasoning_effort'],'low')
            self.assertNotIn('temperature',r['body'])
        self.assertEqual(trial['sourceArtifactHash'],low['artifactHash'])

    def test_reasoning_comparison_preserves_prompt_and_removes_sampling(self):
        artifact=prepare(*inputs(),variant='REASONING_LOW')
        validate(artifact)
        for a,b in zip(artifact['requests'][::2],artifact['requests'][1::2]):
            base=a if a['arm']=='BASELINE' else b
            candidate=b if a['arm']=='BASELINE' else a
            self.assertEqual(base['body']['messages'],candidate['body']['messages'])
            self.assertEqual(candidate['body']['reasoning_effort'],'low')
            self.assertNotIn('temperature',candidate['body'])
        artifact['requests'][1]['body']['temperature']=.1
        with self.assertRaises(ValueError): validate(artifact)

    def test_paired_repetition_preserves_raw_and_contract(self):
        args=inputs(); original=copy.deepcopy(args)
        artifact=prepare(*args); validate(artifact)
        self.assertEqual(args,original)
        self.assertEqual(len(artifact['requests']),42)
        self.assertEqual(artifact['remainingBudgetUsd'],'1.00')
        for r in artifact['requests']:
            if r['arm']=='CANDIDATE': self.assertIn(NEW,r['body']['messages'][0]['content'])
        with tempfile.TemporaryDirectory() as tmp:
            result=execute(artifact,Path(tmp)/'datasets'/'run','fake',response)
        self.assertEqual(len(result['calls']),42)
        self.assertEqual(result['authorizationHash'],artifact['authorizationHash'])
        next_trial=prepare(*args,prior_reports=[result])
        self.assertLess(float(next_trial['remainingBudgetUsd']),1)

    def test_duplicate_pair_contract_change_and_budget_reset_fail_before_transport(self):
        for mode in ('pair','contract','budget','negative'):
            artifact=prepare(*inputs())
            if mode=='pair': artifact['requests'][1]=copy.deepcopy(artifact['requests'][0])
            elif mode=='contract': artifact['requests'][1]['body']['max_completion_tokens']=100
            else:
                artifact['priorReservationLedger']=[dict(reportHash='prior',reservedCostUsd='-.1' if mode=='negative' else '.1')]
                if mode=='negative': artifact['remainingBudgetUsd']='1.1'
            artifact['artifactHash']=digest({k:v for k,v in artifact.items() if k!='artifactHash'})
            with tempfile.TemporaryDirectory() as tmp,self.assertRaises(ValueError):
                execute(artifact,Path(tmp)/'datasets'/'run','fake',lambda *a:self.fail('paid call'))

    def test_prompt_rejects_ambiguous_original_and_budget_cannot_be_reauthorized(self):
        for value in ('none',OLD+OLD):
            with self.assertRaises(ValueError): prompt(value)
        args=list(inputs()); args[1]['additionalBudgetUsd']='2.00'
        with self.assertRaises(ValueError): prepare(*args)
