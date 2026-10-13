import copy
import json
from decimal import Decimal
from pathlib import Path
import sys
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from context_boundary_packet import prepare, validate, difference
from test_context_reselection import initial


def fixture():
    t,r=initial('PASS'); normal,nr=initial('PASS')
    records=[dict(id='review-source',attempt=t,receipt=r),dict(id='normal-source',attempt=normal,receipt=nr)]
    c=t['originalInput']['candidates'][0]
    selection=dict(candidateId=c['candidateId'],anchorId=c['anchorId'],issueSummary='diagnostic only',
        selections=[dict(segmentId=c['anchorId'],role='PRIMARY',spanId='FULL'),
                    dict(segmentId=c['anchorId'],role='TARGET',spanId='w0-1'),
                    dict(segmentId='b',role='CONTEXT',spanId='FULL'),
                    dict(segmentId='other',role='CONTEXT',spanId='FULL')])
    preserved=[t['selectedScope']['evidence'][2]]
    spec=dict(schemaVersion='context-boundary-specification-1',conditions=[
        dict(id='review-source',mode='ASSISTANT_DIAGNOSTIC_SELECTION',candidateSelection=selection,
             preservedEvidence=preserved,developmentExpectedDecision='REVIEW_REQUIRED',labelStatus='OWNER_DEVELOPMENT_ONLY'),
        dict(id='normal-source',mode='UNCHANGED_NORMAL_CONTROL',candidateSelection=None,
             preservedEvidence=preserved,developmentExpectedDecision='PASS',labelStatus='OWNER_DEVELOPMENT_ONLY')])
    config=dict(maxCompletionTokens=8000,pricing=dict(inputUsdPerMillion='0.1',outputUsdPerMillion='0.5'))
    return records,spec,config


class BoundaryPacketTests(unittest.TestCase):
    def test_originals_unchanged_labels_server_only_and_calls_disabled(self):
        records,spec,config=fixture(); before=copy.deepcopy((records,spec,config))
        p=prepare(records,spec,config);validate(p,records,spec)
        self.assertEqual((records,spec,config),before)
        self.assertEqual(p['apiCalls'],0);self.assertEqual(p['reservedCostUsd'],'0')
        self.assertFalse(p['executionEnabled']);self.assertFalse(p['automaticSelectionEvaluated'])
        self.assertIsNone(p['qualityGateMet']);self.assertFalse(p['trainingUseAuthorized'])
        for request in p['requests']:
            incoming=json.loads(request['attempt']['requestBody']['messages'][1]['content'])
            self.assertNotIn('developmentExpectedDecision',incoming)
            self.assertNotIn('labelStatus',incoming);self.assertNotIn('original',incoming)
        total=sum(Decimal(r['plannedCostUpperEstimateUsd']) for r in p['requests'])
        self.assertEqual(total,Decimal(p['plannedCostUpperEstimateUsd']))

    def test_prompt_reference_unchanged_and_normal_body_identical(self):
        records,spec,config=fixture();p=prepare(records,spec,config)
        for record in records:
            bodies=[r['attempt']['requestBody'] for r in p['requests'] if r['conditionId']==record['id']]
            self.assertEqual(bodies[0]['messages'][0],bodies[1]['messages'][0])
            if record['id']=='normal-source':self.assertEqual(bodies[0],bodies[1])
        self.assertEqual(p['views'][0]['difference']['added'],[
            dict(segmentId='a',role='TARGET',quote='이 장소의'),
            dict(segmentId='other',role='CONTEXT',quote='다른 메뉴를 평가한다')])
        self.assertEqual(p['views'][0]['difference']['removed'],[
            dict(segmentId='a',role='TARGET',quote='이 장소')])
        self.assertEqual(p['views'][1]['difference']['added'],[])

    def test_preserved_normal_evidence_cannot_be_removed_or_manufactured(self):
        records,spec,config=fixture()
        for mode in ('removed','quote','no-explicit-preservation'):
            bad=copy.deepcopy(spec)
            if mode=='removed':bad['conditions'][0]['candidateSelection']['selections'].pop(2)
            if mode=='quote':bad['conditions'][0]['preservedEvidence'][0]['quote']='invented'
            if mode=='no-explicit-preservation':bad['conditions'][0]['preservedEvidence']=[]
            with self.subTest(mode=mode),self.assertRaises(ValueError):prepare(records,bad,config)

    def test_outside_source_wrong_anchor_unknown_span_and_duplicate_specs_rejected(self):
        records,spec,config=fixture()
        for mode in ('anchor','outside','span','duplicate'):
            bad=copy.deepcopy(spec);s=bad['conditions'][0]['candidateSelection']
            if mode=='anchor':s['anchorId']='other'
            if mode=='outside':s['selections'][2]['segmentId']='outside'
            if mode=='span':s['selections'][1]['spanId']='made-up'
            if mode=='duplicate':bad['conditions'].append(copy.deepcopy(bad['conditions'][0]))
            with self.subTest(mode=mode),self.assertRaises(ValueError):prepare(records,bad,config)

    def test_changed_source_receipt_and_packet_cannot_be_rehashed_into_approval(self):
        records,spec,config=fixture();p=prepare(records,spec,config)
        bad=copy.deepcopy(records);bad[0]['receipt']['modelOutput']['assessment']['reason']='rewritten'
        with self.assertRaises(ValueError):prepare(bad,spec,config)
        changed=copy.deepcopy(p);changed['executionEnabled']=True
        with self.assertRaises(ValueError):validate(changed,records,spec)

    def test_normal_control_never_relabelled_or_reselected(self):
        records,spec,config=fixture();bad=copy.deepcopy(spec)
        bad['conditions'][1]['developmentExpectedDecision']='REVIEW_REQUIRED'
        with self.assertRaises(ValueError):prepare(records,bad,config)
        bad=copy.deepcopy(spec);bad['conditions'][1]['candidateSelection']=bad['conditions'][0]['candidateSelection']
        with self.assertRaises(ValueError):prepare(records,bad,config)
