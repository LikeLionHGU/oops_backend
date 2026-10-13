"""Freeze BCD + four synthetic controls, comparing wire contracts only."""
import argparse
import copy
from decimal import Decimal
import json
from pathlib import Path
from fixed_review_suite import read,digest,require,write_private
from run_boundary_comparison import reserve
from target_wire_contract import prompt,VERSION


def prepare(pattern, boundary, prior, authorization):
    require(authorization == {'schemaVersion':'additional-evaluation-budget-1','additionalBudgetUsd':'.5',
                             'priorReservedCostUsd':'0.4994146','userAuthorized':True}, 'explicit new authorization required')
    require(prior['artifactHash'] == boundary['artifactHash'] and prior['reservedCostUsd'] == '0.0537028'
            and prior['status'] == 'COMPLETED_PENDING_JAVA_AUDIT', 'previous ledger mismatch')
    for a in (pattern,boundary):
        require(a['artifactHash'] == digest({k:v for k,v in a.items() if k != 'artifactHash'}), 'source artifact changed')
    cases = copy.deepcopy(pattern['cases']) + copy.deepcopy(boundary['cases'][1:])
    require([c['name'] for c in cases] == ['B','C','D','N1','N2','N3','N4'], 'fixed seven cases required')
    templates = {(r['case'],r['arm']):r['body'] for r in pattern['requests']}
    normals = {(r['case'],r['arm']):r['body'] for r in boundary['requests']}
    requests = []
    for repeat in range(1,4):
        for case in cases:
            body = copy.deepcopy((templates if case['name'] in 'BCD' else normals)[case['name'],'CANDIDATE'])
            # Both arms use the refined reference. Normal arms do NOT inherit C's rejected appended instructions.
            body['max_completion_tokens'] = 4000
            for arm in (('BASELINE','CANDIDATE') if repeat % 2 else ('CANDIDATE','BASELINE')):
                request = copy.deepcopy(body)
                if arm == 'CANDIDATE': request['messages'][0]['content'] = prompt(request['messages'][0]['content'])
                tokens,cost = reserve(request,pattern['config'])
                require(tokens < 272000,'long context unsupported')
                row = {'case':case['name'],'arm':arm,'repeat':repeat,'body':request,'requestHash':digest(request),
                       'reservedInputTokensHeuristic':tokens,'reservedCostUsd':str(cost)}
                if arm == 'CANDIDATE': row['outputAdapter'] = VERSION
                requests.append(row)
    total = sum(Decimal(r['reservedCostUsd']) for r in requests)
    require(total <= Decimal('.5'),'additional budget exceeded')
    result = {'schemaVersion':'recorded-target-wire-trial-1','plannedCalls':42,'additionalBudgetUsd':'.5',
              'priorReservedCostUsd':'0.4994146','remainingBudgetUsd':'.5','reservedTotalCostUsd':str(total),
              'authorizationHash':digest(authorization),'sourceArtifactHashes':[pattern['artifactHash'],boundary['artifactHash']],
              'previousReportHash':digest(prior),'config':pattern['config'],'cases':cases,'requests':requests,
              'normalControlsIncluded':True,'fullPipelineReplayed':False,'runtimeChanged':False,'qualityGateMet':None}
    result['artifactHash'] = digest(result)
    return result


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    for f in ('pattern','boundary','previous-report','authorization','output'): p.add_argument('--'+f,required=True)
    a = p.parse_args()
    result = prepare(read(a.pattern),read(a.boundary),read(a.previous_report),read(a.authorization))
    path = Path(a.output).resolve()
    require('datasets' in path.parts and not path.exists(),'new private output required')
    write_private(path,result)
    print(json.dumps({'plannedCalls':42,'reservedCostUsd':result['reservedTotalCostUsd'],'executed':False}))
