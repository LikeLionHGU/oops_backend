"""Frozen recipe preparation/validation; no execution or credential loading.

Historical approval snapshots describe past trials, not permission for new calls.
Live execution must still pass the shared runner and reservation ledger.
"""
import copy
from decimal import Decimal
import json

from experiment_costs import reserve
from fixed_review_suite import digest, require
from issue_scoped_review import hydrate_selection, prompts, selection_request
from prepare_flow_regression import validate as validate_source

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
    if 'incidentReferenceStudy' in artifact:
        from run_incident_reference_comparison import validate as validate_incident
        validate_incident(artifact)
        return
    if 'referenceSplitStudy' in artifact:
        from run_reference_split_comparison import validate as validate_reference_split
        validate_reference_split(artifact)
        return
    if 'targetFocusTrial' in artifact:
        from run_target_focus_comparison import validate as validate_focus
        validate_focus(artifact)
        return
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
    if variant in {'TARGET_FOCUS_CLOSED_SCOPE','MECHANISM_FOCUS_CLOSED_SCOPE','COMPACT_REVIEW_CLOSED_SCOPE',
                   'CHRONOLOGY_COMPACT_REVIEW_CLOSED_SCOPE','EXPLICIT_PASS_CLOSED_SCOPE'}:
        from bound_review_contract import prepare_attempt
        return prepare_attempt(base,scope,'request-preview',judgment_variant=variant)['requestBody']
    if variant in {'RAW_REMOVED','CLOSED_SCOPE'}:
        from scoped_judgment_contract import judgment_request as scoped_request
        return scoped_request(base,scope,variant)
    from review_boundary_variants import judgment_request as changed
    return changed(base, scope, variant)
