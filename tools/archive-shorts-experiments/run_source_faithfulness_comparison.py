"""Single-block explanation boundary comparison under a separate fresh approval."""
import copy
from decimal import Decimal
from fixed_review_suite import read,digest,require
from private_report_bundle import load
from bound_review_contract import prepare_attempt
from experiment_costs import reserve
from stabilize_incident_reference import ROOT

AUTHORIZATION=dict(schemaVersion='fresh-experiment-budget-1',userAuthorized=True,
    additionalBudgetUsd='1.00',approvalId='2026-10-13-source-faithfulness-user-1',
    approvalText='1달러승인',scope='SOURCE_FAITHFULNESS_AND_AUTOMATIC_SELECTION_DIAGNOSTICS_NO_BUDGET_POOLING')


def source_inputs():
    from run_incident_reference_comparison import score
    from confirm_incident_stability import gate
    p=ROOT/'faithful-explicit-pass-confirmation-20261013';f=read(p/'frozen.json')
    s=score(f,load(p/'live-run/report-index.json'),read(p/'java-audit.json'))
    require(s==read(p/'score.json') and gate(s),'audited successful parent required')
    return f


def freeze(source,authorization):
    require(authorization==AUTHORIZATION,'exact separate fresh approval required')
    templates={j['case']:j for j in source['jobs'] if j['repeat']==1 and j['arm']=='CANDIDATE'}
    require(len(templates)==9 and source['config']['maxCompletionTokens']==3000,'fixed nine-condition source required')
    jobs=[]
    for repeat in range(1,4):
        for i,c in enumerate(source['source']['cases']):
            old=templates[c['name']]
            for arm in (('BASELINE','CANDIDATE') if (repeat+i)%2 else ('CANDIDATE','BASELINE')):
                variant='EXPLICIT_PASS_CLOSED_SCOPE' if arm=='BASELINE' else 'SOURCE_FAITHFUL_CLOSED_SCOPE'
                request=prepare_attempt(old['baseBody'],old['fixedSelectedScope'],'freeze-preview',judgment_variant=variant)['requestBody']
                tokens,cost=reserve(request,source['config'])
                jobs.append(dict(case=c['name'],arm=arm,repeat=repeat,baseBody=copy.deepcopy(old['baseBody']),
                    baseRequestHash=old['baseRequestHash'],fixedSelectedScope=copy.deepcopy(old['fixedSelectedScope']),
                    judgmentVariant=variant,stages=[dict(stage='JUDGE',body=request,requestHash=digest(request),
                        reservedInputTokensHeuristic=tokens,reservedCostUsd=str(cost))]))
    total=sum(Decimal(j['stages'][0]['reservedCostUsd']) for j in jobs)
    require(total<=Decimal(authorization['additionalBudgetUsd']),'complete comparison exceeds separate approval')
    result=copy.deepcopy(source);result.pop('artifactHash');result.pop('confirmationParentStep',None);result.pop('sourceScoreHash',None)
    result.update(stabilizationStep='SOURCE_FAITHFUL_1',incidentReferenceStudy='SOURCE_FAITHFUL_1',
        sourceTrialHash=digest(source),jobs=jobs,previewHash=digest(jobs),plannedUnits=54,maxApiCalls=54,
        authorization=copy.deepcopy(authorization),authorizationHash=digest(authorization),additionalFundingHash=digest(authorization),
        availableBudgetUsd=authorization['additionalBudgetUsd'],reservedCostUsd=str(total),
        remainingBudgetUsd=str(Decimal(authorization['additionalBudgetUsd'])-total),
        predeclaredCandidateGate='ALL_27_OWNER_DECISIONS_AND_JAVA_CONTRACTS_AND_SEPARATE_EXPLANATION_REVIEW',
        qualityGateMet=None,semanticQualityGateMet=None,runtimeChanged=False)
    result['artifactHash']=digest(result);return result


def prepare():return freeze(source_inputs(),AUTHORIZATION)
def validate(frozen):require(frozen==prepare(),'source faithfulness comparison changed')
