"""Freeze ID-selection comparison and debit the separate additional budget."""
import argparse
import copy
import json
from decimal import Decimal
from pathlib import Path
from fixed_review_suite import read, digest, require, write_private
from run_boundary_comparison import reserve
from evidence_selection_contract import prompt, VERSION


def prepare(source, prior, authorization):
    require(source['artifactHash'] == digest({k:v for k,v in source.items() if k != 'artifactHash'}),
            'source changed')
    require(authorization == {'schemaVersion':'additional-evaluation-budget-1','additionalBudgetUsd':'.5',
                             'priorReservedCostUsd':'0.4994146','userAuthorized':True}, 'authorization required')
    require(prior['artifactHash'] == source['artifactHash'] and prior['reservedCostUsd'] == '0.1829466'
            and prior['status'] == 'COMPLETED_PENDING_JAVA_AUDIT' and len(prior['calls']) == 42,
            'prior reservation ledger mismatch')
    require([c['name'] for c in source['cases']] == ['B','C','D','N1','N2','N3','N4'], 'fixed cases required')
    templates = {r['case']:r['body'] for r in source['requests'] if r['arm'] == 'BASELINE'}
    requests = []
    for repeat in range(1,4):
        for case in source['cases']:
            for arm in (('BASELINE','CANDIDATE') if repeat % 2 else ('CANDIDATE','BASELINE')):
                body = copy.deepcopy(templates[case['name']])
                if arm == 'CANDIDATE': body['messages'][0]['content'] = prompt(body['messages'][0]['content'])
                tokens, cost = reserve(body, source['config'])
                require(tokens < 272000, 'long context unsupported')
                row = dict(case=case['name'], arm=arm, repeat=repeat, body=body, requestHash=digest(body),
                           reservedInputTokensHeuristic=tokens, reservedCostUsd=str(cost))
                if arm == 'CANDIDATE': row['outputAdapter'] = VERSION
                requests.append(row)
    remaining = Decimal('.5') - Decimal(prior['reservedCostUsd'])
    total = sum(Decimal(r['reservedCostUsd']) for r in requests)
    require(total <= remaining, 'remaining authorization exceeded')
    result = dict(schemaVersion='recorded-evidence-selection-trial-1', plannedCalls=42,
                  additionalBudgetUsd='.5', priorReservedCostUsd='0.4994146',
                  priorAdditionalReservedCostUsd=prior['reservedCostUsd'], previousReportHash=digest(prior),
                  authorizationHash=digest(authorization), sourceArtifactHash=source['artifactHash'],
                  remainingBudgetUsd=str(remaining), reservedTotalCostUsd=str(total),
                  cases=copy.deepcopy(source['cases']), config=source['config'], requests=requests,
                  normalControlsIncluded=True, fullPipelineReplayed=False, runtimeChanged=False, qualityGateMet=None)
    result['artifactHash'] = digest(result)
    return result


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('source','previous-report','authorization','output'): p.add_argument('--'+name, required=True)
    a = p.parse_args()
    result = prepare(read(a.source),read(a.previous_report),read(a.authorization))
    path = Path(a.output).resolve()
    require('datasets' in path.parts and not path.exists(), 'new private output required')
    write_private(path,result)
    print(json.dumps({'plannedCalls':42,'reservedCostUsd':result['reservedTotalCostUsd'],
                      'remainingBeforeUsd':result['remainingBudgetUsd'],'executed':False}))
