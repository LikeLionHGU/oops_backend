"""Three-repeat known-case reproduction trial; no runtime promotion or retries."""
import copy
from collections import Counter
from decimal import Decimal
from pathlib import Path

from bound_review_contract import prepare_attempt
from build_incident_reference import build_card,project,preview
from experiment_costs import reserve
from fixed_review_suite import digest,read,require
from issue_scoped_recipes import BOUND_SCHEMA
from prepare_case_context_ablation import request_body
from run_target_focus_comparison import score_validated

ROOT=Path(__file__).resolve().parents[1]/'datasets/controversy'
AUTHORIZATION=dict(schemaVersion='fresh-experiment-budget-1',userAuthorized=True,
    additionalBudgetUsd='1.00',approvalId='2026-10-12-faithful-incident-reference-user-1',
    approvalText='예산 늘려 ! 1달러',scope='KNOWN_CASE_CARD_COMPARISON_NO_BUDGET_POOLING')


def source_inputs():
    directory=ROOT/'evaluation/v1/faithful-incident-reference-final-20261012'
    frozen=read(ROOT/'evaluation/v1/bc-rules-only-20261012/frozen.json')
    require(digest(frozen)=='8f77199e92f3f1528c588a708a6daeea667929a63d932b9781babf0adafebcff',
            'pinned source 137 required')
    base=read(directory/'baseline-runtime.json')
    spec=read(ROOT/'research/incident-references/pisik-B-spec-20261012.json')
    card=read(directory/'incident-card.json');candidate=read(directory/'candidate-runtime.json')
    require(card==build_card(frozen,spec) and candidate==project(base,card)
            and read(directory/'preview.json')==preview(frozen,base,candidate,card),
            'unchanged card/source/projection/preview required')
    return frozen,base,candidate,card


def freeze(source,base,candidate,card,authorization):
    require(authorization==AUTHORIZATION and candidate==project(base,card),
            'exact new approval and faithful card required')
    prepared=preview(source,base,candidate,card)
    cases=copy.deepcopy(source['source']['cases'])
    require([c['name'] for c in cases]==['F2','C','D','F4','F5'], 'fixed five conditions required')
    templates={j['case']:j for j in source['jobs'] if j['arm']=='BASELINE'}
    jobs=[]
    for repeat in range(1,4):
        for i,c in enumerate(cases):
            old=templates[c['name']]
            altered,visible=request_body(old['baseBody'],base,candidate,card['caseId'])
            bodies=dict(BASELINE=old['baseBody'],CANDIDATE=altered)
            for arm in (('BASELINE','CANDIDATE') if (repeat+i)%2 else ('CANDIDATE','BASELINE')):
                body=copy.deepcopy(bodies[arm]);scope=copy.deepcopy(old['fixedSelectedScope'])
                request=prepare_attempt(body,scope,'freeze-preview',judgment_variant='CLOSED_SCOPE')['requestBody']
                tokens,cost=reserve(request,source['config'])
                jobs.append(dict(case=c['name'],arm=arm,repeat=repeat,baseBody=body,
                    baseRequestHash=digest(body),fixedSelectedScope=scope,judgmentVariant='CLOSED_SCOPE',
                    referenceChanged=bool(visible),stages=[dict(stage='JUDGE',body=request,
                    requestHash=digest(request),reservedInputTokensHeuristic=tokens,reservedCostUsd=str(cost))]))
    total=sum(Decimal(j['stages'][0]['reservedCostUsd']) for j in jobs)
    require(total<=Decimal(authorization['additionalBudgetUsd']), 'full trial exceeds separate approval')
    result=dict(schemaVersion=BOUND_SCHEMA,incidentReferenceStudy='FAITHFUL_CARD_THREE_REPEATS_1',
        previewHash=digest(prepared),sourceFrozenHash=digest(source),baseArchiveHash=digest(base),
        candidateArchiveHash=digest(candidate),cardHash=digest(card),source=dict(cases=cases),
        config=copy.deepcopy(source['config']),authorization=copy.deepcopy(authorization),
        authorizationHash=digest(authorization),additionalFundingHash=digest(authorization),
        availableBudgetUsd=authorization['additionalBudgetUsd'],reservedCostUsd=str(total),
        remainingBudgetUsd=str(Decimal(authorization['additionalBudgetUsd'])-total),jobs=jobs,
        plannedUnits=30,maxApiCalls=30,repetitions=3,repetitionsPerCondition=3,
        requestTimeoutSeconds=90,maxConcurrency=1,reportSerialization='bundle-1',
        executionEnabled=True,budgetSufficient=True,normalControlsIncluded=True,
        automaticSelectionEvaluated=False,fullPipelineReplayed=False,runtimeChanged=False,
        qualityGateMet=None,semanticQualityGateMet=None,
        evaluationScope='KNOWN_CASE_REPRODUCTION_NOT_GENERALIZATION')
    result['artifactHash']=digest(result)
    return result


def prepare():return freeze(*source_inputs(),AUTHORIZATION)
def validate(frozen):
    if frozen.get('stabilizationStep')=='ANCHOR_FOCUS_1':
        from run_anchor_stability_comparison import validate as validate_anchor
        validate_anchor(frozen)
    elif frozen.get('stabilizationStep')=='CONFIRMATION_1':
        from confirm_incident_stability import validate as validate_confirmation
        validate_confirmation(frozen)
    elif 'stabilizationStep' in frozen:
        from stabilize_incident_reference import validate as validate_stabilization
        validate_stabilization(frozen)
    else:
        require(frozen==prepare(),'incident reference trial changed')


def score(frozen,report,audit):
    validate(frozen)
    result=score_validated(frozen,report,audit)
    summary=[]
    for c in frozen['source']['cases']:
        for arm in ('BASELINE','CANDIDATE'):
            rows=[r for r in result['results'] if r['case']==c['name'] and r['arm']==arm]
            summary.append(dict(case=c['name'],arm=arm,observations=len(rows),
                decisions=dict(Counter(r['assessment']['decision'] if r['assessment'] else 'CONTRACT_REJECTED' for r in rows)),
                accepted=sum(r['accepted'] for r in rows),semanticIssueMatched=None))
    result.update(schemaVersion='incident-reference-score-1',summary=summary,
        evaluationScope='KNOWN_CASE_REPRODUCTION_NOT_GENERALIZATION',independentGoldApprovals=0)
    return result
