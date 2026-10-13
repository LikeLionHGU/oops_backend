"""Isolate dictionary refinement from prompt/selection changes; no automatic adoption.

Build a recorded 18-call candidate comparison plus a 42-call BCD/normal window
comparison within the existing cumulative budget. References differ, raw and
contracts do not. Development evidence, not an effect of hundreds of cases.
"""
import argparse
import copy
from decimal import Decimal
import json
from pathlib import Path

from fixed_review_suite import digest, read, require, write_private
from compare_recorded_candidates import prepare as recorded_prepare
from run_boundary_comparison import freeze, load_packet, reserve


FIELDS = {'condition','normalContrast','requiredEvidence','missingContext'}


def refine(base, plan):
    require(plan['schemaVersion'] == 'context-pattern-refinement-1'
            and plan['status'] == 'UNREVIEWED_DRAFT'
            and all(plan.get(k) is False for k in ('runtimeUseAuthorized','trainingUseAuthorized','humanApproved')),
            'draft refinement required')
    require(plan['baseArchiveHash'] == digest(base), 'base changed')
    result = copy.deepcopy(base)
    indexed = {r['id']: r for r in result['guidelines']}
    source_ids = {r['caseId'] for r in base['sourceCases']}
    require(len(plan['refinements']) == 1, 'single-pattern experiment only')
    for row in plan['refinements']:
        require(row['mechanismId'] in indexed and set(row['fields']) == FIELDS
                and row['supportingCaseIds'] and set(row['supportingCaseIds']) <= source_ids,
                'explicit existing mechanism and research cases required')
        require(all(isinstance(s,str) and 0 < len(s.strip()) <= 350 for s in row['fields'].values()), 'bounded pattern required')
        indexed[row['mechanismId']].update(row['fields'])
    return result


def replace_reference(system, base, candidate, plan):
    marker = 'contextReference='
    require(system.count(marker) == 1, 'one recorded reference required')
    offset = system.index(marker) + len(marker)
    reference, end = json.JSONDecoder().raw_decode(system[offset:])
    changed = copy.deepcopy(reference)
    rows = {r['id']: r for r in changed['reviewGuidelines']}
    for refinement in plan['refinements']:
        pid = refinement['mechanismId']
        old = next(r for r in base['guidelines'] if r['id'] == pid)
        new = next(r for r in candidate['guidelines'] if r['id'] == pid)
        require(pid in rows and all(rows[pid][f] == old[f] for f in FIELDS), 'captured reference differs from base')
        rows[pid].update({f:new[f] for f in FIELDS})
    # Prefix (judgment/JSON rules), selected contexts and suffix are unchanged.
    result = system[:offset] + json.dumps(changed, ensure_ascii=False, separators=(',',':')) + system[offset+end:]
    return result


def prepare(trace, base, plan, packet, prior_window, prior_reports):
    require(len(prior_reports) == 3, 'all three prior reservations required')
    require(prior_reports[0]['status'] == 'COMPLETED_CONTRACT_CHECK_ONLY'
            and prior_reports[1]['status'] == 'PARTIAL'
            and prior_reports[2]['status'] == 'COMPLETED_PENDING_JAVA_AUDIT', 'prior ledger mismatch')
    spent = sum(Decimal(p['reservedCostUsd']) for p in prior_reports)
    require(all(Decimal(p['reservedCostUsd']).is_finite() and Decimal(p['reservedCostUsd']) > 0 for p in prior_reports),
            'prior reservations required')
    remaining = Decimal('.5') - spent
    candidate = refine(base, plan)
    pricing = prior_window['configuration']['pricing']
    actual = recorded_prepare(trace, prior_reports[0], pricing, prior_reports[1])
    actual['remainingBudgetUsd'] = str(remaining)
    actual['comparisonKind'] = 'REFERENCE_PATTERN_ONLY_NOT_PROCEDURE_DELTA'
    actual['priorReservationLedger'] = [{'reportHash':digest(p),'reservedCostUsd':p['reservedCostUsd']} for p in prior_reports]
    actual['refinementPlanHash'] = digest(plan)
    cases = {c['name']:c for c in actual['cases']}
    for request in actual['requests']:
        body = copy.deepcopy(cases[request['case']]['body'])
        if request['arm'] == 'CANDIDATE':
            body['messages'][0]['content'] = replace_reference(body['messages'][0]['content'], base, candidate, plan)
        request['body'] = body
        request['requestHash'] = digest(body)
        tokens, cost = reserve(body, actual['config'])
        require(tokens < 272000, 'unsupported long-context tier')
        request.update(reservedInputTokensHeuristic=tokens,reservedCostUsd=str(cost))
    actual_cost = sum(Decimal(r['reservedCostUsd']) for r in actual['requests'])
    actual['reservedTotalCostUsd'] = str(actual_cost)
    actual['artifactHash'] = digest({k:v for k,v in actual.items() if k != 'artifactHash'})
    config = copy.deepcopy(prior_window['configuration'])
    config['candidatePrompt'] = config['baselinePrompt']
    config['candidateReferenceSnapshot'] = copy.deepcopy(config['referenceSnapshot'])
    rows = {r['id']:r for r in config['candidateReferenceSnapshot']['guidelines']}
    for change in plan['refinements']:
        pid = change['mechanismId']
        old = next(r for r in base['guidelines'] if r['id'] == pid)
        require(all(rows[pid][f] == old[f] for f in FIELDS), 'window reference differs')
        rows[pid].update(change['fields'])
    config['maxEstimatedCostUsd'] = str(remaining - actual_cost)
    window = freeze(packet, config)
    window_cost = sum(Decimal(r['reservedCostUsd']) for r in window['requests'])
    require(len(window['requests']) == 42 and actual_cost + window_cost <= remaining, 'cumulative budget exceeded')
    provenance = {'schemaVersion':'pattern-refinement-trial-provenance-1','hypothesis':plan['hypothesis'],
        'baseArchiveHash':digest(base),'candidatePatternContentHash':digest(candidate), 'planHash':digest(plan),
        'candidateArtifactHash':actual['artifactHash'],'windowArtifactHash':window['artifactHash'],
        'priorReservedCostUsd':str(spent),'newReservedTotalCostUsd':str(actual_cost+window_cost),
        'cumulativeReservedCostUsd':str(spent+actual_cost+window_cost),'budgetUsd':'.5',
        'sameRecordedRawAndSelectedContexts':True, 'normalControlsIncludedInRecordedTrial':False,
        'normalControlsIncludedInWindowTrial':True,'numberOfNewIncidents':0,
        'hundredsOfCasesEffectMeasured':False,'runtimeChanged':False,'qualityGateMet':None}
    return actual, window, provenance


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for flag in ('trace','base','plan','packet','prior-window-artifact','output-dir'):
        p.add_argument('--'+flag,required=True)
    p.add_argument('--prior-cost-report',action='append',required=True)
    args = p.parse_args()
    actual, window, provenance = prepare(read(args.trace),read(args.base),read(args.plan),load_packet(args.packet),
                                         read(args.prior_window_artifact),[read(path) for path in args.prior_cost_report])
    directory = Path(args.output_dir).resolve()
    require('datasets' in directory.parts and not directory.exists(), 'new private directory required')
    directory.mkdir(parents=True,mode=0o700)
    for name,value in (('recorded-frozen.json',actual),('window-frozen.json',window),('provenance.json',provenance)):
        write_private(directory/name,value)
    print(json.dumps({'plannedCalls':60,**provenance},ensure_ascii=False,indent=2))


if __name__ == '__main__': main()
