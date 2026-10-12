"""Budget-bounded template correction and output cap bundle; no retries/promotion."""
import copy
from decimal import Decimal
from fixed_review_suite import read,digest,require
from private_report_bundle import load
from bound_review_contract import prepare_attempt
from experiment_costs import positive,reserve
from stabilize_incident_reference import ROOT


def source_inputs():
    from run_incident_reference_comparison import score
    p=ROOT/'faithful-chronology-confirmation-20261013';f=read(p/'frozen.json')
    require(read(p/'score.json')==score(f,load(p/'live-run/report-index.json'),read(p/'java-audit.json')),
        'audited complete confirmation required')
    return f


def freeze(source,available):
    available=positive(available)
    require(available<=Decimal(source['remainingBudgetUsd']),'no approval expansion')
    result=copy.deepcopy(source);result.pop('artifactHash');result.pop('confirmationParentStep',None)
    result.pop('sourceScoreHash',None);config=copy.deepcopy(source['config']);config['maxCompletionTokens']=3000
    jobs=[]
    for old in source['jobs']:
        require(old['arm']=='CANDIDATE','candidate-only unchanged nine conditions required')
        body=copy.deepcopy(old['baseBody']);body['max_completion_tokens']=3000
        request=prepare_attempt(body,old['fixedSelectedScope'],'freeze-preview',
            judgment_variant='EXPLICIT_PASS_CLOSED_SCOPE')['requestBody']
        tokens,cost=reserve(request,config)
        j=copy.deepcopy(old);j.update(baseBody=body,baseRequestHash=digest(body),judgmentVariant='EXPLICIT_PASS_CLOSED_SCOPE',
            stages=[dict(stage='JUDGE',body=request,requestHash=digest(request),
                reservedInputTokensHeuristic=tokens,reservedCostUsd=str(cost))]);jobs.append(j)
    require(len(jobs)==27,'nine conditions repeated three times required')
    total=sum(Decimal(j['stages'][0]['reservedCostUsd']) for j in jobs)
    require(2*total<=available,'primary plus unchanged confirmation must both fit')
    result.update(stabilizationStep='EXPLICIT_PASS_1',incidentReferenceStudy='FAITHFUL_EXPLICIT_PASS_1',
        sourceTrialHash=digest(source),config=config,jobs=jobs,previewHash=digest(jobs),
        availableBudgetUsd=str(available),reservedCostUsd=str(total),remainingBudgetUsd=str(available-total),
        minimumPrimaryAndConfirmationReserveUsd=str(2*total),
        predeclaredCandidateGate='ALL_27_OWNER_DECISIONS_AND_JAVA_CONTRACTS',
        qualityGateMet=None,semanticQualityGateMet=None,runtimeChanged=False)
    result['artifactHash']=digest(result)
    return result


def prepare(available):return freeze(source_inputs(),available)
def validate(frozen):require(frozen==prepare(frozen['availableBudgetUsd']),'explicit PASS trial changed')
