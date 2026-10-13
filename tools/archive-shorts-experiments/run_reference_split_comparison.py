"""Pinned one-repeat B/C rule ablation, fixed examples and selected evidence.

No new cases, retrieval, selection, raw edits or runtime adoption. Baseline rules
are refreshed from the pinned current archive on BOTH arms, not historical text.
"""
import copy
import json
from decimal import Decimal
from pathlib import Path

from bound_review_contract import prepare_attempt
from build_review_guidelines import prompt_rules
from experiment_costs import positive, reserve
from fixed_review_suite import digest, read, require
from issue_scoped_recipes import BOUND_SCHEMA
from split_context_reference_draft import split
from run_target_focus_comparison import score_validated

ROOT = Path(__file__).resolve().parents[1] / 'datasets/controversy'
SOURCE_HASH = 'b099449a6aa67682dfa268ec7ed43c8610a4663386468be9cac0ba6f066a67da'
BASE_HASH = '0b80cfb8b9bd3177995686ffd8c9eaa034db1011bee95eec9bbd89a722cebb52'
NAMES = ('F2', 'C', 'D', 'F4', 'F5')


def source_inputs():
    source = read(ROOT / 'evaluation/v1/target-focus-regression-20261012/frozen.json')
    base = read(ROOT / 'guidelines/runtime.json')
    candidate = read(ROOT / 'evaluation/v1/bc-split-draft-20261012/split-runtime.json')
    require(digest(source) == SOURCE_HASH and digest(base) == BASE_HASH,
            'pinned source/current archive required')
    require(candidate == split(base)[0], 'isolated split only; new cases forbidden')
    return source, base, candidate


def reference_body(body, archive, base):
    result = copy.deepcopy(body)
    system = result['messages'][0]['content']
    require(system.count('contextReference=') == 1, 'single reference required')
    prefix, tail = system.split('contextReference=', 1)
    reference, end = json.JSONDecoder().raw_decode(tail)
    require(set(reference) == {'reviewGuidelines', 'referenceContexts'}, 'v4 reference required')
    reference['reviewGuidelines'] = prompt_rules(archive, 'SPEECH')
    ids = []
    for example in reference['referenceContexts']:
        matches = [e for e in base['examples'] if e['context'] == example['context']]
        require(len(matches) == 1, 'saved example must exactly match current archive')
        old = matches[0]
        require(example['mechanismIds'] == old['mechanismIds'], 'source ownership changed')
        new = next(e for e in archive['examples'] if e['id'] == old['id'])
        require(new['context'] == old['context'], 'example text may not change')
        example['mechanismIds'] = copy.deepcopy(new['mechanismIds'])
        ids.append(old['id'])
    encoded = json.dumps(reference, ensure_ascii=False, separators=(',', ':'))
    require(len(encoded) <= 6000, 'reference exceeds runtime size limit')
    result['messages'][0]['content'] = prefix + 'contextReference=' + encoded + tail[end:]
    return result, ids


def freeze(source, base, candidate, available):
    available = positive(available)
    require(available <= Decimal(source['remainingBudgetUsd']), 'cannot increase previous approval')
    require(candidate == split(base)[0], 'only isolated split permitted')
    templates = {j['case']: j for j in source['jobs'] if j['arm'] == 'BASELINE' and j['repeat'] == 1}
    cases = [copy.deepcopy(next(c for c in source['source']['cases'] if c['name'] == n)) for n in NAMES]
    require([c['developmentExpectation'] for c in cases] ==
            ['REVIEW_REQUIRED', 'REVIEW_REQUIRED', 'REVIEW_REQUIRED', 'PASS', 'PASS'], 'fixed labels required')
    jobs, exposure = [], []
    for i, case in enumerate(cases):
        old = templates[case['name']]
        bodies = {}
        for arm, archive in (('BASELINE', base), ('CANDIDATE', candidate)):
            body, ids = reference_body(old['baseBody'], archive, base)
            bodies[arm] = body
            scope = copy.deepcopy(old['fixedSelectedScope'])
            request = prepare_attempt(body, scope, 'freeze-preview', judgment_variant='CLOSED_SCOPE')['requestBody']
            tokens, cost = reserve(request, source['config'])
            jobs.append(dict(case=case['name'], arm=arm, repeat=1, baseBody=body,
                baseRequestHash=digest(body), fixedSelectedScope=scope, judgmentVariant='CLOSED_SCOPE',
                stages=[dict(stage='JUDGE', body=request, requestHash=digest(request),
                             reservedInputTokensHeuristic=tokens, reservedCostUsd=str(cost))]))
            exposure.append(dict(case=case['name'], arm=arm, exampleIds=ids,
                                 baseRequestHash=digest(body), selectedScopeHash=digest(scope)))
        require(bodies['BASELINE']['messages'][1:] == bodies['CANDIDATE']['messages'][1:],
                'original input changed')
        # Alternate arm order without changing the fixed inputs or labels.
        if i % 2: jobs[-2:] = list(reversed(jobs[-2:]))
    total = sum(Decimal(j['stages'][0]['reservedCostUsd']) for j in jobs)
    require(total <= available, 'all ten judgments must fit existing balance')
    result = dict(schemaVersion=BOUND_SCHEMA, referenceSplitStudy='BC_RULES_ONLY_ONE_REPEAT_1',
        previewHash=digest(dict(base=digest(base), candidate=digest(candidate))),
        sourceHash=digest(source), baseArchiveHash=digest(base), candidateArchiveHash=digest(candidate),
        source=dict(cases=cases), config=copy.deepcopy(source['config']),
        authorization=copy.deepcopy(source['authorization']), authorizationHash=source['authorizationHash'],
        additionalFundingHash=source['additionalFundingHash'], availableBudgetUsd=str(available),
        reservedCostUsd=str(total), remainingBudgetUsd=str(available-total), jobs=jobs,
        referenceExposure=exposure, plannedUnits=10, maxApiCalls=10, repetitions=1,
        repetitionsPerCondition=1, requestTimeoutSeconds=90, maxConcurrency=1,
        reportSerialization='bundle-1', executionEnabled=True, budgetSufficient=True,
        normalControlsIncluded=True, automaticSelectionEvaluated=False, fullPipelineReplayed=False,
        runtimeChanged=False, qualityGateMet=None, semanticQualityGateMet=None,
        skippedConditions=['F1 ambiguous target', 'F3 no selected TARGET'], newCasesIncluded=False)
    result['artifactHash'] = digest(result)
    return result


def prepare(available): return freeze(*source_inputs(), available)


def validate(frozen):
    require(frozen == prepare(frozen['availableBudgetUsd']), 'rule comparison plan changed')


def score(frozen, report, audit):
    validate(frozen)
    result = score_validated(frozen, report, audit)
    result.update(schemaVersion='reference-split-score-1', referenceSplitStudy=frozen['referenceSplitStudy'],
                  referenceExposure=copy.deepcopy(frozen['referenceExposure']), newCasesIncluded=False)
    return result
