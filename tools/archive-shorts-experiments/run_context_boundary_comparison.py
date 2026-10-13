"""Eight pinned diagnostic judgments, paid only after a complete reservation.

No model selection, prompt changes, retries, semantic approval or deployment.
The original preview remains immutable and non-executable.
"""
import copy
from decimal import Decimal
import json
from pathlib import Path

from bound_review_contract import prepare_attempt, receive
from context_boundary_packet import validate as validate_packet
from fixed_review_suite import digest, read, require
from private_report_bundle import load
from run_boundary_comparison import reserve, positive
from issue_scoped_recipes import BOUND_SCHEMA

ROOT = Path(__file__).resolve().parents[1]
PREVIEW = ROOT / 'datasets/controversy/evaluation/v1/b-context-boundary-review-20261012'
PRIOR = ROOT / 'datasets/controversy/evaluation/v1/context-reselection-20261012'
PACKET_HASH = '0c793aadfa4c86345cd912d8de86c3dfb50ad2f533c4a7a246f1c54e2290360d'
PRIOR_REPORT_HASH = '9124949e52fc73accc216b5b16e2bd5edfa91c980fc7a9803fc0361c115c0e5a'
PRIOR_SCORE_HASH = 'a2a66249666249d535d42aab44c2fb6c7e18ccdda554c2ba5d0d2b1491e2cf73'


def prepare(packet, records, specification, prior, report, scored):
    validate_packet(packet, records, specification)
    require(digest(packet)==PACKET_HASH and digest(report)==PRIOR_REPORT_HASH
            and digest(scored)==PRIOR_SCORE_HASH
            and report['artifactHash']==prior['artifactHash']
            and report['runKind']=='LIVE' and report['unknownCostAttempts']==0
            and report['status']=='COMPLETED_PENDING_JAVA_AUDIT' and report['apiCalls']==4
            and report['consumedReservationUsd']==prior['reservedCostUsd']
            and scored['remainingBudgetUsd']==prior['remainingBudgetUsd']
            and prior['remainingBudgetUsd']=='0.4683626', 'complete pinned reservation ledger required')
    available = Decimal(prior['remainingBudgetUsd'])
    jobs, cases = [], []
    for view in packet['views']:
        record=next(r for r in records if r['id']==view['id'])
        cases.append(dict(name=view['id'],payload=copy.deepcopy(record['attempt']['originalInput']),
                          developmentExpectation=view['developmentExpectedDecision']))
    for request in packet['requests']:
        ticket=request['attempt'];body=ticket['requestBody']
        tokens,cost=reserve(body,packet['config'])
        require(tokens==request['reservedInputTokensHeuristic']
                and cost==Decimal(request['plannedCostUpperEstimateUsd']), 'preview reservation changed')
        jobs.append(dict(case=request['conditionId'],arm=request['arm'],repeat=1,
            baseBody=copy.deepcopy(ticket['baseRequest']),baseRequestHash=digest(ticket['baseRequest']),
            fixedSelectedScope=copy.deepcopy(ticket['selectedScope']),judgmentVariant='CLOSED_SCOPE',
            stages=[dict(stage='JUDGE',body=copy.deepcopy(body),requestHash=digest(body),
                         reservedInputTokensHeuristic=tokens,reservedCostUsd=str(cost))]))
    total=sum(Decimal(j['stages'][0]['reservedCostUsd']) for j in jobs)
    require(len(jobs)==8 and total==Decimal(packet['plannedCostUpperEstimateUsd']) and total<=available,
            'eight judgments must fit existing approval')
    result=dict(schemaVersion=BOUND_SCHEMA,boundaryInputStudy='PINNED_ASSISTANT_SELECTION_1',
        previewHash=PACKET_HASH,priorReportHash=PRIOR_REPORT_HASH,priorScoreHash=PRIOR_SCORE_HASH,
        source=dict(cases=cases),config=copy.deepcopy(packet['config']),jobs=jobs,
        authorization=copy.deepcopy(prior['authorization']),authorizationHash=prior['authorizationHash'],
        additionalFundingHash=prior['additionalFundingHash'],reservedCostUsd=str(total),
        remainingBudgetUsd=str(available-total),priorApprovalReservedUsd=str(Decimal('1')-available),
        allHistoryReservedUsd=str(Decimal(prior['allHistoryReservedUsd'])+total),
        plannedUnits=8,maxApiCalls=8,repetitions=1,requestTimeoutSeconds=90,maxConcurrency=1,
        reportSerialization='bundle-1',normalControlsIncluded=True,repetitionsPerCondition=1,
        executionEnabled=True,budgetSufficient=True,automaticSelectionEvaluated=False,
        fullPipelineReplayed=False,runtimeChanged=False,qualityGateMet=None)
    result['artifactHash']=digest(result)
    return result


def from_source():
    from prepare_context_reselection import validate as validate_prior, score as score_prior
    prior=read(PRIOR/'frozen.json');report=load(PRIOR/'live-run/report-index.json')
    audit=read(PRIOR/'java-audit.json');scored=read(PRIOR/'score.json')
    validate_prior(prior)
    require(scored==score_prior(prior,report,audit), 'prior accounting changed')
    return prepare(read(PREVIEW/'packet.json'),read(PREVIEW/'records.json'),
                   read(PREVIEW/'specification.json'),prior,report,scored)


def validate(frozen):
    require(frozen==from_source(), 'frozen input comparison changed')


def audit_input(report):
    from compare_recorded_candidates import audit_export
    exported=audit_export(json.loads(json.dumps(report,ensure_ascii=False)))
    exported.update(trialSchemaVersion='recorded-boundary-input-1',repetitionsPerCondition=1)
    return exported


def score(frozen, report, audit, *, allow_partial=False):
    from compare_review_prompts import digest as audit_digest
    validate(frozen)
    require(report['artifactHash']==frozen['artifactHash'] and report['previewHash']==frozen['previewHash']
            and report['runKind'] in {'LIVE','MOCK_OFFLINE'}
            and audit['sourceReportSha256']==audit_digest(audit_input(report)), 'report/audit association changed')
    complete=report['status'] in {'COMPLETED_PENDING_JAVA_AUDIT','MOCK_COMPLETE_PENDING_JAVA_AUDIT'}
    require(complete or allow_partial, 'partial result needs explicit diagnostic scoring')
    require(len(report['calls'])<=8 and (len(report['calls'])==8 if complete else True),
            'bounded complete result required')
    rows=[];total=Decimal('0');unknown=0
    for index,unit in enumerate(report['calls'],1):
        job=frozen['jobs'][index-1]
        require((unit['unitIndex'],unit['case'],unit['arm'],unit['repeat'])==
                (index,job['case'],job['arm'],1) and len(unit['stages'])==1,
                'ordered one-stage results required')
        stage=unit['stages'][0];cap=job['stages'][0]
        ticket=prepare_attempt(job['baseBody'],job['fixedSelectedScope'],f'{frozen["artifactHash"]}-{index:03d}',
                               judgment_variant='CLOSED_SCOPE')
        require(stage['localAttempt']==ticket and stage['body']==cap['body']
                and stage['requestHash']==digest(stage['body']) and unit['selectedScope']==job['fixedSelectedScope'],
                'executed scope/request changed')
        known='estimatedCostUsd' in stage
        if known:
            usage=stage['providerResponse']['usage']
            require(stage['usage']==usage and all(type(usage.get(k)) is int and usage[k]>=0
                    for k in ('prompt_tokens','completion_tokens')), 'original usage required')
            cost=(Decimal(usage['prompt_tokens'])*positive(frozen['config']['pricing']['inputUsdPerMillion'])
                 +Decimal(usage['completion_tokens'])*positive(frozen['config']['pricing']['outputUsdPerMillion']))/1000000
            require(cost==Decimal(stage['estimatedCostUsd']), 'observed cost changed')
            if complete:
                require(cost<=Decimal(cap['reservedCostUsd']) and usage['completion_tokens']<=8000
                        and usage['prompt_tokens']<=cap['reservedInputTokensHeuristic'], 'reservation exceeded')
            total+=cost
        else:
            unknown+=1
        row=dict(case=job['case'],arm=job['arm'],accepted=False,assessment=None,
                 status=unit['status'],knownCost=known)
        if unit['status']=='CONTRACT_ONLY':
            receipt=receive(ticket,stage['body'],stage['providerResponse'])
            require(stage['boundReceipt']==receipt and unit['reviewPacket']==receipt['compatiblePacket']
                    and unit['output']==receipt['compatiblePacket']['compatibleAssessment'], 'receipt/output changed')
            audited=next(r for r in audit['results'] if (r['case'],r['arm'],r['repeat'])==(job['case'],job['arm'],1))
            row.update(accepted=audited['accepted'],assessment=copy.deepcopy(receipt['modelOutput']['assessment']),
                       usedSelectionIndexes=receipt['modelOutput']['usedSelectionIndexes'],
                       selectedEvidence=copy.deepcopy(job['fixedSelectedScope']['evidence']))
        rows.append(row)
    live=report['runKind']=='LIVE'
    require(report['unknownCostAttempts']==unknown and report['apiCalls']==(len(rows) if live else 0)
            and Decimal(report['estimatedCostUsd'] if live else report['mockTokenRateEquivalentUsd'])==total
            and report['consumedReservationUsd']==(frozen['reservedCostUsd'] if live else '0'),
            'totals/reservation changed')
    return dict(schemaVersion='boundary-input-comparison-score-1',reportHash=digest(report),auditHash=digest(audit),
        results=rows,runKind=report['runKind'],complete=complete,unknownCostAttempts=unknown,
        mechanicalContractGateMet=complete and all(r['accepted'] for r in rows),
        knownUsageRateEquivalentUsd=str(total) if live else None,actualBilledCostUsd=None,
        reservedCostUsd=frozen['reservedCostUsd'] if live else '0',
        remainingBudgetUsd=frozen['remainingBudgetUsd'] if live else '0.4683626',
        automaticSelectionEvaluated=False,fullRegressionReplayed=False,
        semanticQualityGateMet=None,runtimeUseAuthorized=False)
