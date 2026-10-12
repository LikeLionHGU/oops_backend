"""Source-linked incident card alongside abstract patterns; offline only.

Preserves selected STT, temporal order, contrary context and synthetic controls.
The interpretation is a development hypothesis, not verified audience testimony.
"""
import copy
import json
from decimal import Decimal

from bound_review_contract import prepare_attempt
from build_review_guidelines import prompt_rules
from experiment_costs import reserve
from fixed_review_suite import digest, require
from prepare_case_context_ablation import request_body


def build_card(frozen, spec):
    require(frozen['artifactHash'] == digest({k:v for k,v in frozen.items() if k!='artifactHash'}),
            'unchanged source frozen required')
    require(spec['schemaVersion']=='incident-reference-spec-1' and spec['status']=='UNREVIEWED_DRAFT'
            and all(spec.get(k) is False for k in ('humanApproved','runtimeEligible','trainingUseAuthorized')),
            'unapproved research specification required')
    jobs=[j for j in frozen['jobs'] if j['case']==spec['sourceCase'] and j['arm']=='BASELINE']
    require(len(jobs)==1, 'unique baseline source required')
    payload=json.loads(jobs[0]['baseBody']['messages'][1]['content'])
    require(digest(payload)==spec['sourceInputHash'], 'source input changed')
    raw={r['id']:r for r in payload['raw']}
    require(len(raw)==len(payload['raw']), 'duplicate raw source IDs')
    ids=[sid for group in spec['orderedGroups'] for sid in group]
    require(1<=len(spec['orderedGroups'])<=8 and all(g for g in spec['orderedGroups'])
            and len(ids)==len(set(ids)) and set(ids)<=set(raw), 'unique source-linked groups required')
    rows=[raw[sid] for sid in ids]
    require(rows==sorted(rows,key=lambda r:(r['startMs'],r['endMs'],r['id'])), 'temporal order required')
    start,end=spec['sourceWindowMs']
    require(ids==[r['id'] for r in payload['raw'] if r['startMs']>=start and r['endMs']<=end],
            'all saved rows in declared window must be preserved')
    flow=['사례 STT 시간순 인용(원음 미확인, 인과 확정 아님): '+ ' → '.join('“'+raw[s]['text']+'”' for s in g)
          for g in spec['orderedGroups']]
    require(all(len(s)<=350 for s in flow), 'no truncation: regroup long source rows')
    hypotheses=spec['criticismHypotheses']
    limits=spec['missingContext']
    require(1<=len(hypotheses)<=16 and 1<=len(limits)<=8
            and all(isinstance(s,str) and 0<len(s.strip())<=350 for s in hypotheses+limits),
            'bounded interpretation and limitations required')
    controls=[]
    require(1<=len(spec['normalControlCases'])<=8
            and len(set(spec['normalControlCases']))==len(spec['normalControlCases']),
            'explicit unique normal controls required')
    for name in spec['normalControlCases']:
        cases=[c for c in frozen['source']['cases'] if c['name']==name]
        require(len(cases)==1 and cases[0]['developmentExpectation']=='PASS', 'existing normal control required')
        controls.append(dict(sourceCase=name,kind='SYNTHETIC_OWNER_DEVELOPMENT_CONTROL',
            segments=copy.deepcopy(cases[0]['payload']['raw'])))
    card=dict(schemaVersion='incident-reference-card-1',caseId=spec['caseId'],familyId=spec['familyId'],
        status='UNREVIEWED_DRAFT',sourceCase=spec['sourceCase'],sourceInputHash=digest(payload),originalAudioVerified=False,
        commentMappingVerified=False,humanApproved=False,runtimeEligible=False,trainingUseAuthorized=False,
        utteranceEvidence=copy.deepcopy(rows),normalControls=controls,
        interpretationBasis='USER_DEVELOPMENT_BOUNDARY_WITH_ASSISTANT_EXPLANATION_NOT_INDEPENDENT_GOLD',
        context=dict(coverage='SELECTED_EXCERPTS_NOT_FULL_TRANSCRIPT',flow=flow,
                     criticismHypotheses=copy.deepcopy(hypotheses),missingContext=copy.deepcopy(limits),
                     sourceInterpretations=[]))
    return card


def project(base,card):
    require(base.get('schemaVersion')=='review-guidelines-5' and base['humanValidated'] is False
            and base.get('status')=='WORKING_REFERENCE_NOT_VALIDATED',
            'frozen retrieval view required')
    require(card.get('schemaVersion')=='incident-reference-card-1'
            and card.get('status')=='UNREVIEWED_DRAFT'
            and all(card.get(k) is False for k in ('humanApproved','runtimeEligible','trainingUseAuthorized')),
            'unapproved incident card required')
    result=copy.deepcopy(base)
    found=[e for e in result['examples'] if e['id']==card['caseId'] and e['familyId']==card['familyId']]
    require(len(found)==1 and found[0].get('retrievalView'), 'existing indexed case required')
    found[0]['context']=copy.deepcopy(card['context'])
    result['version']='2026-10-12-faithful-incident-reference-1'
    result['contextDictionarySha256']=digest(dict(base=base['contextDictionarySha256'],card=card))
    require(prompt_rules(base,'SPEECH')==prompt_rules(result,'SPEECH'), 'abstract patterns unchanged')
    return result


def preview(frozen,base,candidate,card):
    require(frozen['artifactHash']==digest({k:v for k,v in frozen.items() if k!='artifactHash'}),
            'source frozen changed')
    sources=[j for j in frozen['jobs'] if j['case']==card['sourceCase'] and j['arm']=='BASELINE']
    require(len(sources)==1 and digest(json.loads(sources[0]['baseBody']['messages'][1]['content']))
            ==card['sourceInputHash'], 'card source input mismatch')
    require(candidate==project(base,card), 'faithful projection changed')
    units=[]
    for source in frozen['jobs']:
        if source['arm']!='BASELINE': continue
        body,visible=request_body(source['baseBody'],base,candidate,card['caseId'])
        for arm,b in [('BASELINE',source['baseBody']),('CANDIDATE',body)]:
            req=prepare_attempt(b,source['fixedSelectedScope'],'preview',judgment_variant='CLOSED_SCOPE')['requestBody']
            tokens,cost=reserve(req,frozen['config'])
            units.append(dict(case=source['case'],arm=arm,body=req,requestHash=digest(req),
                caseReferenceVisible=bool(visible),plannedReservationUsd=str(cost),inputEnvelope=tokens))
    require(len(units)==10, 'five paired inputs required')
    return dict(schemaVersion='incident-reference-preview-1',status='OFFLINE_NOT_EXECUTABLE',
        cardHash=digest(card),sourceFrozenHash=digest(frozen),baseArchiveHash=digest(base),
        candidateArchiveHash=digest(candidate),units=units,plannedCalls=10,
        distinctRequestBodies=len({u['requestHash'] for u in units}),
        plannedReservationUsd=str(sum(Decimal(u['plannedReservationUsd']) for u in units)),
        consumedReservationUsd='0',apiCalls=0,executionEnabled=False,runtimeEligible=False,
        qualityGateMet=None,evaluationScope='KNOWN_CASE_REPRODUCTION_NOT_GENERALIZATION')
