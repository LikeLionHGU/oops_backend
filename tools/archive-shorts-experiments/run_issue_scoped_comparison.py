"""Bounded, explicit paid comparison of one-stage and evidence-first review.

New authorization only; never refunds prior reservations, retries, resumes,
deploys, labels semantics, or changes original responses. Draft mode is default.
"""
import argparse
import copy
from concurrent.futures import ThreadPoolExecutor, as_completed
from decimal import Decimal
import json
from pathlib import Path
import threading
import time
import urllib.error

from collect_youtube_comments import read_key
from compare_recorded_candidates import checked_output
from issue_scoped_reporting import build_report, save_reports
from fixed_review_suite import digest, read, require, write_private
from issue_scoped_review import hydrate_selection, judgment_request, review_packet
from experiment_costs import positive, reserve
from run_boundary_comparison import transport

# Compatibility exports: callers may keep the existing preparation entry points.
# The implementation and approval snapshots now belong to issue_scoped_recipes.
from issue_scoped_recipes import (
    SCHEMA, SCOPE_BYTES, AUTHORIZATION, CATALOG_SCHEMA, BOUND_SCHEMA,
    BOUND_FUNDING, BOUNDARY_FUNDING, prepare, validate, prepare_catalog,
    prepare_bound, fund_bound, prepare_target_selection, prepare_full_boundary,
    bound_judgment_request,
)


def execute(artifact, directory, key, call=transport, *, simulate=False, ledger_path=None, env_file=None):
    validate(artifact)
    is_bound = artifact['schemaVersion'] == BOUND_SCHEMA
    if simulate:
        require(is_bound and call is not transport and key is None, 'mock requires explicit callback, no key')
    elif is_bound:
        require(artifact['executionEnabled'] and artifact['budgetSufficient'], 'insufficient remaining reservation; no calls')
    directory = Path(directory).resolve()
    require('datasets' in directory.parts and not directory.exists(), 'new private execution directory required')
    reservation = None
    if not simulate:
        from reservation_ledger import claim, DEFAULT_PATH
        reservation = claim(ledger_path or DEFAULT_PATH, artifact, directory)
        # A caller may pass its own key, but the CLI loads it only after claiming.
        if key is None: key = read_key(env_file,key_name='OPENAI_API_KEY')
    directory.mkdir(parents=True, mode=0o700)
    write_private(directory/'frozen.json', artifact)
    if reservation is not None: write_private(directory/'reservation.json',reservation)
    stop = threading.Event()
    cases = {c['name']:c for c in artifact['source']['cases']}
    is_catalog=artifact['schemaVersion'] in {CATALOG_SCHEMA, BOUND_SCHEMA}
    if is_catalog:
        from span_catalog_review import hydrate as hydrate_scope, judgment_request as judge_request
        if is_bound:
            from bound_review_contract import judgment_request as judge_request, prepare_attempt, receive, parse_response
            judge_request = lambda base, scope: bound_judgment_request(artifact, base, scope)
    else:
        hydrate_scope,judge_request=hydrate_selection,judgment_request

    def unit(index, job):
        if stop.is_set(): return None
        result = {k:job[k] for k in ('case','arm','repeat')}
        result.update(unitIndex=index, stages=[], requestHash=job['baseRequestHash'], status='PARTIAL')
        payload = cases[job['case']]['payload']
        effective_base=copy.deepcopy(job['baseBody'])
        scope = copy.deepcopy(job.get('fixedSelectedScope'))
        if scope is not None:
            result['selectedScope'] = copy.deepcopy(scope)
        for stage in job['stages']:
            if stop.is_set(): result['status']='STOPPED_BEFORE_STAGE'; break
            body = (bound_judgment_request(artifact,effective_base,scope,variant=job.get('judgmentVariant'))
                    if is_bound and stage['stage']=='JUDGE' else
                    judge_request(job['baseBody'],scope) if stage['stage']=='JUDGE' else stage['body'])
            actual_tokens, _ = reserve(body, artifact['config'])
            require(actual_tokens <= stage['reservedInputTokensHeuristic'], 'dynamic request exceeds reservation')
            attempt = dict(stage=stage['stage'], requestHash=digest(body), body=body)
            ticket = None
            if is_bound and stage['stage'] == 'JUDGE':
                ticket = prepare_attempt(effective_base, scope, f'{artifact["artifactHash"]}-{index:03d}',
                                         judgment_variant=job.get('judgmentVariant',artifact.get('judgmentVariant')))
                attempt['localAttempt'] = ticket
            write_private(directory/f'attempt-{index:03d}-{stage["stage"]}.json', attempt)
            started = time.monotonic()
            try:
                response = call(key, body, timeout=artifact['requestTimeoutSeconds'])
                attempt['providerResponse'] = response
                usage = response.get('usage', {})
                require(all(type(usage.get(k)) is int and usage[k] >= 0 for k in ('prompt_tokens','completion_tokens')), 'usage unavailable')
                price = artifact['config']['pricing']
                cost = (Decimal(usage['prompt_tokens'])*positive(price['inputUsdPerMillion'])
                        + Decimal(usage['completion_tokens'])*positive(price['outputUsdPerMillion']))/1000000
                attempt.update(usage=usage, estimatedCostUsd=str(cost))
                require(response.get('model') == 'gpt-6-luna' and usage['completion_tokens'] <= body['max_completion_tokens']
                        and usage['prompt_tokens'] <= stage['reservedInputTokensHeuristic']
                        and cost <= Decimal(stage['reservedCostUsd']), 'provider model/usage exceeds reservation')
                choice = response['choices'][0]
                require(choice['finish_reason'] == 'stop' and not choice['message'].get('refusal'), 'incomplete or refused response')
                raw = parse_response(response, body['model']) if is_bound else json.loads(choice['message']['content'])
                attempt['rawOutput'] = raw
                if stage['stage']=='LOCATE':
                    from automatic_anchor_diagnostic import accept
                    try:
                        chosen=accept(raw,effective_base,scope)
                        effective_base=chosen['baseBody'];scope=chosen['scope']
                        payload=json.loads(effective_base['messages'][1]['content']);c=payload['candidates'][0]
                        result.update(anchorAction=chosen['status'],selectedScope=copy.deepcopy(scope),
                            actualCandidateProposal=dict(axis=c['axis'],anchorId=c['anchorId'],
                                reason=c['hypothesisNotEvidence'],evidence=copy.deepcopy(c['proposedEvidence'])))
                    except (ValueError,KeyError,TypeError) as error:
                        result.update(status='ANCHOR_REJECTED',wireAdaptationError='ANCHOR_CONTRACT_REJECTED',contractError=str(error))
                elif stage['stage'] == 'VERIFY':
                    flat = dict(providerResponse=response, outputAdapter='single-target-evidence-wire-1')
                    checked_output(flat, payload)
                    result.update({k:v for k,v in flat.items() if k != 'providerResponse'})
                    if 'output' not in result: result['wireAdaptationError']='BASELINE_WIRE_REJECTED'
                elif stage['stage'] == 'SELECT':
                    try:
                        scope = hydrate_scope(raw, payload)
                        require(len(json.dumps(scope,ensure_ascii=False).encode()) <= SCOPE_BYTES, 'scope envelope exceeded')
                        result['selectedScope'] = scope
                        if 'contextReselectionStudy' in artifact:
                            previous = job['previousAttempt']['selectedScope']
                            result['contextTransition'] = dict(
                                priorReceiptHash=job['previousReceipt']['receiptHash'],
                                previousScopeHash=previous['scopeHash'], newScopeHash=scope['scopeHash'],
                                previousEvidence=copy.deepcopy(previous['evidence']),
                                newEvidence=copy.deepcopy(scope['evidence']), reselectionsUsed=1,
                                semanticQualityGateMet=None, runtimeUseAuthorized=False)
                    except (ValueError, KeyError, TypeError) as error:
                        result.update(status='SELECTION_REJECTED', wireAdaptationError='SELECTION_CONTRACT_REJECTED',
                                      contractError=str(error))
                else:
                    try:
                        if is_bound:
                            receipt = receive(ticket, body, response)
                            attempt['boundReceipt'] = receipt
                            packet = receipt['compatiblePacket']
                        else:
                            packet = review_packet(raw, scope, payload)
                        result.update(reviewPacket=packet, output=packet['compatibleAssessment'], status='CONTRACT_ONLY')
                    except (ValueError, KeyError, TypeError) as error:
                        result.update(status='JUDGMENT_REJECTED', wireAdaptationError='JUDGMENT_CONTRACT_REJECTED',
                                      contractError=str(error))
            except Exception as error:
                attempt.update(error=type(error).__name__, status='FAILED_STOP')
                # Future diagnostics only: never persist exception text, URL or
                # error response body, which can contain credentials/content.
                if isinstance(error, urllib.error.HTTPError) and type(error.code) is int and 100 <= error.code <= 599:
                    attempt['httpStatus'] = error.code
                result.update(error=type(error).__name__, status='FAILED_STOP')
                stop.set()
            attempt['latencyMs'] = round((time.monotonic()-started)*1000)
            result['stages'].append(attempt)
            write_private(directory/f'result-{index:03d}-{stage["stage"]}.json', attempt)
            if 'error' in attempt or result.get('wireAdaptationError'): break
        result['latencyMs'] = sum(s['latencyMs'] for s in result['stages'])
        if result['status'] == 'PARTIAL': result['status']='CONTRACT_ONLY'
        write_private(directory/f'unit-{index:03d}.json', result)
        return result

    results = []
    with ThreadPoolExecutor(max_workers=artifact['maxConcurrency']) as pool:
        pending = [pool.submit(unit,i,j) for i,j in enumerate(artifact['jobs'],1)]
        for future in as_completed(pending):
            value = future.result()
            if value is None: continue
            results.append(value)
            print(dict(units=len(results),case=value['case'],arm=value['arm'],repeat=value['repeat'],status=value['status']),flush=True)
    results.sort(key=lambda r:r['unitIndex'])
    report = build_report(artifact, results, stopped=stop.is_set(), is_bound=is_bound, simulate=simulate)
    save_reports(directory, artifact, report)
    return report


if __name__ == '__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source'); p.add_argument('--authorization'); p.add_argument('--output',required=True)
    p.add_argument('--frozen'); p.add_argument('--execute',action='store_true'); p.add_argument('--env-file')
    p.add_argument('--prior-report')
    p.add_argument('--bound-catalog', help='prepare server-bound follow-up from unchanged catalog snapshot')
    a=p.parse_args()
    if a.execute:
        require(a.frozen and not a.source and not a.authorization,'explicit frozen execution required')
        frozen = read(a.frozen)
        validate(frozen)
        if frozen['schemaVersion'] == BOUND_SCHEMA:
            require(frozen['executionEnabled'], 'insufficient remaining reservation; key not loaded')
        execute(frozen,Path(a.output),None,env_file=a.env_file)
    else:
        require(a.source and a.authorization and not a.frozen,'source and new authorization required')
        require(not (a.bound_catalog and a.prior_report), 'choose one follow-up version')
        frozen = prepare_bound(read(a.source), read(a.authorization), read(a.bound_catalog)) if a.bound_catalog else (prepare_catalog(read(a.source),read(a.authorization),read(a.prior_report)) if a.prior_report else prepare(read(a.source),read(a.authorization)))
        write_private(Path(a.output).resolve(),frozen)
        print(dict(maxApiCalls=frozen['maxApiCalls'],reservedCostUsd=frozen['reservedCostUsd'],executed=False))
