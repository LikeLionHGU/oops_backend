"""Original/selected-quote anchor diagnostic plus unchanged controls, no adoption."""
import copy
from decimal import Decimal
from fixed_review_suite import read,digest,require
from private_report_bundle import load
from experiment_costs import reserve
from bound_review_contract import prepare_attempt
from reanchor_incident_scope import reanchor
from stabilize_incident_reference import ROOT

AUTHORIZATION=dict(schemaVersion='fresh-experiment-budget-1',userAuthorized=True,
    additionalBudgetUsd='1.00',approvalId='2026-10-12-anchor-stability-user-1',
    approvalText='1달러 추가 !',scope='ANCHOR_COMPARISON_AND_FROZEN_CONFIRMATION_NO_BUDGET_POOLING')


def source_inputs():
    from run_incident_reference_comparison import score
    p=ROOT/'faithful-compact-review-20261012';f=read(p/'frozen.json')
    require(read(p/'score.json')==score(f,load(p/'live-run/report-index.json'),read(p/'java-audit.json')),
        'audited compact parent required')
    return f


def freeze(source,authorization,anchor='stt-2233'):
    require(authorization==AUTHORIZATION,'exact separate additional approval required')
    templates={j['case']:j for j in source['jobs'] if j['arm']=='CANDIDATE' and j['repeat']==1}
    require(set(templates)=={'F2','C','D','F4','F5'},'five fixed conditions required')
    cases=copy.deepcopy(source['source']['cases'])
    b=next(c for c in cases if c['name']=='F2');original=copy.deepcopy(b)
    original['name']='F2-original';original['role']='PAIRED_ANCHOR_BASELINE'
    body,scope=reanchor(templates['F2']['baseBody'],templates['F2']['fixedSelectedScope'],anchor)
    import json
    b['payload']=json.loads(body['messages'][1]['content']);b['role']='ANCHOR_CANDIDATE'
    templates['F2-original']=copy.deepcopy(templates['F2'])
    templates['F2']=dict(baseBody=body,baseRequestHash=digest(body),fixedSelectedScope=scope)
    cases.insert(0,original);jobs=[]
    for repeat in range(1,4):
        order=cases if repeat%2 else [cases[1],cases[0],*cases[2:]]
        for case in order:
            old=templates[case['name']];request=prepare_attempt(old['baseBody'],old['fixedSelectedScope'],
                'freeze-preview',judgment_variant='COMPACT_REVIEW_CLOSED_SCOPE')['requestBody']
            tokens,cost=reserve(request,source['config'])
            jobs.append(dict(case=case['name'],arm='BASELINE' if case['name']=='F2-original' else 'CANDIDATE',
                repeat=repeat,baseBody=copy.deepcopy(old['baseBody']),baseRequestHash=old['baseRequestHash'],
                fixedSelectedScope=copy.deepcopy(old['fixedSelectedScope']),judgmentVariant='COMPACT_REVIEW_CLOSED_SCOPE',
                stages=[dict(stage='JUDGE',body=request,requestHash=digest(request),
                    reservedInputTokensHeuristic=tokens,reservedCostUsd=str(cost))]))
    total=sum(Decimal(j['stages'][0]['reservedCostUsd']) for j in jobs)
    require(total<=Decimal(authorization['additionalBudgetUsd']),'full diagnostic exceeds separate approval')
    result=copy.deepcopy(source);result.pop('artifactHash')
    result.update(stabilizationStep='ANCHOR_FOCUS_1',incidentReferenceStudy='FAITHFUL_ANCHOR_FOCUS_1',
        sourceTrialHash=digest(source),source=dict(cases=cases),previewHash=digest(jobs),jobs=jobs,
        authorization=copy.deepcopy(authorization),authorizationHash=digest(authorization),
        additionalFundingHash=digest(authorization),availableBudgetUsd=authorization['additionalBudgetUsd'],
        reservedCostUsd=str(total),remainingBudgetUsd=str(Decimal(authorization['additionalBudgetUsd'])-total),
        plannedUnits=18,maxApiCalls=18,predeclaredCandidateGate='ALL_15_OWNER_DECISIONS_AND_JAVA_CONTRACTS',
        qualityGateMet=None,semanticQualityGateMet=None,runtimeChanged=False)
    result['artifactHash']=digest(result)
    return result


def prepare():return freeze(source_inputs(),AUTHORIZATION)
def validate(frozen):require(frozen==prepare(),'anchor diagnostic changed')
