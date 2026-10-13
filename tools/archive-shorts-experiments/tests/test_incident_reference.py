import copy
import json
from pathlib import Path
import sys
import unittest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from build_incident_reference import build_card,project,preview
from fixed_review_suite import digest
from freeze_context_retrieval_view import freeze as migrate
from test_case_context_ablation import fixture as source_fixture


def fixture():
    frozen,base,_=source_fixture()
    from issue_scoped_review import hydrate_selection
    for j in frozen['jobs']:
        p=json.loads(j['baseBody']['messages'][1]['content'])
        for i,r in enumerate(p['raw']):r.update(startMs=i*1000,endMs=i*1000+500)
        j['baseBody']['messages'][1]['content']=json.dumps(p,ensure_ascii=False)
        j['fixedSelectedScope']=hydrate_selection(j['fixedSelectedScope']['selectionOutput'],p)
    for case in frozen['source']['cases']:
        for i,r in enumerate(case['payload']['raw']):r.update(startMs=i*1000,endMs=i*1000+500)
    frozen['artifactHash']=digest({k:v for k,v in frozen.items() if k!='artifactHash'})
    job=next(j for j in frozen['jobs'] if j['case']=='F2' and j['arm']=='BASELINE')
    payload=json.loads(job['baseBody']['messages'][1]['content'])
    rows=payload['raw']
    spec=dict(schemaVersion='incident-reference-spec-1',status='UNREVIEWED_DRAFT',humanApproved=False,
        runtimeEligible=False,trainingUseAuthorized=False,caseId='pisik-B',familyId='fixture-family',
        sourceCase='F2',sourceInputHash=digest(payload),sourceWindowMs=[min(r['startMs'] for r in rows),max(r['endMs'] for r in rows)],
        orderedGroups=[[r['id'] for r in rows]],normalControlCases=['F4','F5'],
        criticismHypotheses=['draft mechanism, not inferred speaker motive'],missingContext=['audio unavailable'])
    for e in base['examples']:e['familyId']='fixture-family'
    return frozen,migrate(base)[0],spec


class IncidentReferenceTests(unittest.TestCase):
    def test_approved_card_and_changed_frozen_refused(self):
        f,b,s=fixture();card=build_card(f,s)
        bad=copy.deepcopy(card);bad['humanApproved']=True
        with self.assertRaises(ValueError):project(b,bad)
        c=project(b,card);f['jobs'].pop()
        with self.assertRaises(ValueError):preview(f,b,c,card)

    def test_exact_saved_transcript_and_controls_retained(self):
        f,b,s=fixture();card=build_card(f,s)
        j=next(j for j in f['jobs'] if j['case']=='F2' and j['arm']=='BASELINE')
        self.assertEqual(card['utteranceEvidence'],json.loads(j['baseBody']['messages'][1]['content'])['raw'])
        self.assertEqual(len(card['normalControls']),2)
        self.assertFalse(card['originalAudioVerified'])
        self.assertFalse(card['commentMappingVerified'])

    def test_deleted_duplicate_or_reordered_quotes_and_approval_refused(self):
        for mode in ('omit','duplicate','order','approval','hash'):
            f,b,s=fixture()
            if mode=='omit':s['orderedGroups'][0].pop()
            if mode=='duplicate':s['orderedGroups'][0].append(s['orderedGroups'][0][0])
            if mode=='order':s['orderedGroups'][0].reverse()
            if mode=='approval':s['humanApproved']=True
            if mode=='hash':s['sourceInputHash']='changed'
            with self.subTest(mode=mode),self.assertRaises(ValueError):build_card(f,s)

    def test_projection_keeps_retrieval_rules_and_other_cases_unchanged(self):
        f,b,s=fixture();old=copy.deepcopy(b);c=project(b,build_card(f,s))
        self.assertEqual(b,old)
        self.assertEqual(c['guidelines'],b['guidelines'])
        self.assertEqual(c['examples'][0]['retrievalView'],b['examples'][0]['retrievalView'])
        self.assertEqual(c['examples'][1],b['examples'][1])

    def test_model_preview_is_offline_and_only_reference_context_changes(self):
        f,b,s=fixture();card=build_card(f,s);p=preview(f,b,project(b,card),card)
        self.assertFalse(p['executionEnabled']);self.assertEqual(p['apiCalls'],0)
        self.assertEqual(p['evaluationScope'],'KNOWN_CASE_REPRODUCTION_NOT_GENERALIZATION')
        for i in range(0,len(p['units']),2):
            a,c=p['units'][i:i+2]
            self.assertEqual(a['body']['messages'][1:],c['body']['messages'][1:])
