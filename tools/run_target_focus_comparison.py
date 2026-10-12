"""Pinned target-focus comparison via the shared guarded runner; never runtime adoption."""
import copy
from decimal import Decimal
import json
from pathlib import Path

from bound_review_contract import prepare_attempt, receive
from compare_recorded_candidates import audit_export
from compare_review_prompts import digest as audit_digest
from experiment_costs import reserve, positive
from fixed_review_suite import digest, read, require
from issue_scoped_recipes import BOUND_SCHEMA
from private_report_bundle import load
from prepare_target_focus_comparison import prepare as preview_prepare

ROOT = Path(__file__).resolve().parents[1] / 'datasets/controversy/evaluation/v1'
SOURCE = ROOT / 'b-context-boundary-live-20261012'
PREVIEW = ROOT / 'target-focus-preview-20261012.json'
PREVIEW_HASH = '0503c10c1f438d1d7c6e9d3bf183f078633852353af086a09c7f97666f66784d'


def source_inputs():
    frozen = read(SOURCE / 'frozen.json')
    report = load(SOURCE / 'live-run/report-index.json')
    audit = read(SOURCE / 'java-audit.json')
    preview = read(PREVIEW)
    require(digest(preview) == PREVIEW_HASH and preview == preview_prepare(frozen, report, audit),
            'original audited preview changed')
    return preview, frozen, report


def freeze(preview, prior, report, available):
    require(preview['artifactHash'] == digest({k:v for k,v in preview.items() if k != 'artifactHash'})
            and preview['status'] == 'OFFLINE_NOT_EXECUTABLE' and preview['executionEnabled'] is False
            and preview['apiCalls'] == 0 and preview['reservedCostUsd'] == '0'
            and preview['frozenHash'] == digest(prior) and preview['reportHash'] == digest(report),
            'unchanged nonexecuted preview/source required')
    available = positive(available)
    require(available <= Decimal(prior['remainingBudgetUsd']), 'do not increase prior approved remainder')
    jobs, cases = [], []
    tickets = {u['stages'][0]['localAttempt']['attemptHash']:u['stages'][0]['localAttempt'] for u in report['calls']}
    for index, group in enumerate(preview['comparisons'], 1):
        ticket = tickets[group['observations'][0]['sourceAttemptHash']]
        require(ticket['requestBody'] == group['baselineRequest']
                and ticket['selectedScope']['evidence'] == group['selectedEvidence'], 'original group changed')
        name = f'F{index}'
        cases.append(dict(name=name, payload=copy.deepcopy(ticket['originalInput']),
            developmentExpectation=group['developmentExpectedDecision'], labelStatus='OWNER_DEVELOPMENT_ONLY',
            sourceObservations=copy.deepcopy(group['observations'])))
        for arm in (('BASELINE','CANDIDATE') if index % 2 else ('CANDIDATE','BASELINE')):
            variant = 'CLOSED_SCOPE' if arm == 'BASELINE' else 'TARGET_FOCUS_CLOSED_SCOPE'
            body = prepare_attempt(ticket['baseRequest'],ticket['selectedScope'], 'freeze-preview',
                                   judgment_variant=variant)['requestBody']
            require(body == group['baselineRequest' if arm == 'BASELINE' else 'candidateRequest'],
                    'execution variant differs from reviewed preview')
            tokens, cost = reserve(body, prior['config'])
            jobs.append(dict(case=name, arm=arm, repeat=1, baseBody=copy.deepcopy(ticket['baseRequest']),
                baseRequestHash=digest(ticket['baseRequest']), fixedSelectedScope=copy.deepcopy(ticket['selectedScope']),
                judgmentVariant=variant, stages=[dict(stage='JUDGE',body=body,requestHash=digest(body),
                    reservedInputTokensHeuristic=tokens,reservedCostUsd=str(cost))]))
    total = sum(Decimal(j['stages'][0]['reservedCostUsd']) for j in jobs)
    require(len(cases) == 5 and len(jobs) == 10 and total <= available
            and {c['developmentExpectation'] for c in cases} == {'PASS','REVIEW_REQUIRED'},
            'five fixed inputs and both control classes must fit existing budget')
    result = dict(schemaVersion=BOUND_SCHEMA, targetFocusTrial='PINNED_JUDGE_ONLY_1',
        previewHash=PREVIEW_HASH, sourceReportHash=digest(report), source=dict(cases=cases),
        config=copy.deepcopy(prior['config']), authorization=copy.deepcopy(prior['authorization']),
        authorizationHash=prior['authorizationHash'], additionalFundingHash=prior['additionalFundingHash'],
        availableBudgetUsd=str(available), reservedCostUsd=str(total), remainingBudgetUsd=str(available-total),
        jobs=jobs, plannedUnits=10, maxApiCalls=10, repetitions=1, repetitionsPerCondition=1,
        requestTimeoutSeconds=90, maxConcurrency=1, reportSerialization='bundle-1',
        executionEnabled=True, budgetSufficient=True, normalControlsIncluded=True,
        automaticSelectionEvaluated=False, fullPipelineReplayed=False, runtimeChanged=False,
        qualityGateMet=None, semanticQualityGateMet=None)
    result['artifactHash'] = digest(result)
    return result


def prepare(available):
    return freeze(*source_inputs(), available)


def validate(frozen):
    if frozen.get('targetFocusTrial') == 'REGRESSION_THREE_REPEATS_1':
        from run_target_focus_regression import validate as validate_regression
        validate_regression(frozen)
        return
    require(frozen == prepare(frozen['availableBudgetUsd']), 'target-focus frozen plan changed')


def audit_input(report):
    exported = audit_export(json.loads(json.dumps(report,ensure_ascii=False)))
    repetitions = report.get('repetitionsPerCondition',1)
    exported.update(trialSchemaVersion=('recorded-flow-regression-1' if repetitions >= 3 else 'recorded-target-focus-1'),
                    repetitionsPerCondition=repetitions)
    return exported


def score(frozen, report, audit):
    validate(frozen)
    return score_validated(frozen, report, audit)


def score_validated(frozen, report, audit):
    """Only for validated frozen recipes; shared original-response/accounting checks."""
    count = frozen['plannedUnits']
    live = report['runKind'] == 'LIVE'
    trial_key = 'referenceSplitStudy' if 'referenceSplitStudy' in frozen else 'targetFocusTrial'
    require(report['runKind'] in {'LIVE','MOCK_OFFLINE'} and len(report['calls']) == count
            and report['artifactHash'] == frozen['artifactHash'] and report['previewHash'] == frozen['previewHash']
            and report[trial_key] == frozen[trial_key]
            and report['cases'] == frozen['source']['cases']
            and report['authorizationHash'] == frozen['authorizationHash']
            and report['additionalFundingHash'] == frozen['additionalFundingHash']
            and report['reservedCostUsd'] == frozen['reservedCostUsd']
            and report['repetitionsPerCondition'] == frozen['repetitionsPerCondition']
            and report['status'] == ('COMPLETED_PENDING_JAVA_AUDIT' if live else 'MOCK_COMPLETE_PENDING_JAVA_AUDIT')
            and audit['sourceReportSha256'] == audit_digest(audit_input(report)), 'complete associated report/audit required')
    indexed = {(a['case'],a['arm'],a['repeat']):a for a in audit['results']}
    require(len(indexed) == len(audit['results']) == count
            and set(indexed) == {(j['case'],j['arm'],j['repeat']) for j in frozen['jobs']}, 'unique complete Java audit required')
    cases = {c['name']:c for c in frozen['source']['cases']}
    rows, total = [], Decimal('0')
    for index, (job, unit) in enumerate(zip(frozen['jobs'],report['calls'],strict=True), 1):
        require((unit['unitIndex'],unit['case'],unit['arm'],unit['repeat']) == (index,job['case'],job['arm'],job['repeat'])
                and unit['requestHash'] == job['baseRequestHash']
                and len(unit['stages']) == 1 and 'error' not in unit, 'ordered complete one-stage responses required')
        stage = unit['stages'][0]; cap = job['stages'][0]
        ticket = prepare_attempt(job['baseBody'],job['fixedSelectedScope'],f'{frozen["artifactHash"]}-{index:03d}',
                                 judgment_variant=job['judgmentVariant'])
        require(stage['localAttempt'] == ticket and stage['body'] == cap['body']
                and stage['stage'] == cap['stage'] == 'JUDGE' and 'error' not in stage
                and stage['requestHash'] == digest(stage['body']) and unit['selectedScope'] == job['fixedSelectedScope'],
                'executed source/request association changed')
        usage = stage['providerResponse']['usage']
        require(stage['usage'] == usage and all(type(usage.get(k)) is int and usage[k] >= 0
                for k in ('prompt_tokens','completion_tokens')), 'original usage required')
        price = frozen['config']['pricing']
        cost = (Decimal(usage['prompt_tokens'])*positive(price['inputUsdPerMillion'])
                +Decimal(usage['completion_tokens'])*positive(price['outputUsdPerMillion']))/1000000
        require(cost == Decimal(stage['estimatedCostUsd']) and cost <= Decimal(cap['reservedCostUsd'])
                and usage['prompt_tokens'] <= cap['reservedInputTokensHeuristic'] and usage['completion_tokens'] <= 8000,
                'usage estimate/reservation changed')
        total += cost
        a = indexed[job['case'],job['arm'],job['repeat']]; assessment = None
        try:
            receipt = receive(ticket,stage['body'],stage['providerResponse'])
        except (ValueError,KeyError,TypeError) as error:
            require(unit.get('contractError') == str(error) and not a['accepted']
                    and unit['status'] == 'JUDGMENT_REJECTED' and 'output' not in unit, 'contract failure changed')
        else:
            require(stage['boundReceipt'] == receipt and unit['reviewPacket'] == receipt['compatiblePacket']
                    and unit['output'] == receipt['compatiblePacket']['compatibleAssessment'], 'derived output changed')
            assessment = copy.deepcopy(receipt['modelOutput']['assessment'])
            require(a.get('decision') == assessment['decision'], 'audited decision changed')
        wanted = cases[job['case']]['developmentExpectation']
        row = dict(case=job['case'],arm=job['arm'],accepted=a['accepted'],assessment=assessment,
            expectedDecision=wanted, labelStatus='OWNER_DEVELOPMENT_ONLY',
            matchedDevelopmentDecision=(bool(a['accepted'] and assessment and assessment['decision'] == wanted) if live else None),
            semanticIssueMatched=None)
        if frozen['repetitionsPerCondition'] > 1: row['repeat'] = job['repeat']
        rows.append(row)
    require(report['unknownCostAttempts'] == 0 and report['apiCalls'] == (count if live else 0)
            and report['simulatedCalls'] == (0 if live else count)
            and report['consumedReservationUsd'] == (frozen['reservedCostUsd'] if live else '0')
            and Decimal(report['estimatedCostUsd'] if live else report['mockTokenRateEquivalentUsd']) == total,
            'accounting changed')
    return dict(schemaVersion='target-focus-score-1',reportHash=digest(report),auditHash=digest(audit),
        results=rows,runKind=report['runKind'],mechanicalContractGateMet=all(r['accepted'] for r in rows),
        estimatedUsageCostUsd=str(total) if live else None, actualBilledCostUsd=None,
        reservedCostUsd=frozen['reservedCostUsd'] if live else '0',
        remainingBudgetUsd=frozen['remainingBudgetUsd'] if live else frozen['availableBudgetUsd'],
        semanticQualityGateMet=None,runtimeUseAuthorized=False,fullRegressionReplayed=False,
        automaticSelectionEvaluated=False)
