"""Frozen nine-condition confirmation; owner controls, not independent gold."""
import copy
import json
from decimal import Decimal
from bound_review_contract import prepare_attempt
from experiment_costs import reserve,positive
from fixed_review_suite import digest,read,require
from issue_scoped_review import hydrate_selection
from stabilize_incident_reference import ROOT,development_gate


def control_inputs():
    p=ROOT/'bcd-confirmed-review-packet'
    return {n:read(p/n) for n in ('model-inputs.json','manifest.json','owner-labels.json')}


def freeze(source,scored,packet,available):
    require(development_gate(scored), 'primary candidate must pass before confirmation')
    available=positive(available)
    require(available<=Decimal(source['remainingBudgetUsd']), 'no budget expansion')
    templates={j['case']:j for j in source['jobs'] if j['arm']=='CANDIDATE' and j['repeat']==1}
    primary={'F2','C','D','F4','F5'}
    extended=primary|{'Control-Absence','Control-Review','Control-Nostalgia','Control-SingleMenu'}
    require(set(templates) in (primary,extended), 'fixed primary or extended conditions required')
    variants={j['judgmentVariant'] for j in templates.values()}
    require(len(variants)==1,'one unchanged candidate required')
    variant=next(iter(variants));cases=copy.deepcopy([c for c in source['source']['cases'] if c['name']!='F2-original'])
    specs=[('Control-Absence','F2','이 동네'),('Control-Review','C','이 메뉴'),
           ('Control-Nostalgia','D',None),('Control-SingleMenu','C','선택 가능한 메뉴')]
    inputs={u['unitId']:u['input'] for u in packet['model-inputs.json']['units']}
    labels={r['unitId']:r for r in packet['owner-labels.json']['labels']}
    entries=packet['manifest.json']['cases'][3:]
    require(len(entries)==4, 'four previously confirmed normal controls required')
    if set(templates)==extended:
        require(source['controlPacketHash']==digest(packet),'existing confirmed controls changed')
        entries=[];specs=[]
    for entry,(name,parent,target) in zip(entries,specs,strict=True):
        inp=inputs[entry['unitId']];label=labels[entry['unitId']]
        require(entry['inputHash']==label['assessment']['inputHash']==digest(inp)
            and label['assessment']['developmentExpectedDecision']=='PASS'
            and label['reviewStatus']=='OWNER_DEVELOPMENT_ONLY'
            and inp['mode']=='SYNTHETIC_TEXT' and len(inp['segments'])==1
            and inp['segments'][0]['startMs'] is None and inp['segments'][0]['endMs'] is None,
            'unchanged owner-approved synthetic text required')
        body=copy.deepcopy(templates[parent]['baseBody'])
        payload=json.loads(body['messages'][1]['content']);text=inp['segments'][0]['text']
        row=dict(id='synthetic-control-1',text=text,startMs=0,endMs=1500,type='SPEECH')
        # These times exist only for the current Java replay adapter, not video evidence.
        candidate=dict(candidateId='candidate-1',anchorId=row['id'],segmentIds=[row['id']],
            axis='EXPRESSION_CONTENT' if target is None else 'TARGET_TREATMENT',
            hypothesisNotEvidence='선택한 표현의 게시 전 검토 여부를 확인한다.',
            proposedEvidence=[dict(segmentId=row['id'],quote=text)],
            contextLimited=False,contextExpanded=False)
        payload['raw']=[row];payload['candidates']=[candidate]
        body['messages'][1]['content']=json.dumps(payload,ensure_ascii=False)
        picks=[dict(segmentId=row['id'],start=0,end=len(text),role='PRIMARY')]
        if target is not None:
            start=text.index(target);picks.append(dict(segmentId=row['id'],start=start,
                end=start+len(target),role='TARGET'))
        scope=hydrate_selection(dict(candidateId='candidate-1',anchorId=row['id'],
            issueSummary='표현과 대상의 연결을 확인한다.',selections=picks),payload)
        templates[name]=dict(baseBody=body,baseRequestHash=digest(body),fixedSelectedScope=scope)
        cases.append(dict(name=name,payload=payload,developmentExpectation='PASS',
            labelStatus='OWNER_DEVELOPMENT_ONLY',sourceInputHash=digest(inp),
            sourceLabelHash=digest(label),inputMode='SYNTHETIC_TEXT',
            timestampsAreSyntheticAuditCoordinates=True))
    jobs=[]
    for repeat in range(1,4):
        for case in cases:
            old=templates[case['name']];request=prepare_attempt(old['baseBody'],old['fixedSelectedScope'],
                'freeze-preview',judgment_variant=variant)['requestBody']
            tokens,cost=reserve(request,source['config'])
            jobs.append(dict(case=case['name'],arm='CANDIDATE',repeat=repeat,
                baseBody=copy.deepcopy(old['baseBody']),baseRequestHash=old['baseRequestHash'],
                fixedSelectedScope=copy.deepcopy(old['fixedSelectedScope']),judgmentVariant=variant,
                stages=[dict(stage='JUDGE',body=request,requestHash=digest(request),
                    reservedInputTokensHeuristic=tokens,reservedCostUsd=str(cost))]))
    total=sum(Decimal(j['stages'][0]['reservedCostUsd']) for j in jobs)
    require(total<=available, 'complete confirmation exceeds remaining approval')
    result=copy.deepcopy(source);result.pop('artifactHash')
    result.update(stabilizationStep='CONFIRMATION_1',incidentReferenceStudy='FAITHFUL_CONFIRMATION_1',
        confirmationParentStep=source['stabilizationStep'],sourceTrialHash=digest(source),
        sourceScoreHash=digest(scored),controlPacketHash=digest(packet),source=dict(cases=cases),
        previewHash=digest(jobs),jobs=jobs,plannedUnits=27,maxApiCalls=27,
        availableBudgetUsd=str(available),reservedCostUsd=str(total),remainingBudgetUsd=str(available-total),
        predeclaredCandidateGate='ALL_27_OWNER_DECISIONS_AND_JAVA_CONTRACTS',
        semanticQualityGateMet=None,qualityGateMet=None,runtimeChanged=False)
    result['artifactHash']=digest(result)
    return result


def prepare(available,parent_step):
    from run_incident_reference_comparison import score
    from private_report_bundle import load
    names={'TARGET_FOCUS_1':'faithful-target-focus-20261012',
           'MECHANISM_FOCUS_1':'faithful-mechanism-focus-20261012',
           'COMPACT_REVIEW_1':'faithful-compact-review-20261012',
           'ANCHOR_FOCUS_1':'faithful-anchor-focus-20261012',
           'CHRONOLOGY_1':'faithful-chronology-20261012'}
    require(parent_step in names,'known confirmation parent required')
    p=ROOT/names[parent_step];source=read(p/'frozen.json')
    scored=score(source,load(p/'live-run/report-index.json'),read(p/'java-audit.json'))
    require(scored==read(p/'score.json'),'audited immutable parent required')
    return freeze(source,scored,control_inputs(),available)


def validate(frozen):
    require(frozen==prepare(frozen['availableBudgetUsd'],frozen['confirmationParentStep']),
        'confirmation changed')


def gate(score):
    rows=score['results']
    return len(rows)==27 and all(r['arm']=='CANDIDATE' and r['accepted']
        and r['matchedDevelopmentDecision'] is True for r in rows)
