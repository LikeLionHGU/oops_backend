"""Fixed observed-scope JUDGE diagnostic; no prompt change or stable promotion.

The pinned private seed is derived from an audited experiment, not hand-built
evidence. Prior decisions stay administrative and never enter new requests.
Digest checks are reproducibility guards, not provider authentication.
"""
import argparse
import copy
from collections import Counter
from decimal import Decimal
import json
from pathlib import Path

from bound_review_contract import prepare_attempt, receive, validate_receipt, parse_response
from compare_recorded_candidates import audit_export
from compare_review_prompts import digest as audit_digest
from fixed_review_suite import digest, read, require, write_private
from run_boundary_comparison import reserve
from run_issue_scoped_comparison import BOUND_SCHEMA, BOUNDARY_FUNDING, bound_judgment_request

SOURCE_HASHES = dict(
    frozen='9f7a89523d03cdb62397acfb2fa05705ada3d3d072f48a7cd562d6a4c27d89ea',
    report='7916b797caba31677dbb528fa3d2bbdb49a42817d72e7c306291ad2e24a68c35',
    audit='a0348eff5ecad26d1c258d91464c9b2cb7c2f61369e411d8a685340acff34fca',
    score='578370cfbd74e48920f34acce7e4696e735220646b786922c62be44cf3cfc3a0')
SEED_HASH = '1c917f77273546fe1bf568b88f1fe7fb407144b987687f694ef297bf2fe4c251'
SPEC = [('B1', 'B', 1), ('B3', 'B', 3), ('N3', 'N3', 1), ('N4', 'N4', 1)]


def build_seed(frozen, report, audit, scored):
    from score_issue_scoped_comparison import score as prior_score
    require(frozen['artifactHash'] == SOURCE_HASHES['frozen']
            and digest(report) == SOURCE_HASHES['report']
            and digest(audit) == SOURCE_HASHES['audit'] and digest(scored) == SOURCE_HASHES['score'],
            'exact audited predecessor required')
    require(prior_score(frozen, report, audit) == scored and report['runKind'] == 'LIVE'
            and frozen['additionalFunding'] == BOUNDARY_FUNDING, 'live source and current funding required')
    records = []
    for name, case, repeat in SPEC:
        index = next(i for i,u in enumerate(report['calls'],1) if u['case']==case and u['repeat']==repeat)
        unit = report['calls'][index-1]
        stage = unit['stages'][1]
        require('boundReceipt' in stage and 'output' in unit, 'accepted source receipt required')
        records.append(dict(name=name, sourceCase=case, sourceRepeat=repeat, sourceIndex=index,
                            localAttempt=copy.deepcopy(stage['localAttempt']),
                            receipt=copy.deepcopy(stage['boundReceipt'])))
    return dict(schemaVersion='fixed-scope-source-seed-1', hashes=copy.deepcopy(SOURCE_HASHES),
                funding=copy.deepcopy(frozen['additionalFunding']), config=copy.deepcopy(frozen['config']),
                previousReservationUsd=frozen['reservedCostUsd'], remainingBudgetUsd=frozen['remainingBudgetUsd'],
                records=records)


def prepare(seed):
    require(digest(seed) == SEED_HASH and seed['hashes'] == SOURCE_HASHES, 'pinned source seed required')
    require(seed['funding'] == BOUNDARY_FUNDING and seed['previousReservationUsd'] == '0.4039344'
            and seed['remainingBudgetUsd'] == '0.0960656', 'unconsumed prior approval required')
    require([(r['name'],r['sourceCase'],r['sourceRepeat']) for r in seed['records']] == SPEC,
            'fixed diagnostic conditions required')
    cases, jobs = [], []
    for record in seed['records']:
        ticket, receipt = record['localAttempt'], record['receipt']
        validate_receipt(receipt, ticket)
        require(ticket['attemptId'] == f'{SOURCE_HASHES["frozen"]}-{record["sourceIndex"]:03d}'
                and ticket['judgmentVariant'] == 'RELATION_CHECKLIST', 'source invocation mismatch')
        base, scope = ticket['baseRequest'], ticket['selectedScope']
        body = bound_judgment_request(dict(judgmentVariant='RELATION_CHECKLIST'), base, scope)
        require(body == ticket['requestBody'], 'fixed scope request differs from observed source')
        require(base['model']=='gpt-6-luna' and base['reasoning_effort']=='low'
                and base['max_completion_tokens']==8000 and base['store'] is False
                and base['response_format']=={'type':'json_object'} and 'temperature' not in base,
                'unchanged model settings required')
        cases.append(dict(name=record['name'], payload=copy.deepcopy(ticket['originalInput']),
                          developmentExpectation='REVIEW_REQUIRED' if record['sourceCase']=='B' else 'PASS'))
    # Interleave conditions; no previous decision enters the request.
    for repeat in range(1,4):
        for record in seed['records']:
            ticket = record['localAttempt']
            body = copy.deepcopy(ticket['requestBody'])
            tokens, cost = reserve(body, seed['config'])
            jobs.append(dict(case=record['name'], arm='CANDIDATE', repeat=repeat,
                baseBody=copy.deepcopy(ticket['baseRequest']), baseRequestHash=digest(ticket['baseRequest']),
                fixedSelectedScope=copy.deepcopy(ticket['selectedScope']),
                stages=[dict(stage='JUDGE',body=body,requestHash=digest(body),
                    reservedInputTokensHeuristic=tokens,reservedCostUsd=str(cost))]))
    total = sum(Decimal(j['stages'][0]['reservedCostUsd']) for j in jobs)
    available = Decimal(seed['remainingBudgetUsd'])
    require(total<=available, 'fixed-scope trial exceeds remaining authorization')
    result = dict(schemaVersion=BOUND_SCHEMA,fixedScopeStudy='JUDGE_ONLY_DIAGNOSTIC_1',
        seed=copy.deepcopy(seed),seedHash=SEED_HASH,source=dict(cases=cases),
        config=copy.deepcopy(seed['config']),authorization=copy.deepcopy(seed['funding']),
        authorizationHash=digest(seed['funding']),additionalFunding=copy.deepcopy(seed['funding']),
        additionalFundingHash=digest(seed['funding']),judgmentVariant='RELATION_CHECKLIST',
        jobs=jobs,plannedUnits=12,maxApiCalls=12,repetitions=3,requestTimeoutSeconds=90,maxConcurrency=3,
        reservedCostUsd=str(total),remainingBudgetUsd=str(available-total),
        previousReservationUsd=seed['previousReservationUsd'],executionEnabled=True,budgetSufficient=True,
        selectionReplayed=False,fullPipelineReplayed=False,runtimeChanged=False,qualityGateMet=None)
    result['artifactHash']=digest(result)
    return result


def validate(frozen):
    require(frozen == prepare(frozen['seed']), 'fixed-scope frozen changed')


def score(frozen, report, audit):
    validate(frozen)
    mock = report.get('runKind') == 'MOCK_OFFLINE'
    require(report.get('runKind') in {'MOCK_OFFLINE','LIVE'}
        and report['artifactHash']==frozen['artifactHash']
        and report['authorizationHash']==frozen['authorizationHash']
        and report['additionalFundingHash']==frozen['additionalFundingHash']
        and report['status']==('MOCK_COMPLETE_PENDING_JAVA_AUDIT' if mock else 'COMPLETED_PENDING_JAVA_AUDIT')
        and report['cases']==frozen['source']['cases'] and len(report['calls'])==12
        and report['apiCalls']==(0 if mock else 12) and report['simulatedCalls']==(12 if mock else 0)
        and report['consumedReservationUsd']==('0' if mock else frozen['reservedCostUsd'])
        and report['reservedCostUsd']==frozen['reservedCostUsd'] and report['unknownCostAttempts']==0,
        'complete matched diagnostic report required')
    exported=audit_export(json.loads(json.dumps(report,ensure_ascii=False)))
    exported.update(trialSchemaVersion='recorded-flow-regression-1',repetitionsPerCondition=3)
    require(audit['sourceReportSha256']==audit_digest(exported), 'current Java audit mismatch')
    indexed={(r['case'],r['arm'],r['repeat']):r for r in audit['results']}
    require(len(indexed)==12 and len(audit['results'])==12
            and set(indexed)=={(j['case'],j['arm'],j['repeat']) for j in frozen['jobs']}, 'complete unique audit required')
    rows=[]; total_cost=Decimal(0)
    for index,(job,unit) in enumerate(zip(frozen['jobs'],report['calls']),1):
        require(all(job[k]==unit[k] for k in ('case','arm','repeat')) and 'error' not in unit
            and unit['selectedScope']==job['fixedSelectedScope'] and len(unit['stages'])==1
            and unit['requestHash']==job['baseRequestHash'], 'fixed scope/unit mismatch')
        stage=unit['stages'][0]; body=job['stages'][0]['body']
        ticket=prepare_attempt(job['baseBody'],job['fixedSelectedScope'],f'{frozen["artifactHash"]}-{index:03d}',
                               judgment_variant='RELATION_CHECKLIST')
        require(stage['stage']=='JUDGE' and stage['body']==body and stage['requestHash']==digest(body)
            and stage['localAttempt']==ticket and 'error' not in stage
            and stage['rawOutput']==parse_response(stage['providerResponse'],body['model']), 'stage provenance mismatch')
        usage=stage['providerResponse'].get('usage',{})
        require(stage['usage']==usage and all(type(usage.get(k)) is int and usage[k]>=0
            for k in ('prompt_tokens','completion_tokens')), 'known exact usage required')
        cost=(Decimal(usage['prompt_tokens'])*Decimal(str(frozen['config']['pricing']['inputUsdPerMillion']))
            +Decimal(usage['completion_tokens'])*Decimal(str(frozen['config']['pricing']['outputUsdPerMillion'])))/1000000
        require(usage['prompt_tokens']<=job['stages'][0]['reservedInputTokensHeuristic']
            and usage['completion_tokens']<=8000 and cost==Decimal(stage['estimatedCostUsd'])
            and cost<=Decimal(job['stages'][0]['reservedCostUsd']), 'cost/usage mismatch')
        total_cost+=cost
        try:
            receipt=receive(ticket,body,stage['providerResponse'])
        except (ValueError,KeyError,TypeError) as error:
            require(unit.get('status')=='JUDGMENT_REJECTED' and unit.get('contractError')==str(error)
                and 'output' not in unit and 'boundReceipt' not in stage, 'unmodified rejection required')
        else:
            require(stage['boundReceipt']==receipt and unit['reviewPacket']==receipt['compatiblePacket']
                and unit['output']==receipt['compatiblePacket']['compatibleAssessment'], 'derived response mismatch')
        a=indexed[job['case'],job['arm'],job['repeat']]
        wanted='REVIEW_REQUIRED' if job['case'] in {'B1','B3'} else 'PASS'
        rows.append(dict(case=job['case'],repeat=job['repeat'],accepted=a['accepted'],decision=a.get('decision'),
            developmentExpectedDecision=wanted,matched=None if mock else a['accepted'] and a.get('decision')==wanted,
            semanticIssueMatched=None))
    require(Decimal(report['estimatedCostUsd'])== (Decimal(0) if mock else total_cost), 'total usage mismatch')
    summary=[]
    for case in frozen['source']['cases']:
        group=[r for r in rows if r['case']==case['name']]
        summary.append(dict(case=case['name'],observations=3,accepted=sum(r['accepted'] for r in group),
            decisions=dict(Counter(r['decision'] for r in group)),matched=None if mock else sum(r['matched'] for r in group)))
    return dict(schemaVersion='fixed-scope-diagnostic-score-1',runKind=report['runKind'],frozenHash=frozen['artifactHash'],
        reportHash=digest(report),auditHash=digest(audit),summary=summary,results=rows,
        developmentRegressionGateMet=None,diagnosticExpectedDecisionsMet=None if mock else all(r['matched'] for r in rows),
        semanticQualityGateMet=None,runtimeUseAuthorized=False,fullPipelineReplayed=False,
        independentGoldApprovals=0,responsesModified=False,selectionReplayed=False)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('frozen','report','audit','output'):p.add_argument('--'+name,required=True)
    a=p.parse_args()
    result=score(read(a.frozen),read(a.report),read(a.audit))
    write_private(Path(a.output).resolve(),result)
    print(dict(summary=result['summary'],runtimeUseAuthorized=False))
