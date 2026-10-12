"""Fresh funded full-raw vs closed-view comparison; no runtime promotion."""
import argparse
import copy
import json
from decimal import Decimal
from pathlib import Path

from fixed_review_suite import digest,read,require,write_private
from run_boundary_comparison import reserve
from issue_scoped_recipes import BOUND_SCHEMA
from scoped_judgment_contract import judgment_request
from bound_review_contract import validate_receipt

AUTHORIZATION=dict(schemaVersion='scoped-paired-budget-1',additionalBudgetUsd='1.00',userAuthorized=True,
    previousReservedUsd='3.9618824',previousRemainingUsd='0.0211090')
SOURCE_HASH='9683e388f9a8d1ae0f8a3a2eb2f6a205626271ee1bf3e419a7abb12f1425c403'
FULL_SOURCE_HASHES=dict(frozen='9f7a89523d03cdb62397acfb2fa05705ada3d3d072f48a7cd562d6a4c27d89ea',
    report='7916b797caba31677dbb528fa3d2bbdb49a42817d72e7c306291ad2e24a68c35',
    audit='a0348eff5ecad26d1c258d91464c9b2cb7c2f61369e411d8a685340acff34fca',
    score='578370cfbd74e48920f34acce7e4696e735220646b786922c62be44cf3cfc3a0')
FULL_SEED_HASH='09c35f54038d50606eef64e4a47b2a52c4b7ecbd803a7f5d9a0a145b219f8622'
FIRST_REPORT_HASH='1ebd67a04ed6b2c078701b531782a751e5a013e38fe1198cbcb92d3e7f1b978b'


def prepare(source,authorization):
    from issue_scoped_recipes import validate as validate_source
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
    expected=(prepare_full(frozen['sourceFrozen'],frozen['regressionSeed'],frozen['priorComparison'],frozen['authorization'])
        if 'regressionSeed' in frozen else prepare(frozen['sourceFrozen'],frozen['authorization']))
    require(frozen==expected, 'paired scope comparison changed')


def build_full_seed(frozen,report,audit,scored):
    from score_issue_scoped_comparison import score as score_source
    require(frozen['artifactHash']==FULL_SOURCE_HASHES['frozen'] and digest(report)==FULL_SOURCE_HASHES['report']
        and digest(audit)==FULL_SOURCE_HASHES['audit'] and digest(scored)==FULL_SOURCE_HASHES['score']
        and score_source(frozen,report,audit)==scored, 'audited full source required')
    records=[]
    for unit in report['calls']:
        stage=unit['stages'][1];ticket=stage['localAttempt']
        validate_receipt(stage['boundReceipt'],ticket)
        records.append(dict(case=unit['case'],repeat=unit['repeat'],baseBody=copy.deepcopy(ticket['baseRequest']),
            selectedScope=copy.deepcopy(ticket['selectedScope']),sourceAttemptHash=ticket['attemptHash']))
    return dict(schemaVersion='full-scoped-regression-seed-1',hashes=copy.deepcopy(FULL_SOURCE_HASHES),
        cases=copy.deepcopy(frozen['source']['cases']),config=copy.deepcopy(frozen['config']),records=records)


def prepare_full(first_source,seed,prior_report,authorization):
    from issue_scoped_recipes import bound_judgment_request
    if first_source.get('schemaVersion')=='fixed-source-budget-link-1':
        first_source=restore_source(first_source)
    first=prepare(first_source,authorization)
    require(digest(seed)==FULL_SEED_HASH and seed['hashes']==FULL_SOURCE_HASHES
        and seed['config']==first['config'] and len(seed['records'])==27
        and {c['name'] for c in seed['cases']}=={'B','C','D','N1','N2','N3','N4','N5','N6'},
        'pinned nine-case regression seed required')
    fields=('schemaVersion','artifactHash','authorizationHash','additionalFundingHash','status','runKind',
            'reservedCostUsd','consumedReservationUsd','apiCalls','unknownCostAttempts')
    expected=dict(schemaVersion=BOUND_SCHEMA,artifactHash=first['artifactHash'],authorizationHash=first['authorizationHash'],
        additionalFundingHash=first['additionalFundingHash'],status='COMPLETED_PENDING_JAVA_AUDIT',runKind='LIVE',
        reservedCostUsd=first['reservedCostUsd'],consumedReservationUsd=first['reservedCostUsd'],apiCalls=24,unknownCostAttempts=0)
    require(all(prior_report.get(k)==v for k,v in expected.items()),'complete prior live reservation required')
    meta={k:prior_report[k] for k in fields}
    meta['sourceReportHash']=prior_report.get('sourceReportHash',digest(prior_report))
    require(meta['sourceReportHash']==FIRST_REPORT_HASH,'prior comparison provenance mismatch')
    cases={c['name']:c['payload'] for c in seed['cases']}
    require({(r['case'],r['repeat']) for r in seed['records']}=={(c,n) for c in cases for n in range(1,4)}
        and all(json.loads(r['baseBody']['messages'][1]['content'])==cases[r['case']] for r in seed['records']),
        'complete unique source inputs required')
    jobs=[]
    for i,r in enumerate(seed['records']):
        for arm in (('BASELINE','CANDIDATE') if (i+r['repeat'])%2 else ('CANDIDATE','BASELINE')):
            mode='RELATION_CHECKLIST' if arm=='BASELINE' else 'CLOSED_SCOPE'
            body=bound_judgment_request({},r['baseBody'],r['selectedScope'],variant=mode)
            tokens,cost=reserve(body,seed['config'])
            jobs.append(dict(case=r['case'],repeat=r['repeat'],arm=arm,judgmentVariant=mode,
                baseBody=copy.deepcopy(r['baseBody']),baseRequestHash=digest(r['baseBody']),
                fixedSelectedScope=copy.deepcopy(r['selectedScope']),stages=[dict(stage='JUDGE',body=body,
                    requestHash=digest(body),reservedInputTokensHeuristic=tokens,reservedCostUsd=str(cost))]))
    total=sum(Decimal(j['stages'][0]['reservedCostUsd']) for j in jobs)
    available=Decimal(authorization['additionalBudgetUsd'])-Decimal(first['reservedCostUsd'])
    require(total<=available,'full regression exceeds remaining authorization')
    result=dict(schemaVersion=BOUND_SCHEMA,scopedPaidComparison='FULL_RAW_VS_CLOSED_REGRESSION_2',
        sourceFrozen=compact_source(first_source),sourceFrozenHash=first_source['artifactHash'],regressionSeed=copy.deepcopy(seed),
        source=dict(cases=copy.deepcopy(seed['cases'])),config=copy.deepcopy(seed['config']),
        authorization=copy.deepcopy(authorization),authorizationHash=digest(authorization),
        additionalFunding=copy.deepcopy(authorization),additionalFundingHash=digest(authorization),
        priorComparison=meta,jobs=jobs,plannedUnits=54,maxApiCalls=54,repetitions=3,requestTimeoutSeconds=90,maxConcurrency=3,
        reportSerialization='bundle-1',
        reservedCostUsd=str(total),remainingBudgetUsd=str(available-total),
        priorBudgetRemainingUsd=authorization['previousRemainingUsd'],executionEnabled=True,budgetSufficient=True,
        selectionReplayed=False,fullPipelineReplayed=False,runtimeChanged=False,qualityGateMet=None)
    result['artifactHash']=digest(result)
    return result


def compact_source(source):
    # Keep the verified input seed and funding chain, not repeated job bodies.
    result=dict(schemaVersion='fixed-source-budget-link-1',seed=copy.deepcopy(source['seed']),
                artifactHash=source['artifactHash'])
    for key in ('retrialFunding','priorDiagnostic'):
        if key in source:result[key]=copy.deepcopy(source[key])
    return result


def restore_source(link):
    from fixed_scope_diagnostic import prepare as prepare_fixed,fund_retrial
    source=prepare_fixed(link['seed'])
    if 'retrialFunding' in link:
        source=fund_retrial(source,link['priorDiagnostic'],link['retrialFunding'])
    require(compact_source(source)==link,'compact prior source changed')
    return source


def score(frozen,report,audit,*,allow_partial=False):
    from fixed_scope_diagnostic import score as diagnostic_score
    return diagnostic_score(frozen,report,audit,allow_partial=allow_partial,paired=True)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('frozen','report','audit','output'):p.add_argument('--'+name,required=True)
    p.add_argument('--partial',action='store_true');a=p.parse_args()
    from private_report_bundle import load as load_report
    result=score(read(a.frozen),load_report(a.report),read(a.audit),allow_partial=a.partial)
    write_private(Path(a.output).resolve(),result)
    print(dict(summary=result['summary'],semanticQualityGateMet=None,runtimeUseAuthorized=False))
