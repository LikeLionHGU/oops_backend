"""Fresh funded full-raw vs closed-view comparison; no runtime promotion."""
import argparse
import copy
from decimal import Decimal
from pathlib import Path

from fixed_review_suite import digest,read,require,write_private
from run_boundary_comparison import reserve
from run_issue_scoped_comparison import BOUND_SCHEMA
from scoped_judgment_contract import judgment_request

AUTHORIZATION=dict(schemaVersion='scoped-paired-budget-1',additionalBudgetUsd='1.00',userAuthorized=True,
    previousReservedUsd='3.9618824',previousRemainingUsd='0.0211090')
SOURCE_HASH='9683e388f9a8d1ae0f8a3a2eb2f6a205626271ee1bf3e419a7abb12f1425c403'


def prepare(source,authorization):
    from run_issue_scoped_comparison import validate as validate_source
    require(source.get('fixedScopeStudy')=='JUDGE_ONLY_DIAGNOSTIC_1'
        and source['artifactHash']==SOURCE_HASH and authorization==AUTHORIZATION,
        'pinned same-scope source and separate approval required')
    validate_source(source)
    require(source['remainingBudgetUsd']==authorization['previousRemainingUsd']
        and source['plannedUnits']==12,'prior residual and four-condition source required')
    jobs=[]
    for i,old in enumerate(source['jobs']):
        arms=('BASELINE','CANDIDATE') if (i+old['repeat'])%2 else ('CANDIDATE','BASELINE')
        for arm in arms:
            job=copy.deepcopy(old)
            variant='RELATION_CHECKLIST' if arm=='BASELINE' else 'CLOSED_SCOPE'
            body=copy.deepcopy(old['stages'][0]['body']) if arm=='BASELINE' else judgment_request(
                old['baseBody'],old['fixedSelectedScope'],'CLOSED_SCOPE')
            tokens,cost=reserve(body,source['config'])
            job.update(arm=arm,judgmentVariant=variant,stages=[dict(stage='JUDGE',body=body,
                requestHash=digest(body),reservedInputTokensHeuristic=tokens,reservedCostUsd=str(cost))])
            jobs.append(job)
    total=sum(Decimal(j['stages'][0]['reservedCostUsd']) for j in jobs)
    require(total<=Decimal(authorization['additionalBudgetUsd']), 'comparison exceeds new separate budget')
    result=dict(schemaVersion=BOUND_SCHEMA,scopedPaidComparison='FULL_RAW_VS_CLOSED_1',
        sourceFrozen=copy.deepcopy(source),sourceFrozenHash=source['artifactHash'],source=copy.deepcopy(source['source']),
        config=copy.deepcopy(source['config']),authorization=copy.deepcopy(authorization),authorizationHash=digest(authorization),
        additionalFunding=copy.deepcopy(authorization),additionalFundingHash=digest(authorization),jobs=jobs,
        plannedUnits=24,maxApiCalls=24,repetitions=3,requestTimeoutSeconds=90,maxConcurrency=3,
        reservedCostUsd=str(total),remainingBudgetUsd=str(Decimal(authorization['additionalBudgetUsd'])-total),
        priorBudgetRemainingUsd=authorization['previousRemainingUsd'],executionEnabled=True,budgetSufficient=True,
        selectionReplayed=False,fullPipelineReplayed=False,runtimeChanged=False,qualityGateMet=None)
    result['artifactHash']=digest(result)
    return result


def validate(frozen):
    require(frozen==prepare(frozen['sourceFrozen'],frozen['authorization']), 'paired scope comparison changed')


def score(frozen,report,audit,*,allow_partial=False):
    from fixed_scope_diagnostic import score as diagnostic_score
    return diagnostic_score(frozen,report,audit,allow_partial=allow_partial,paired=True)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('frozen','report','audit','output'):p.add_argument('--'+name,required=True)
    p.add_argument('--partial',action='store_true');a=p.parse_args()
    result=score(read(a.frozen),read(a.report),read(a.audit),allow_partial=a.partial)
    write_private(Path(a.output).resolve(),result)
    print(dict(summary=result['summary'],semanticQualityGateMet=None,runtimeUseAuthorized=False))
