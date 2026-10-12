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
from issue_scoped_recipes import BOUND_SCHEMA, BOUNDARY_FUNDING, bound_judgment_request

SOURCE_HASHES = dict(
    frozen='9f7a89523d03cdb62397acfb2fa05705ada3d3d072f48a7cd562d6a4c27d89ea',
    report='7916b797caba31677dbb528fa3d2bbdb49a42817d72e7c306291ad2e24a68c35',
    audit='a0348eff5ecad26d1c258d91464c9b2cb7c2f61369e411d8a685340acff34fca',
    score='578370cfbd74e48920f34acce7e4696e735220646b786922c62be44cf3cfc3a0')
SEED_HASH = '1c917f77273546fe1bf568b88f1fe7fb407144b987687f694ef297bf2fe4c251'
SPEC = [('B1', 'B', 1), ('B3', 'B', 3), ('N3', 'N3', 1), ('N4', 'N4', 1)]
RETRIAL_FUNDING = dict(schemaVersion='fixed-scope-retrial-budget-1', additionalBudgetUsd='0.10',
    userAuthorized=True, previousReservedUsd='3.8829914', previousRemainingUsd='0.0171746')
PARTIAL_REPORT_HASH = 'ebc9c0debeac98aa549c5347d3d17ccea26ce1e18f781e0604e014da9a629bb9'


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
    expected=prepare(frozen['seed'])
    if 'retrialFunding' in frozen:
        expected=fund_retrial(expected,frozen['priorDiagnostic'],frozen['retrialFunding'])
    require(frozen == expected, 'fixed-scope frozen changed')


def fund_retrial(original, prior_report, funding):
    """One separate approved trial; no resume, automatic retry or refunds."""
    require(original == prepare(original['seed']) and funding == RETRIAL_FUNDING,
            'unchanged original and exact new approval required')
    fields=('schemaVersion','artifactHash','additionalFundingHash','status','runKind',
            'reservedCostUsd','consumedReservationUsd','apiCalls','unknownCostAttempts')
    expected=dict(schemaVersion=BOUND_SCHEMA,artifactHash=original['artifactHash'],
        additionalFundingHash=original['additionalFundingHash'],status='PARTIAL',runKind='LIVE',
        reservedCostUsd=original['reservedCostUsd'],consumedReservationUsd=original['reservedCostUsd'],
        apiCalls=3,unknownCostAttempts=1)
    require(all(prior_report.get(k)==v for k,v in expected.items()), 'exact interrupted prior reservation required')
    meta={k:prior_report[k] for k in fields}
    meta['sourceReportHash']=prior_report.get('sourceReportHash',digest(prior_report))
    require(meta['sourceReportHash']==PARTIAL_REPORT_HASH
            and original['remainingBudgetUsd']==funding['previousRemainingUsd'], 'prior ledger mismatch')
    total=Decimal(original['reservedCostUsd'])
    require(total<=Decimal(funding['additionalBudgetUsd']), 'retrial exceeds separate approval')
    result=copy.deepcopy(original); old_hash=result.pop('artifactHash')
    result.update(retrialFunding=copy.deepcopy(funding),priorDiagnostic=meta,parentArtifactHash=old_hash,
        additionalFunding=copy.deepcopy(funding),additionalFundingHash=digest(funding),
        remainingBudgetUsd=str(Decimal(funding['additionalBudgetUsd'])-total),
        priorBudgetRemainingUsd=funding['previousRemainingUsd'])
    result['artifactHash']=digest(result)
    return result


def diagnostic_audit_export(report, *, partial=False):
    exported=audit_export(json.loads(json.dumps(report,ensure_ascii=False)))
    exported.update(trialSchemaVersion='recorded-flow-regression-1',repetitionsPerCondition=3)
    if partial:
        observed={u['case'] for u in report['calls']}
        exported['cases']=[c for c in exported['cases'] if c['name'] in observed]
        exported['diagnosticPartial']=True
    return exported


def score(frozen, report, audit, *, allow_partial=False, paired=False):
    if paired:
        from compare_scoped_inputs import validate as validate_pair
        validate_pair(frozen)
    else:
        validate(frozen)
    limit=frozen['plannedUnits'] if paired else 12
    mock = report.get('runKind') == 'MOCK_OFFLINE'
    partial = report['status'] in {'PARTIAL','MOCK_PARTIAL'}
    require(not partial or allow_partial, 'partial diagnostic requires explicit opt-in; no gate')
    count=len(report['calls'])
    require(report.get('runKind') in {'MOCK_OFFLINE','LIVE'}
        and report['artifactHash']==frozen['artifactHash']
        and report['authorizationHash']==frozen['authorizationHash']
        and report['additionalFundingHash']==frozen['additionalFundingHash']
        and report['status']==(('MOCK_PARTIAL' if mock else 'PARTIAL') if partial else
                              ('MOCK_COMPLETE_PENDING_JAVA_AUDIT' if mock else 'COMPLETED_PENDING_JAVA_AUDIT'))
        and report['cases']==frozen['source']['cases'] and (1<=count<=limit if partial else count==limit)
        and report['apiCalls']==(0 if mock else count) and report['simulatedCalls']==(count if mock else 0)
        and report['consumedReservationUsd']==('0' if mock else frozen['reservedCostUsd'])
        and report['reservedCostUsd']==frozen['reservedCostUsd']
        and (partial or report['unknownCostAttempts']==0),
        'complete matched diagnostic report required')
    exported=diagnostic_audit_export(report,partial=partial)
    require(audit['sourceReportSha256']==audit_digest(exported), 'current Java audit mismatch')
    indexed={(r['case'],r['arm'],r['repeat']):r for r in audit['results']}
    require(len(indexed)==count and len(audit['results'])==count
            and set(indexed)=={(j['case'],j['arm'],j['repeat']) for j in report['calls']}, 'unique observed audit required')
    require(len({u['unitIndex'] for u in report['calls']})==count, 'duplicate execution index')
    rows=[]; total_cost=Decimal(0)
    expected_by_case={c['name']:c.get('developmentExpectation') or (
        'REVIEW_REQUIRED' if c['name'] in {'B','C','D','B1','B3'} else 'PASS') for c in frozen['source']['cases']}
    for unit in report['calls']:
        index=unit['unitIndex']
        require(type(index) is int and 1<=index<=limit,'execution index invalid')
        job=frozen['jobs'][index-1]
        require(all(job[k]==unit[k] for k in ('case','arm','repeat'))
            and unit['selectedScope']==job['fixedSelectedScope'] and len(unit['stages'])==1
            and unit['requestHash']==job['baseRequestHash'], 'fixed scope/unit mismatch')
        stage=unit['stages'][0]; body=job['stages'][0]['body']
        ticket=prepare_attempt(job['baseBody'],job['fixedSelectedScope'],f'{frozen["artifactHash"]}-{index:03d}',
                               judgment_variant=job.get('judgmentVariant','RELATION_CHECKLIST'))
        require(stage['stage']=='JUDGE' and stage['body']==body and stage['requestHash']==digest(body)
            and stage['localAttempt']==ticket, 'stage provenance mismatch')
        if 'error' in stage:
            require(partial and unit['status']=='FAILED_STOP' and unit['error']==stage['error']
                and 'output' not in unit and 'boundReceipt' not in stage, 'failed attempt must stay failed')
            if 'estimatedCostUsd' in stage:
                usage=stage['providerResponse']['usage']
                known=(Decimal(usage['prompt_tokens'])*Decimal(str(frozen['config']['pricing']['inputUsdPerMillion']))
                    +Decimal(usage['completion_tokens'])*Decimal(str(frozen['config']['pricing']['outputUsdPerMillion'])))/1000000
                require(stage['usage']==usage and known==Decimal(stage['estimatedCostUsd']), 'failed known usage mismatch')
                total_cost+=known
            a=indexed[job['case'],job['arm'],job['repeat']]
            require(not a['accepted'], 'provider failure cannot be accepted')
            rows.append(dict(case=job['case'],repeat=job['repeat'],accepted=False,decision=None,
                developmentExpectedDecision=expected_by_case[job['case']] if paired else
                    'REVIEW_REQUIRED' if job['case'] in {'B1','B3'} else 'PASS',
                matched=None,semanticIssueMatched=None,failureCode='PROVIDER_FAILURE'))
            if paired:rows[-1]['arm']=job['arm']
            continue
        require(stage['rawOutput']==parse_response(stage['providerResponse'],body['model']), 'raw response mismatch')
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
        wanted=expected_by_case[job['case']] if paired else 'REVIEW_REQUIRED' if job['case'] in {'B1','B3'} else 'PASS'
        rows.append(dict(case=job['case'],repeat=job['repeat'],accepted=a['accepted'],decision=a.get('decision'),
            developmentExpectedDecision=wanted,matched=None if mock or partial else a['accepted'] and a.get('decision')==wanted,
            semanticIssueMatched=None))
        if paired:rows[-1]['arm']=job['arm']
    require(Decimal(report['estimatedCostUsd'])== (Decimal(0) if mock else total_cost), 'total usage mismatch')
    require(report['unknownCostAttempts']==sum('estimatedCostUsd' not in s for u in report['calls'] for s in u['stages']),
            'unknown cost count mismatch')
    summary=[]
    for arm in (('BASELINE','CANDIDATE') if paired else (None,)):
        for case in frozen['source']['cases']:
            group=[r for r in rows if r['case']==case['name'] and (not paired or r['arm']==arm)]
            summary.append(dict(case=case['name'],observations=len(group),accepted=sum(r['accepted'] for r in group),
                decisions=dict(Counter(r['decision'] for r in group)),matched=None if mock or partial else sum(r['matched'] for r in group)))
            if paired:summary[-1]['arm']=arm
    result=dict(schemaVersion='fixed-scope-diagnostic-score-1',runKind=report['runKind'],frozenHash=frozen['artifactHash'],
        reportHash=digest(report),auditHash=digest(audit),summary=summary,results=rows,
        developmentRegressionGateMet=None,diagnosticExpectedDecisionsMet=None if mock or partial else all(r['matched'] for r in rows),
        diagnosticComplete=not partial,unknownCostAttempts=report['unknownCostAttempts'],
        semanticQualityGateMet=None,runtimeUseAuthorized=False,fullPipelineReplayed=False,
        independentGoldApprovals=0,responsesModified=False,selectionReplayed=False)
    if paired:
        costs=[]
        for arm in ('BASELINE','CANDIDATE'):
            units=[u for u in report['calls'] if u['arm']==arm]
            stages=[s for u in units for s in u['stages']]
            costs.append(dict(arm=arm,units=len(units),apiAttempts=len(stages),
                unknownCostAttempts=sum('estimatedCostUsd' not in s for s in stages),
                promptTokens=sum(s.get('usage',{}).get('prompt_tokens',0) for s in stages),
                completionTokens=sum(s.get('usage',{}).get('completion_tokens',0) for s in stages),
                knownUsageRateEquivalentUsd=str(sum(Decimal(s.get('estimatedCostUsd','0')) for s in stages)),
                meanUnitLatencyMs=round(sum(u['latencyMs'] for u in units)/len(units)) if units else None))
        result.update(schemaVersion='paired-scoped-input-score-1',armCosts=costs,
            comparisonScope='FIXED_SELECTION_JUDGE_ONLY_NOT_FULL_PIPELINE')
        if mock:
            for c in costs:
                c['simulatedCalls']=c['apiAttempts'];c['apiAttempts']=0
                c['mockTokenRateEquivalentUsd']=c.pop('knownUsageRateEquivalentUsd')
    return result


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('frozen','report','audit','output'):p.add_argument('--'+name,required=True)
    p.add_argument('--partial',action='store_true')
    a=p.parse_args()
    result=score(read(a.frozen),read(a.report),read(a.audit),allow_partial=a.partial)
    write_private(Path(a.output).resolve(),result)
    print(dict(summary=result['summary'],runtimeUseAuthorized=False))
