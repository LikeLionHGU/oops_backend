"""Pinned experiment-117 follow-up, not a full regression or runtime pipeline.

Reconstructs the plan from immutable private source files. Reserves SELECT and
the maximum fresh CLOSED_SCOPE judgment before any key is loaded.
"""
import copy
import json
from decimal import Decimal
from pathlib import Path

from bound_review_contract import validate_receipt
from context_reselection import eligible, selection_request
from fixed_review_suite import digest, read, require
from private_report_bundle import load
from run_boundary_comparison import reserve
from issue_scoped_recipes import BOUND_SCHEMA, SCOPE_BYTES
from scoped_judgment_contract import judgment_request

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'datasets/controversy/evaluation/v1/scoped-full-regression-20261012'
SOURCE_FROZEN_HASH = 'a1a7e8592ebb625ce5b4ce1a888b23dc00aff056f7b4d874b9939cb3a1d7a2ef'
SOURCE_REPORT_HASH = '33b7abfacb2c702fdfa05bb5e669b8a6d0d5d624c1d25b2443d878d5551d2230'


def prepare(source, report):
    from compare_scoped_inputs import validate as validate_source
    validate_source(source)
    require(source['artifactHash'] == SOURCE_FROZEN_HASH and digest(report) == SOURCE_REPORT_HASH
            and report['artifactHash'] == source['artifactHash'] and report['runKind'] == 'LIVE'
            and report['status'] == 'COMPLETED_PENDING_JAVA_AUDIT'
            and report['unknownCostAttempts'] == 0
            and report['consumedReservationUsd'] == source['reservedCostUsd'],
            'pinned complete source and budget ledger required')
    jobs, skipped = [], []
    for unit in report['calls']:
        if unit['arm'] != 'CANDIDATE':
            continue
        stage = unit['stages'][0]
        ticket, receipt = stage['localAttempt'], stage['boundReceipt']
        validate_receipt(receipt, ticket)
        if not eligible(ticket, receipt):
            skipped.append(dict(case=unit['case'], repeat=unit['repeat'],
                                decision=receipt['modelOutput']['assessment']['decision'],
                                priorReceiptHash=receipt['receiptHash']))
            continue
        base = ticket['baseRequest']
        require(base['model'] == 'gpt-6-luna' and base['reasoning_effort'] == 'low'
                and base['max_completion_tokens'] == 8000 and base['store'] is False
                and base['response_format'] == {'type':'json_object'} and 'temperature' not in base,
                'preserved model settings required')
        select = selection_request(ticket, receipt)
        envelope = judgment_request(base, ticket['selectedScope'], 'CLOSED_SCOPE')
        envelope['messages'][1]['content'] = json.dumps(dict(
            selectedScope='x'*SCOPE_BYTES, grounding='x'*512), ensure_ascii=False)
        stages = []
        for name, body in [('SELECT', select), ('JUDGE', envelope)]:
            tokens, cost = reserve(body, source['config'])
            stages.append(dict(stage=name, body=body, requestHash=digest(body),
                               reservedInputTokensHeuristic=tokens, reservedCostUsd=str(cost)))
        jobs.append(dict(case=unit['case'], repeat=unit['repeat'], arm='CANDIDATE',
            baseBody=copy.deepcopy(base), baseRequestHash=digest(base),
            judgmentVariant='CLOSED_SCOPE', stages=stages,
            previousAttempt=copy.deepcopy(ticket), previousReceipt=copy.deepcopy(receipt),
            reselectionsUsed=0))
    require(1 <= len(jobs) <= 27, 'bounded unresolved cases required')
    total = sum(Decimal(s['reservedCostUsd']) for j in jobs for s in j['stages'])
    available = Decimal(source['remainingBudgetUsd'])
    require(available == Decimal('0.4999360') and total <= available,
            'follow-up exceeds existing approval')
    names = {j['case'] for j in jobs}
    result = dict(schemaVersion=BOUND_SCHEMA, contextReselectionStudy='OBSERVED_UNCERTAIN_FOLLOWUP_1',
        sourceFrozenHash=SOURCE_FROZEN_HASH, sourceReportHash=SOURCE_REPORT_HASH,
        source=dict(cases=[copy.deepcopy(c) for c in source['source']['cases'] if c['name'] in names]),
        config=copy.deepcopy(source['config']), authorization=copy.deepcopy(source['authorization']),
        authorizationHash=source['authorizationHash'], additionalFundingHash=source['additionalFundingHash'],
        jobs=jobs, skipped=skipped, plannedUnits=len(jobs), maxApiCalls=2*len(jobs),
        reservedCostUsd=str(total), remainingBudgetUsd=str(available-total),
        priorApprovalReservedUsd=str(Decimal('1')-available),
        allHistoryReservedUsd=str(Decimal('4.4619464')+total),
        executionEnabled=True, budgetSufficient=True, maxConcurrency=1, requestTimeoutSeconds=90,
        reportSerialization='bundle-1', normalControlsIncluded=False,
        fullPipelineReplayed=False, runtimeChanged=False, qualityGateMet=None)
    result['artifactHash'] = digest(result)
    return result


def from_source():
    return prepare(read(SOURCE / 'frozen-bundle.json'), load(SOURCE / 'live-run/report-index.json'))


def validate(frozen):
    require(frozen == from_source(), 'reselection source/plan changed')


def score(frozen, report, audit):
    """Mechanical follow-up accounting; never labels semantic recovery success."""
    from bound_review_contract import prepare_attempt, receive
    from span_catalog_review import hydrate
    from run_boundary_comparison import positive
    from compare_recorded_candidates import audit_export
    from compare_review_prompts import digest as audit_digest
    validate(frozen)
    exported = audit_export(json.loads(json.dumps(report, ensure_ascii=False)))
    exported.update(trialSchemaVersion='recorded-flow-regression-1', repetitionsPerCondition=3)
    require(report['artifactHash'] == frozen['artifactHash']
            and report['sourceReportHash'] == frozen['sourceReportHash']
            and audit['sourceReportSha256'] == audit_digest(exported)
            and report['skipped'] == frozen['skipped'], 'report/audit linkage changed')
    require(len(report['calls']) == frozen['plannedUnits'], 'complete follow-up required')
    results, total = [], Decimal('0')
    for index, (job, unit) in enumerate(zip(frozen['jobs'], report['calls']), 1):
        require((unit['case'],unit['repeat'],unit['arm'],unit['unitIndex']) ==
                (job['case'],job['repeat'],job['arm'],index)
                and unit['status'] == 'CONTRACT_ONLY' and len(unit['stages']) == 2,
                'complete ordered selection/judgment required')
        selection, judge = unit['stages']
        require(selection['body'] == job['stages'][0]['body'], 'selection request changed')
        from bound_review_contract import parse_response
        chosen = hydrate(parse_response(selection['providerResponse'], job['baseBody']['model']),
                         job['previousAttempt']['originalInput'])
        ticket = prepare_attempt(job['baseBody'], chosen, f'{frozen["artifactHash"]}-{index:03d}',
                                 judgment_variant='CLOSED_SCOPE')
        receipt = receive(ticket, judge['body'], judge['providerResponse'])
        require(unit['selectedScope'] == chosen and judge['localAttempt'] == ticket
                and judge['boundReceipt'] == receipt and unit['reviewPacket'] == receipt['compatiblePacket']
                and unit['output'] == receipt['compatiblePacket']['compatibleAssessment'],
                'selection/judgment provenance changed')
        transition = dict(priorReceiptHash=job['previousReceipt']['receiptHash'],
            previousScopeHash=job['previousAttempt']['scopeHash'], newScopeHash=chosen['scopeHash'],
            previousEvidence=job['previousAttempt']['selectedScope']['evidence'],
            newEvidence=chosen['evidence'], reselectionsUsed=1,
            semanticQualityGateMet=None, runtimeUseAuthorized=False)
        require(unit['contextTransition'] == transition, 'context transition changed')
        for observed, cap in zip(unit['stages'],job['stages']):
            usage = observed['providerResponse']['usage']
            require(observed['requestHash'] == digest(observed['body']) and observed['usage'] == usage
                    and all(type(usage.get(k)) is int and usage[k]>=0
                            for k in ('prompt_tokens','completion_tokens')),
                    'response usage/request mismatch')
            cost = (Decimal(usage['prompt_tokens'])*positive(frozen['config']['pricing']['inputUsdPerMillion'])
                  + Decimal(usage['completion_tokens'])*positive(frozen['config']['pricing']['outputUsdPerMillion']))/1000000
            require(cost == Decimal(observed['estimatedCostUsd']) and cost<=Decimal(cap['reservedCostUsd'])
                    and usage['prompt_tokens']<=cap['reservedInputTokensHeuristic']
                    and usage['completion_tokens']<=8000, 'usage/cost exceeded or changed')
            total += cost
        accepted = next(r for r in audit['results'] if
            (r['case'],r['arm'],r['repeat']) == (job['case'],job['arm'],job['repeat']))
        results.append(dict(case=job['case'],repeat=job['repeat'],
            beforeDecision=job['previousReceipt']['modelOutput']['assessment']['decision'],
            afterAssessment=copy.deepcopy(receipt['modelOutput']['assessment']),
            selectedEvidence=copy.deepcopy(chosen['evidence']),
            usedSelectionIndexes=receipt['modelOutput']['usedSelectionIndexes'],
            javaAccepted=accepted['accepted'], scopeChanged=chosen['scopeHash']!=job['previousAttempt']['scopeHash']))
    live = report['runKind']=='LIVE'
    require(report['unknownCostAttempts']==0 and report['apiCalls']==(2*len(results) if live else 0)
            and Decimal(report['estimatedCostUsd'] if live else report['mockTokenRateEquivalentUsd'])==total
            and report['consumedReservationUsd']==(frozen['reservedCostUsd'] if live else '0'),
            'report totals changed')
    return dict(schemaVersion='context-reselection-score-1', sourceReportHash=digest(report),
        auditHash=digest(audit), results=results, runKind=report['runKind'],
        mechanicalContractGateMet=all(r['javaAccepted'] for r in results),
        knownUsageRateEquivalentUsd=str(total) if live else None,
        consumedReservationUsd=report['consumedReservationUsd'],
        remainingBudgetUsd=frozen['remainingBudgetUsd'] if live else '0.4999360',
        actualBilledCostUsd=None, fullRegressionReplayed=False, semanticQualityGateMet=None,
        runtimeUseAuthorized=False)
