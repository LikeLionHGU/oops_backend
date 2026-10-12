"""Three predeclared repeats on seven saved inputs; no search or runtime adoption."""
import copy
from collections import Counter
from decimal import Decimal
import json
from pathlib import Path

from bound_review_contract import prepare_attempt, validate_receipt
from experiment_costs import reserve, positive
from fixed_review_suite import digest, read, require
from private_report_bundle import load
from run_target_focus_comparison import score as verify_focus, score_validated, PREVIEW_HASH

ROOT = Path(__file__).resolve().parents[1] / 'datasets/controversy/evaluation/v1'
FOCUS = ROOT / 'target-focus-live-20261012'
CD = ROOT / 'scoped-full-regression-20261012'


def source_inputs():
    f = read(FOCUS/'frozen.json'); r = load(FOCUS/'live-run/report-index.json')
    a = read(FOCUS/'java-audit.json'); s = read(FOCUS/'score.json')
    require(digest(f) == '55e2e5dd01c0b553057c210577d1e21ee22d64e265f21d3c1d21a746cd56ea45'
            and digest(r) == '9aaac53102a3ad6321bdb65d4b60d5cfd86ce93bd19f8e26a453e8e9b16aede0'
            and digest(s) == '8d898ba6b9269bfe22c006bb8b5771876dab26b8263f1566039a3951bd20a4f8'
            and s == verify_focus(f,r,a), 'complete pinned trial 134 required')
    cd = read(CD/'frozen-bundle.json'); report = load(CD/'live-run/report-index.json')
    audit = read(CD/'java-audit.json'); scored = read(CD/'score.json')
    from compare_scoped_inputs import score as verify_cd
    require(digest(cd) == '97efafee5e76c3ddb11cb206b10a19f17dafa5e8072c1f2661b5359e21c037a1'
            and digest(report) == '33b7abfacb2c702fdfa05bb5e669b8a6d0d5d624c1d25b2443d878d5551d2230'
            and scored == verify_cd(cd,report,audit), 'audited unchanged C/D source required')
    require(cd['config'] == f['config'] and cd['authorization'] == f['authorization'],
            'same model/pricing/approval required')
    tickets = {}
    for name in ('C','D'):
        units = [u for u in report['calls'] if (u['case'],u['arm'],u['repeat']) == (name,'CANDIDATE',1)]
        require(len(units) == 1, 'unique first saved C/D selection required')
        stage = units[0]['stages'][0]; validate_receipt(stage['boundReceipt'],stage['localAttempt'])
        require(stage['localAttempt']['judgmentVariant'] == 'CLOSED_SCOPE', 'matching closed C/D input required')
        tickets[name] = copy.deepcopy(stage['localAttempt'])
    return f, digest(report), tickets


def freeze(prior, cd_report_hash, tickets, available):
    available = positive(available)
    require(available <= Decimal(prior['remainingBudgetUsd']) and set(tickets) == {'C','D'},
            'do not pool budgets or omit C/D')
    cases = copy.deepcopy(prior['source']['cases'])
    templates = {j['case']:copy.deepcopy(j) for j in prior['jobs'] if j['arm'] == 'BASELINE'}
    require(set(templates) == {'F1','F2','F3','F4','F5'}, 'same five inputs required')
    for name, ticket in tickets.items():
        require(ticket['requestBody'] == prepare_attempt(ticket['baseRequest'],ticket['selectedScope'],
                    'source-check',judgment_variant='CLOSED_SCOPE')['requestBody'], 'unchanged C/D scope/body required')
        cases.append(dict(name=name,payload=copy.deepcopy(ticket['originalInput']),
            developmentExpectation='REVIEW_REQUIRED',labelStatus='OWNER_DEVELOPMENT_ONLY',
            labelBasis='EXISTING_USER_APPROVED_C_D_BOUNDARY', sourceAttemptHash=ticket['attemptHash']))
        templates[name] = dict(baseBody=copy.deepcopy(ticket['baseRequest']),
            baseRequestHash=digest(ticket['baseRequest']),fixedSelectedScope=copy.deepcopy(ticket['selectedScope']))
    jobs = []
    for repeat in range(1,4):
        for index, case in enumerate(cases):
            template = templates[case['name']]
            for arm in (('BASELINE','CANDIDATE') if (repeat+index)%2 else ('CANDIDATE','BASELINE')):
                variant = 'CLOSED_SCOPE' if arm == 'BASELINE' else 'TARGET_FOCUS_CLOSED_SCOPE'
                body = prepare_attempt(template['baseBody'],template['fixedSelectedScope'],'freeze-preview',
                                       judgment_variant=variant)['requestBody']
                tokens, cost = reserve(body,prior['config'])
                jobs.append(dict(case=case['name'],arm=arm,repeat=repeat,
                    baseBody=copy.deepcopy(template['baseBody']),baseRequestHash=template['baseRequestHash'],
                    fixedSelectedScope=copy.deepcopy(template['fixedSelectedScope']),judgmentVariant=variant,
                    stages=[dict(stage='JUDGE',body=body,requestHash=digest(body),
                        reservedInputTokensHeuristic=tokens,reservedCostUsd=str(cost))]))
    total = sum(Decimal(j['stages'][0]['reservedCostUsd']) for j in jobs)
    require(len(jobs) == 42 and total <= available, 'all 42 judgments must fit remaining approval')
    result = dict(schemaVersion=prior['schemaVersion'],targetFocusTrial='REGRESSION_THREE_REPEATS_1',
        previewHash=PREVIEW_HASH,priorFocusHash=digest(prior),cdSourceReportHash=cd_report_hash,
        source=dict(cases=cases),config=copy.deepcopy(prior['config']),
        authorization=copy.deepcopy(prior['authorization']),authorizationHash=prior['authorizationHash'],
        additionalFundingHash=prior['additionalFundingHash'],availableBudgetUsd=str(available),
        reservedCostUsd=str(total),remainingBudgetUsd=str(available-total),jobs=jobs,
        plannedUnits=42,maxApiCalls=42,repetitions=3,repetitionsPerCondition=3,
        requestTimeoutSeconds=90,maxConcurrency=1,reportSerialization='bundle-1',
        executionEnabled=True,budgetSufficient=True,normalControlsIncluded=True,
        automaticSelectionEvaluated=False,fullPipelineReplayed=False,runtimeChanged=False,
        qualityGateMet=None,semanticQualityGateMet=None)
    result['artifactHash'] = digest(result)
    return result


def prepare(available): return freeze(*source_inputs(),available)
def validate(frozen): require(frozen == prepare(frozen['availableBudgetUsd']), 'regression frozen plan changed')


def score(frozen, report, audit):
    validate(frozen)
    result = score_validated(frozen,report,audit)
    summary = []
    for case in frozen['source']['cases']:
        for arm in ('BASELINE','CANDIDATE'):
            rows = [r for r in result['results'] if (r['case'],r['arm']) == (case['name'],arm)]
            summary.append(dict(case=case['name'],arm=arm,observations=len(rows),
                decisions=dict(Counter(r['assessment']['decision'] if r['assessment'] else 'CONTRACT_REJECTED' for r in rows)),
                accepted=sum(r['accepted'] for r in rows),
                developmentDecisionMatches=sum(r['matchedDevelopmentDecision'] for r in rows)
                    if report['runKind'] == 'LIVE' else None,semanticIssueMatched=None))
    result.update(schemaVersion='target-focus-regression-score-1',summary=summary,
                  repetitionsPerCondition=3,independentGoldApprovals=0,runtimeUseAuthorized=False)
    return result
