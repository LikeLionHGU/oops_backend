"""Prepare v2 judgment requests from saved SELECT results, without API calls.

This is conditional on earlier selections, not a new end-to-end experiment.
Past JUDGE outputs are never replayed through the new contract.
"""
import argparse
import json
from pathlib import Path

from bound_review_contract import grounding, parse_response, prepare_attempt
from fixed_review_suite import digest, read, require, write_private
from issue_scoped_recipes import CATALOG_SCHEMA, validate
from span_catalog_review import hydrate


def build(frozen, report):
    validate(frozen)
    require(frozen['schemaVersion'] == CATALOG_SCHEMA
            and report.get('schemaVersion') == CATALOG_SCHEMA
            and report.get('artifactHash') == frozen['artifactHash']
            and report.get('authorizationHash') == frozen['authorizationHash']
            and report.get('status') == 'COMPLETED_PENDING_JAVA_AUDIT', 'complete catalog source required')
    calls = report.get('calls')
    require(isinstance(calls, list) and len(calls) == frozen['plannedUnits'] == 27, 'all 27 source units required')
    drafts = []
    for index, (job, unit) in enumerate(zip(frozen['jobs'], calls), 1):
        require(all(job[k] == unit[k] for k in ('case', 'arm', 'repeat'))
                and unit['unitIndex'] == index and unit['requestHash'] == job['baseRequestHash'],
                'source unit identity mismatch')
        stages = [s for s in unit['stages'] if s['stage'] == 'SELECT']
        require(len(stages) == 1, 'one recorded SELECT required')
        stage = stages[0]
        expected_body = job['stages'][0]['body']
        require(stage['body'] == expected_body and stage['requestHash'] == digest(expected_body), 'source SELECT request mismatch')
        raw = parse_response(stage['providerResponse'], expected_body['model'])
        require(raw == stage['rawOutput'], 'saved SELECT output differs from provider response')
        payload = json.loads(job['baseBody']['messages'][1]['content'])
        scope = hydrate(raw, payload)
        require(scope == unit['selectedScope'], 'saved SELECT scope mismatch')
        ticket = prepare_attempt(job['baseBody'], scope, f'bound-draft-{index:03d}')
        drafts.append(dict(case=job['case'], repeat=job['repeat'], sourceUnitIndex=index,
                           sourceSelectResponseHash=digest(stage['providerResponse']),
                           grounding=grounding(scope, payload), attempt=ticket))
    result = dict(schemaVersion='bound-review-offline-draft-1', sourceArtifactHash=frozen['artifactHash'],
                  sourceReportHash=digest(report), drafts=drafts, draftsCount=len(drafts),
                  missingTargetSelections=sum(d['grounding']['targetIssue'] and not d['grounding']['targetSpanSelected'] for d in drafts),
                  apiCalls=0, executionEnabled=False, reservationUsd=None,
                  priorJudgmentsRegraded=False, selectionStageRerun=False, fullPipelineReplayed=False,
                  runtimeChanged=False, semanticQualityGateMet=None, independentGoldApprovals=0)
    result['artifactHash'] = digest(result)
    return result


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--frozen', required=True); p.add_argument('--report', required=True)
    p.add_argument('--output', required=True)
    args = p.parse_args()
    out = Path(args.output).resolve()
    require('datasets' in out.parts and not out.exists(), 'new private draft required')
    draft = build(read(args.frozen), read(args.report))
    write_private(out, draft)
    print({k: draft[k] for k in ('draftsCount', 'missingTargetSelections', 'apiCalls', 'executionEnabled')})
