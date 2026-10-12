"""Offline case-explanation-only ablation. No API executor or default writes."""
import copy
import json

from bound_review_contract import prepare_attempt
from build_review_guidelines import prompt_rules
from experiment_costs import reserve
from fixed_review_suite import digest, require


def revise(base, patch):
    require(base.get('schemaVersion') == 'review-guidelines-4'
            and base.get('humanValidated') is False
            and base.get('status') == 'WORKING_REFERENCE_NOT_VALIDATED', 'working v4 required')
    require(set(patch) == {'schemaVersion', 'status', 'humanApproved', 'runtimeEligible',
                          'trainingUseAuthorized', 'caseId', 'previousContextHash',
                          'criticismHypotheses', 'annotationBasis'}
            and patch.get('annotationBasis') == 'ASSISTANT_HYPOTHESIS_NOT_REACTION_GOLD',
            'explanation-only draft fields required')
    require(patch.get('schemaVersion') == 'case-explanation-patch-1'
            and patch.get('status') == 'UNREVIEWED_DRAFT'
            and all(patch.get(k) is False for k in ('humanApproved', 'runtimeEligible', 'trainingUseAuthorized')),
            'unapproved draft patch required')
    matches = [e for e in base['examples'] if e['id'] == patch['caseId']]
    require(len(matches) == 1 and digest(matches[0]['context']) == patch['previousContextHash'],
            'unique unchanged source context required')
    hypotheses = patch['criticismHypotheses']
    require(isinstance(hypotheses, list) and 1 <= len(hypotheses) <= 6
            and all(isinstance(s, str) and 0 < len(s.strip()) <= 350 for s in hypotheses),
            'bounded explanation required')
    require(hypotheses != matches[0]['context'].get('criticismHypotheses'), 'explanation must actually change')
    result = copy.deepcopy(base)
    target = next(e for e in result['examples'] if e['id'] == patch['caseId'])
    target['context']['criticismHypotheses'] = copy.deepcopy(hypotheses)
    result['version'] = '2026-10-12-case-explanation-only-draft-1'
    result['contextDictionarySha256'] = digest({'base': base['contextDictionarySha256'], 'patch': patch})
    return result


def request_body(body, base, candidate, case_id):
    result = copy.deepcopy(body)
    system = result['messages'][0]['content']
    require(system.count('contextReference=') == 1, 'single reference required')
    prefix, tail = system.split('contextReference=', 1)
    reference, end = json.JSONDecoder().raw_decode(tail)
    require(reference['reviewGuidelines'] == prompt_rules(base, 'SPEECH')
            == prompt_rules(candidate, 'SPEECH'), 'common rules must remain unchanged')
    old = next(e for e in base['examples'] if e['id'] == case_id)
    new = next(e for e in candidate['examples'] if e['id'] == case_id)
    changed = 0
    for example in reference['referenceContexts']:
        if example['context'] == old['context']:
            require(example['mechanismIds'] == old['mechanismIds'] == new['mechanismIds'], 'ownership unchanged')
            example['context'] = copy.deepcopy(new['context']); changed += 1
    require(changed <= 1, 'duplicate source context')
    payload = json.dumps(reference, ensure_ascii=False, separators=(',', ':'))
    require(len(payload) <= 6000, 'runtime reference size limit')
    result['messages'][0]['content'] = prefix + 'contextReference=' + payload + tail[end:]
    return result, changed


def prepare(frozen, base, patch):
    require(frozen.get('referenceSplitStudy') == 'BC_RULES_ONLY_ONE_REPEAT_1'
            and frozen.get('artifactHash') == digest({k: v for k, v in frozen.items() if k != 'artifactHash'})
            and frozen['baseArchiveHash'] == digest(base), 'same current baseline required')
    candidate = revise(base, patch)
    units = []
    for source in frozen['jobs']:
        if source['arm'] != 'BASELINE': continue
        require(source['judgmentVariant'] == 'CLOSED_SCOPE', 'fixed judgment required')
        altered, changed = request_body(source['baseBody'], base, candidate, patch['caseId'])
        for arm, body in (('BASELINE', source['baseBody']), ('CANDIDATE', altered)):
            request = prepare_attempt(body, source['fixedSelectedScope'], 'preview',
                                      judgment_variant='CLOSED_SCOPE')['requestBody']
            tokens, cost = reserve(request, frozen['config'])
            units.append(dict(case=source['case'], arm=arm, body=request, requestHash=digest(request),
                sourceScopeHash=digest(source['fixedSelectedScope']), explanationVisible=bool(changed),
                plannedReservationUsd=str(cost), reservedInputTokensHeuristic=tokens))
    require(len(units) == 10 and any(u['explanationVisible'] for u in units), 'ten paired exposed controls required')
    unique = {u['requestHash']: u for u in units}
    from decimal import Decimal
    total = sum(Decimal(u['plannedReservationUsd']) for u in units)
    packet = dict(schemaVersion='case-explanation-ablation-preview-1', status='OFFLINE_NOT_EXECUTABLE',
        baseArchiveHash=digest(base), candidateArchiveHash=digest(candidate), patchHash=digest(patch),
        sourceFrozenHash=digest(frozen), changedField='criticismHypotheses', caseId=patch['caseId'],
        units=units, plannedCalls=10, distinctRequestBodies=len(unique),
        plannedReservationUsd=str(total), consumedReservationUsd='0', apiCalls=0,
        executionEnabled=False, runtimeEligible=False, trainingUseAuthorized=False,
        humanApproved=False, detectionImprovementMeasured=False, qualityGateMet=None)
    packet['artifactHash'] = digest(packet)
    return candidate, packet
