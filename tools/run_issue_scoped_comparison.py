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
from compare_recorded_candidates import checked_output, audit_export
from fixed_review_suite import digest, read, require, write_private
from issue_scoped_review import (hydrate_selection, judgment_request, prompts, review_packet,
                                 selection_request)
from prepare_flow_regression import validate as validate_source
from run_boundary_comparison import positive, reserve, transport

SCHEMA = 'issue-scoped-comparison-1'
SCOPE_BYTES = 32000
AUTHORIZATION = dict(schemaVersion='issue-scoped-budget-1', additionalBudgetUsd='1.00',
                     userAuthorized=True, previousReservedUsd='1.9666938', previousRemainingUsd='0.0257290')
CATALOG_SCHEMA = 'issue-scoped-catalog-followup-1'
BOUND_SCHEMA = 'issue-scoped-bound-followup-2'
BOUND_FUNDING = dict(schemaVersion='bound-followup-budget-1', additionalBudgetUsd='0.50',
                     userAuthorized=True, previousReservedUsd='2.9093844', previousRemainingUsd='0.0573094')
BOUNDARY_FUNDING = dict(schemaVersion='boundary-full-budget-1', additionalBudgetUsd='0.50',
                       userAuthorized=True, previousReservedUsd='3.4001660', previousRemainingUsd='0.0092184')


def prepare(source, authorization):
    validate_source(source)
    require(source['artifactHash'] == digest({k:v for k,v in source.items() if k != 'artifactHash'})
            and source['variant'] == 'TARGET_WIRE' and source.get('ownerControls')
            and authorization == AUTHORIZATION, 'complete source and new separate authorization required')
    require({c['name'] for c in source['cases']} == {'B','C','D','N1','N2','N3','N4','N5','N6'}, 'fixed nine cases')
    templates = {r['case']: r['body'] for r in source['requests'] if r['repeat'] == 1}
    require(all(r['body'] == templates[r['case']] for r in source['requests']), 'identical repeated source required')
    jobs = []
    for repeat in range(1, 4):
        for i, c in enumerate(source['cases']):
            base = copy.deepcopy(templates[c['name']])
            require(base['model'] == 'gpt-6-luna' and base['reasoning_effort'] == 'low'
                    and 'temperature' not in base and base['max_completion_tokens'] == 8000
                    and base['response_format'] == {'type':'json_object'} and base['store'] is False,
                    'fixed low/8k/json_object/store=false model settings required')
            for arm in (('BASELINE','CANDIDATE') if (repeat+i)%2 else ('CANDIDATE','BASELINE')):
                stages = []
                bodies = [('VERIFY', base)] if arm == 'BASELINE' else [('SELECT', selection_request(base))]
                if arm == 'CANDIDATE':
                    padded = copy.deepcopy(base)
                    padded['messages'][0]['content'] = prompts(base['messages'][0]['content'])[1]
                    padded['messages'][1]['content'] = json.dumps(
                        dict(original=c['payload'], selectedScope='x'*SCOPE_BYTES), ensure_ascii=False)
                    bodies.append(('JUDGE', padded))
                for stage, body in bodies:
                    tokens, cost = reserve(body, source['config'])
                    stages.append(dict(stage=stage, body=body, requestHash=digest(body),
                                       reservedInputTokensHeuristic=tokens, reservedCostUsd=str(cost)))
                jobs.append(dict(case=c['name'], arm=arm, repeat=repeat, baseBody=base,
                                 baseRequestHash=digest(base), stages=stages))
    total = sum(Decimal(s['reservedCostUsd']) for j in jobs for s in j['stages'])
    require(total <= Decimal('1.00'), 'full two-stage reservation exceeds new budget')
    result = dict(schemaVersion=SCHEMA, sourceArtifactHash=source['artifactHash'], source=copy.deepcopy(source),
                  authorization=copy.deepcopy(authorization), authorizationHash=digest(authorization),
                  config=copy.deepcopy(source['config']), jobs=jobs, plannedUnits=54, maxApiCalls=81,
                  reservedCostUsd=str(total), scopeEnvelopeByteLimit=SCOPE_BYTES,
                  repetitions=3, requestTimeoutSeconds=90, maxConcurrency=3,
                  fullPipelineReplayed=False, runtimeChanged=False, qualityGateMet=None)
    result['artifactHash'] = digest(result)
    return result


def validate(artifact):
    if 'boundaryInputStudy' in artifact:
        from run_context_boundary_comparison import validate as validate_boundary_input
        validate_boundary_input(artifact)
        return
    if 'contextReselectionStudy' in artifact:
        from prepare_context_reselection import validate as validate_reselection
        validate_reselection(artifact)
        return
    if 'scopedPaidComparison' in artifact:
        from compare_scoped_inputs import validate as validate_paid_scoped
        validate_paid_scoped(artifact)
        return
    if 'scopedInputStudy' in artifact:
        from scoped_input_study import validate as validate_scoped
        validate_scoped(artifact)
        return
    if 'fixedScopeStudy' in artifact:
        from fixed_scope_diagnostic import validate as validate_fixed
        validate_fixed(artifact)
        return
    if artifact.get('schemaVersion') == BOUND_SCHEMA:
        expected = prepare_bound(artifact['source'], artifact['authorization'], artifact['priorCatalog'])
        if 'additionalFunding' in artifact:
            expected = fund_bound(expected, artifact.get('predecessorFunding', artifact['additionalFunding']))
        if 'boundaryFollowup' in artifact:
            link = artifact['boundaryFollowup']
            expected = prepare_full_boundary(expected, link['priorLiveReport'], link['priorTargetReport'],
                                             artifact['additionalFunding'])
        elif 'selectionVariant' in artifact:
            expected = prepare_target_selection(expected, artifact['priorLiveReport'])
    else:
        expected = prepare_catalog(artifact['source'],artifact['authorization'],artifact['priorReport']) if artifact.get('schemaVersion')==CATALOG_SCHEMA else prepare(artifact['source'], artifact['authorization'])
    require(artifact == expected, 'frozen comparison changed')


def prepare_catalog(source,authorization,prior_report):
    from span_catalog_review import selection_request as select, JUDGMENT_FIX
    first=prepare(source,authorization)
    require(prior_report['schemaVersion']==SCHEMA and prior_report['artifactHash']==first['artifactHash']
            and prior_report['authorizationHash']==digest(authorization)
            and prior_report['status']=='COMPLETED_PENDING_JAVA_AUDIT'
            and prior_report['reservedCostUsd']==first['reservedCostUsd'], 'completed prior reservation required')
    first.pop('artifactHash')
    jobs=[j for j in first['jobs'] if j['arm']=='CANDIDATE']
    for j in jobs:
        j['stages'][0]['body']=select(j['baseBody'])
        judge=j['stages'][1]['body']
        marker='# 범용 맥락 참고 기준'
        before,reference=judge['messages'][0]['content'].split(marker,1)
        judge['messages'][0]['content']=before+JUDGMENT_FIX+'\n'+marker+reference
        for s in j['stages']:
            tokens,cost=reserve(s['body'],first['config'])
            s.update(requestHash=digest(s['body']),reservedInputTokensHeuristic=tokens,reservedCostUsd=str(cost))
    total=sum(Decimal(s['reservedCostUsd']) for j in jobs for s in j['stages'])
    remaining=Decimal('1.00')-Decimal(prior_report['reservedCostUsd'])
    require(total<=remaining,'catalog follow-up exceeds remaining new authorization')
    # A budget link needs provenance and totals, not all prior provider bodies.
    # Embedding those would multiply snapshots and exceed the 4 MiB reader.
    fields=('schemaVersion','artifactHash','authorizationHash','status','reservedCostUsd')
    prior_meta={k:prior_report[k] for k in fields}
    prior_meta['sourceReportHash']=prior_report.get('sourceReportHash',digest(prior_report))
    first.update(schemaVersion=CATALOG_SCHEMA,jobs=jobs,plannedUnits=27,maxApiCalls=54,
                 reservedCostUsd=str(total),priorReport=prior_meta,
                 priorReportHash=digest(prior_meta),remainingBudgetUsd=str(remaining),
                 trialDesign='CANDIDATE_ONLY_REGRESSION')
    first['artifactHash']=digest(first)
    return first


def prepare_bound(source, authorization, prior_catalog):
    """Reserve full fresh SELECT/JUDGE; insufficient budget is an explicit block.

    This does not authorize another trial or refund earlier reservations.
    """
    from bound_review_contract import judgment_request as bound_judge
    first = prepare_catalog(source, authorization, prior_catalog['priorReport'])
    fields = ('schemaVersion', 'artifactHash', 'reservedCostUsd', 'priorReport')
    prior_meta = {k: first[k] for k in fields}
    require(prior_catalog == first or prior_catalog == prior_meta, 'unchanged prior catalog snapshot required')
    first.pop('artifactHash')
    for job in first['jobs']:
        payload = json.loads(job['baseBody']['messages'][1]['content'])
        c = payload['candidates'][0]
        row = next(r for r in payload['raw'] if r['id'] == c['anchorId'])
        scope = hydrate_selection(dict(candidateId=c['candidateId'], anchorId=c['anchorId'],
                                       issueSummary='reservation envelope only', selections=[dict(
                                           segmentId=c['anchorId'], role='PRIMARY', start=0, end=len(row['text']))]), payload)
        body = bound_judge(job['baseBody'], scope)
        body['messages'][1]['content'] = json.dumps(dict(original=payload, selectedScope='x'*SCOPE_BYTES,
                                                       grounding='x'*512), ensure_ascii=False)
        tokens, cost = reserve(body, first['config'])
        job['stages'][1].update(body=body, requestHash=digest(body),
                                reservedInputTokensHeuristic=tokens, reservedCostUsd=str(cost))
    total = sum(Decimal(s['reservedCostUsd']) for j in first['jobs'] for s in j['stages'])
    available = (Decimal(authorization['additionalBudgetUsd']) - Decimal(prior_catalog['reservedCostUsd'])
                 - Decimal(prior_catalog['priorReport']['reservedCostUsd']))
    first.update(schemaVersion=BOUND_SCHEMA, priorCatalog=copy.deepcopy(prior_meta),
                 reservedCostUsd=str(total), remainingBudgetUsd=str(available),
                 executionEnabled=total <= available, budgetSufficient=total <= available,
                 trialDesign='FRESH_TWO_STAGE_CANDIDATE_ONLY_REGRESSION')
    first['artifactHash'] = digest(first)
    return first


def fund_bound(blocked, funding):
    """Separate human-approved $0.50; no rollover or refund of prior budgets."""
    require(blocked.get('schemaVersion') == BOUND_SCHEMA and 'additionalFunding' not in blocked,
            'unfunded bound snapshot required')
    require(blocked == prepare_bound(blocked['source'], blocked['authorization'], blocked['priorCatalog'])
            and funding == BOUND_FUNDING and not blocked['executionEnabled']
            and blocked['remainingBudgetUsd'] == funding['previousRemainingUsd'], 'exact new funding required')
    total = Decimal(blocked['reservedCostUsd'])
    require(total <= Decimal(funding['additionalBudgetUsd']), 'full fresh regression exceeds additional approval')
    result = copy.deepcopy(blocked)
    previous_hash = result.pop('artifactHash')
    result.update(additionalFunding=copy.deepcopy(funding), additionalFundingHash=digest(funding),
                  unfundedArtifactHash=previous_hash, executionEnabled=True, budgetSufficient=True,
                  remainingBudgetUsd=str(Decimal(funding['additionalBudgetUsd'])-total),
                  priorBudgetRemainingUsd=funding['previousRemainingUsd'])
    result['artifactHash'] = digest(result)
    return result


def prepare_target_selection(funded, prior_report):
    """Small remaining-budget follow-up; historical comparison, not paired."""
    from review_boundary_variants import selection_request as select
    validate(funded)
    require('additionalFunding' in funded and 'selectionVariant' not in funded, 'funded unchanged source required')
    fields = ('schemaVersion', 'artifactHash', 'additionalFundingHash', 'status', 'reservedCostUsd', 'runKind')
    expected = dict(schemaVersion=BOUND_SCHEMA, artifactHash=funded['artifactHash'],
                    additionalFundingHash=funded['additionalFundingHash'], status='COMPLETED_PENDING_JAVA_AUDIT',
                    reservedCostUsd=funded['reservedCostUsd'], runKind='LIVE')
    require(all(prior_report.get(k) == v for k,v in expected.items()), 'complete prior live reservation required')
    meta = {k: prior_report[k] for k in fields}
    meta['sourceReportHash'] = prior_report.get('sourceReportHash', digest(prior_report))
    result = copy.deepcopy(funded); previous_hash = result.pop('artifactHash')
    jobs = [j for j in result['jobs'] if j['case']=='C' or (j['repeat']==1 and j['case'] in {'B','N3','N4'})]
    require(len(jobs)==6, 'C three repeats and three guard observations required')
    for j in jobs:
        body = select(j['baseBody']); tokens,cost = reserve(body, result['config'])
        j['stages'][0].update(body=body, requestHash=digest(body),
                              reservedInputTokensHeuristic=tokens, reservedCostUsd=str(cost))
    total = sum(Decimal(s['reservedCostUsd']) for j in jobs for s in j['stages'])
    available = Decimal(funded['remainingBudgetUsd'])
    result.update(selectionVariant='TARGET_SELECTION_V1', priorLiveReport=meta,
                  parentFundedArtifactHash=previous_hash, jobs=jobs, plannedUnits=6, maxApiCalls=12,
                  evaluatedCases=[c for c in result['source']['cases'] if c['name'] in {'B','C','N3','N4'}],
                  reservedCostUsd=str(total), remainingBudgetUsd=str(available-total),
                  executionEnabled=total<=available, budgetSufficient=total<=available,
                  trialDesign='TARGET_SELECTION_ONLY_SMALL_REGRESSION')
    result['artifactHash'] = digest(result)
    return result


def prepare_full_boundary(funded, prior_live_report, prior_target_report, funding):
    """Fresh full regression; output hardening bundle, not causal attribution.

    Prior reservations stay consumed. This approval does not include rollover.
    """
    from review_boundary_variants import selection_request_v2
    targeted = prepare_target_selection(funded, prior_live_report)
    require(funding == BOUNDARY_FUNDING and targeted['remainingBudgetUsd'] == funding['previousRemainingUsd'],
            'exact separate full-regression approval required')
    fields = ('schemaVersion', 'artifactHash', 'additionalFundingHash', 'status', 'reservedCostUsd', 'runKind')
    expected = dict(schemaVersion=BOUND_SCHEMA, artifactHash=targeted['artifactHash'],
                    additionalFundingHash=targeted['additionalFundingHash'], status='COMPLETED_PENDING_JAVA_AUDIT',
                    reservedCostUsd=targeted['reservedCostUsd'], runKind='LIVE')
    require(all(prior_target_report.get(k) == v for k,v in expected.items()), 'complete targeted predecessor required')
    target_meta = {k: prior_target_report[k] for k in fields}
    target_meta['sourceReportHash'] = prior_target_report.get('sourceReportHash', digest(prior_target_report))
    result = copy.deepcopy(funded)
    parent_hash = result.pop('artifactHash')
    for job in result['jobs']:
        body = selection_request_v2(job['baseBody'])
        tokens, cost = reserve(body, result['config'])
        job['stages'][0].update(body=body, requestHash=digest(body),
                               reservedInputTokensHeuristic=tokens, reservedCostUsd=str(cost))
        # Use a real hydrated scope to build instructions, then a conservative
        # envelope for all dynamic selections, including their index checklist.
        payload = json.loads(job['baseBody']['messages'][1]['content'])
        c = payload['candidates'][0]
        row = next(r for r in payload['raw'] if r['id'] == c['anchorId'])
        scope = hydrate_selection(dict(candidateId=c['candidateId'], anchorId=c['anchorId'],
            issueSummary='reservation envelope only', selections=[dict(segmentId=c['anchorId'],
                role='PRIMARY', start=0, end=len(row['text']))]), payload)
        body = bound_judgment_request(result, job['baseBody'], scope, variant='RELATION_CHECKLIST')
        body['messages'][1]['content'] = json.dumps(dict(original=payload, selectedScope='x'*SCOPE_BYTES,
            grounding='x'*512, expectedSelectionIndexes=list(range(8))), ensure_ascii=False)
        tokens, cost = reserve(body, result['config'])
        job['stages'][1].update(body=body, requestHash=digest(body),
                               reservedInputTokensHeuristic=tokens, reservedCostUsd=str(cost))
    total = sum(Decimal(s['reservedCostUsd']) for j in result['jobs'] for s in j['stages'])
    require(total <= Decimal(funding['additionalBudgetUsd']), 'full boundary regression exceeds new approval')
    result.update(selectionVariant='TARGET_SELECTION_V2', judgmentVariant='RELATION_CHECKLIST',
        boundaryFollowup=dict(priorLiveReport=targeted['priorLiveReport'], priorTargetReport=target_meta,
                              parentFundedArtifactHash=parent_hash, targetedArtifactHash=targeted['artifactHash']),
        predecessorFunding=copy.deepcopy(funded['additionalFunding']),
        additionalFunding=copy.deepcopy(funding), additionalFundingHash=digest(funding),
        reservedCostUsd=str(total), remainingBudgetUsd=str(Decimal(funding['additionalBudgetUsd'])-total),
        priorBudgetRemainingUsd=funding['previousRemainingUsd'], executionEnabled=True, budgetSufficient=True,
        trialDesign='FRESH_FULL_OUTPUT_HARDENING_BUNDLE_NO_CAUSAL_ATTRIBUTION')
    result['artifactHash'] = digest(result)
    return result


def bound_judgment_request(artifact, base, scope, *, variant=None):
    from bound_review_contract import judgment_request as original
    variant = variant or artifact.get('judgmentVariant')
    if variant is None:
        return original(base, scope)
    if variant in {'RAW_REMOVED','CLOSED_SCOPE'}:
        from scoped_judgment_contract import judgment_request as scoped_request
        return scoped_request(base,scope,variant)
    from review_boundary_variants import judgment_request as changed
    return changed(base, scope, variant)


def execute(artifact, directory, key, call=transport, *, simulate=False):
    validate(artifact)
    is_bound = artifact['schemaVersion'] == BOUND_SCHEMA
    if simulate:
        require(is_bound and call is not transport and key is None, 'mock requires explicit callback, no key')
    elif is_bound:
        require(artifact['executionEnabled'] and artifact['budgetSufficient'], 'insufficient remaining reservation; no calls')
    directory = Path(directory).resolve()
    require('datasets' in directory.parts and not directory.exists(), 'new private execution directory required')
    directory.mkdir(parents=True, mode=0o700)
    write_private(directory/'frozen.json', artifact)
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
        scope = copy.deepcopy(job.get('fixedSelectedScope'))
        if scope is not None:
            result['selectedScope'] = copy.deepcopy(scope)
        for stage in job['stages']:
            if stop.is_set(): result['status']='STOPPED_BEFORE_STAGE'; break
            body = (bound_judgment_request(artifact,job['baseBody'],scope,variant=job.get('judgmentVariant'))
                    if is_bound and stage['stage']=='JUDGE' else
                    judge_request(job['baseBody'],scope) if stage['stage']=='JUDGE' else stage['body'])
            actual_tokens, _ = reserve(body, artifact['config'])
            require(actual_tokens <= stage['reservedInputTokensHeuristic'], 'dynamic request exceeds reservation')
            attempt = dict(stage=stage['stage'], requestHash=digest(body), body=body)
            ticket = None
            if is_bound and stage['stage'] == 'JUDGE':
                ticket = prepare_attempt(job['baseBody'], scope, f'{artifact["artifactHash"]}-{index:03d}',
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
                require(response.get('model') == 'gpt-6-luna' and usage['completion_tokens'] <= 8000
                        and usage['prompt_tokens'] <= stage['reservedInputTokensHeuristic']
                        and cost <= Decimal(stage['reservedCostUsd']), 'provider model/usage exceeds reservation')
                choice = response['choices'][0]
                require(choice['finish_reason'] == 'stop' and not choice['message'].get('refusal'), 'incomplete or refused response')
                raw = parse_response(response, body['model']) if is_bound else json.loads(choice['message']['content'])
                attempt['rawOutput'] = raw
                if stage['stage'] == 'VERIFY':
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
    stages = [s for r in results for s in r['stages']]
    report = dict(schemaVersion=artifact['schemaVersion'], artifactHash=artifact['artifactHash'], authorizationHash=artifact['authorizationHash'],
                  status='COMPLETED_PENDING_JAVA_AUDIT' if len(results)==artifact['plannedUnits'] and not stop.is_set() else 'PARTIAL',
                  cases=copy.deepcopy(artifact.get('evaluatedCases', artifact['source']['cases'])), calls=results,
                  apiCalls=len(stages), reservedCostUsd=artifact['reservedCostUsd'],
                  estimatedCostUsd=str(sum(Decimal(s.get('estimatedCostUsd','0')) for s in stages)),
                  unknownCostAttempts=sum('estimatedCostUsd' not in s for s in stages),
                  actualBilledCostUsd=None, qualityGateMet=None, fullPipelineReplayed=False,
                  normalControlsIncluded=artifact.get('normalControlsIncluded', True),
                  repetitionsPerCondition=artifact.get('repetitionsPerCondition',3))
    if 'boundaryInputStudy' in artifact:
        report.update(boundaryInputStudy=artifact['boundaryInputStudy'],previewHash=artifact['previewHash'],
                      automaticSelectionEvaluated=False,fullRegressionReplayed=False)
    if 'contextReselectionStudy' in artifact:
        report.update(contextReselectionStudy=artifact['contextReselectionStudy'],
                      sourceReportHash=artifact['sourceReportHash'],
                      skipped=copy.deepcopy(artifact['skipped']),
                      freshInitialJudgments=False, fullRegressionReplayed=False)
    if is_bound:
        report.update(runKind='MOCK_OFFLINE' if simulate else 'LIVE', simulatedCalls=len(stages) if simulate else 0,
                      apiCalls=0 if simulate else len(stages),
                      consumedReservationUsd='0' if simulate else artifact['reservedCostUsd'])
        if 'additionalFundingHash' in artifact:
            report['additionalFundingHash'] = artifact['additionalFundingHash']
        if simulate:
            report.update(mockTokenRateEquivalentUsd=report['estimatedCostUsd'], estimatedCostUsd='0',
                          status='MOCK_COMPLETE_PENDING_JAVA_AUDIT' if report['status']=='COMPLETED_PENDING_JAVA_AUDIT' else 'MOCK_PARTIAL')
    if artifact.get('reportSerialization')=='bundle-1':
        from private_report_bundle import save as save_bundle
        save_bundle(directory,report,'report')
    else:
        write_private(directory/'report.json',report)
    # Break in-memory aliases before administrative ID normalization. JSON on
    # disk duplicates shared objects, so audit provenance must use that form.
    exported=audit_export(json.loads(json.dumps(report,ensure_ascii=False)))
    exported.update(trialSchemaVersion='recorded-boundary-input-1' if 'boundaryInputStudy' in artifact else 'recorded-flow-regression-1',
                    repetitionsPerCondition=artifact.get('repetitionsPerCondition',3))
    if artifact.get('reportSerialization')=='bundle-1':
        save_bundle(directory,exported,'audit')
    else:
        write_private(directory/'audit-input.json',exported)
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
        execute(frozen,Path(a.output),read_key(a.env_file,key_name='OPENAI_API_KEY'))
    else:
        require(a.source and a.authorization and not a.frozen,'source and new authorization required')
        require(not (a.bound_catalog and a.prior_report), 'choose one follow-up version')
        frozen = prepare_bound(read(a.source), read(a.authorization), read(a.bound_catalog)) if a.bound_catalog else (prepare_catalog(read(a.source),read(a.authorization),read(a.prior_report)) if a.prior_report else prepare(read(a.source),read(a.authorization)))
        write_private(Path(a.output).resolve(),frozen)
        print(dict(maxApiCalls=frozen['maxApiCalls'],reservedCostUsd=frozen['reservedCostUsd'],executed=False))
