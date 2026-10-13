"""Offline, source-bound selection comparison; no transport or label promotion.

An assistant-selected diagnostic scope is not a successful automatic selector.
Owner development expectations stay outside model inputs. Changed evidence is
an experimental bundle, not proof that one omitted phrase caused a miss.
"""
import copy
from decimal import Decimal

from bound_review_contract import prepare_attempt, validate_receipt
from fixed_review_suite import digest, require
from run_boundary_comparison import reserve
from span_catalog_review import hydrate


def difference(previous, current):
    """Compare exact source slices, not just row IDs (TARGET may be partial)."""
    before, after = previous['evidence'], current['evidence']
    return dict(added=[copy.deepcopy(e) for e in after if e not in before],
                removed=[copy.deepcopy(e) for e in before if e not in after],
                retained=[copy.deepcopy(e) for e in after if e in before])


def prepare(records, specification, config):
    require(isinstance(records, list) and 1 <= len(records) <= 20, 'bounded source records required')
    require(set(specification) == {'schemaVersion','conditions'}
            and specification['schemaVersion'] == 'context-boundary-specification-1'
            and isinstance(specification['conditions'],list), 'explicit scope specifications required')
    by_id = {r['id']:r for r in records}
    specs = {s['id']:s for s in specification['conditions']}
    require(len(by_id)==len(records) and len(specs)==len(specification['conditions'])
            and set(by_id)==set(specs), 'unique complete conditions required')
    require(config['maxCompletionTokens']==8000, 'matching output reservation required')
    views, requests = [], []
    for i, record in enumerate(records):
        require(set(record)=={'id','attempt','receipt'}, 'source record fields invalid')
        ticket, receipt = record['attempt'], record['receipt']
        validate_receipt(receipt, ticket)
        require(ticket.get('judgmentVariant')=='CLOSED_SCOPE', 'closed source boundary required')
        base = ticket['baseRequest']
        require(base['model']=='gpt-6-luna' and base['reasoning_effort']=='low'
                and base['max_completion_tokens']==8000 and base['store'] is False
                and base['response_format']=={'type':'json_object'} and 'temperature' not in base,
                'same explicit model settings required')
        spec = specs[record['id']]
        require(set(spec)=={'id','mode','candidateSelection','preservedEvidence',
                           'developmentExpectedDecision','labelStatus'}
                and spec['mode'] in {'ASSISTANT_DIAGNOSTIC_SELECTION','UNCHANGED_NORMAL_CONTROL'}
                and spec['labelStatus']=='OWNER_DEVELOPMENT_ONLY'
                and spec['developmentExpectedDecision'] in {'PASS','REVIEW_REQUIRED'},
                'diagnostic selection and existing development label required')
        old = ticket['selectedScope']
        if spec['mode']=='UNCHANGED_NORMAL_CONTROL':
            require(spec['candidateSelection'] is None and spec['developmentExpectedDecision']=='PASS'
                    and receipt['modelOutput']['assessment']['decision']=='PASS',
                    'normal control must retain its recorded PASS input')
            new = copy.deepcopy(old)
        else:
            require(spec['developmentExpectedDecision']=='REVIEW_REQUIRED',
                    'do not invent a new outcome label')
            new = hydrate(spec['candidateSelection'],ticket['originalInput'])
        require(isinstance(spec['preservedEvidence'],list) and spec['preservedEvidence']
                and all(isinstance(e,dict) and set(e)=={'segmentId','role','quote'}
                        and e['role']=='CONTEXT' and e in old['evidence'] and e in new['evidence']
                        for e in spec['preservedEvidence']),
                'explicit existing normal context must survive the diagnostic change')
        variants = {}
        for arm,scope in [('BASELINE',old),('CANDIDATE',new)]:
            attempt = prepare_attempt(base,scope,f'{record["id"]}-{arm.lower()}-draft',
                                      judgment_variant='CLOSED_SCOPE')
            tokens,cost = reserve(attempt['requestBody'],config)
            variants[arm] = attempt
            requests.append(dict(conditionId=record['id'],arm=arm,attempt=attempt,
                reservedInputTokensHeuristic=tokens,plannedCostUpperEstimateUsd=str(cost)))
        require(variants['BASELINE']['requestBody']['messages'][0]['content'] ==
                variants['CANDIDATE']['requestBody']['messages'][0]['content'],
                'selection-only study must not change judgment prompt/reference')
        if spec['mode']=='UNCHANGED_NORMAL_CONTROL':
            require(variants['BASELINE']['requestBody']==variants['CANDIDATE']['requestBody'],
                    'normal input cannot change between arms')
        views.append(dict(id=record['id'],selectionOrigin=spec['mode'],
            sourceAttemptHash=ticket['attemptHash'],sourceReceiptHash=receipt['receiptHash'],
            fullOriginalHash=ticket['inputHash'],sourceDecision=receipt['modelOutput']['assessment']['decision'],
            developmentExpectedDecision=spec['developmentExpectedDecision'],labelStatus=spec['labelStatus'],
            previousScope=copy.deepcopy(old),diagnosticScope=new,difference=difference(old,new),
            preservedEvidence=copy.deepcopy(spec['preservedEvidence']),
            actualTargetMeaningApproved=False))
    # Order rotation is explicit; a preview is not a budget reservation or call.
    ordered = []
    for i,record in enumerate(records):
        arms = ('BASELINE','CANDIDATE') if i%2==0 else ('CANDIDATE','BASELINE')
        ordered.extend(next(r for r in requests if r['conditionId']==record['id'] and r['arm']==arm)
                       for arm in arms)
    result = dict(schemaVersion='context-boundary-packet-1',status='OFFLINE_PREVIEW_NOT_EXECUTABLE',
        recordsHash=digest(records),specificationHash=digest(specification),config=copy.deepcopy(config),
        views=views,requests=ordered,plannedApiCalls=len(ordered),repetitionsPerCondition=1,
        plannedCostUpperEstimateUsd=str(sum(Decimal(r['plannedCostUpperEstimateUsd']) for r in ordered)),
        reservedCostUsd='0',apiCalls=0,executionEnabled=False,fullPipelineReplayed=False,
        automaticSelectionEvaluated=False,independentGoldApprovals=0,
        qualityGateMet=None,runtimeUseAuthorized=False,trainingUseAuthorized=False,
        blockingReasons=['EXECUTION_LEDGER_NOT_FROZEN','DIAGNOSTIC_SCOPE_SEMANTICS_UNAPPROVED',
                         'SINGLE_OBSERVATION_NOT_STABILITY_OR_GENERALIZATION'])
    result['artifactHash']=digest(result)
    return result


def validate(packet, records, specification):
    require(packet==prepare(records,specification,packet['config']), 'boundary packet changed')
