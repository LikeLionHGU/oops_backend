"""Offline target-focus ablation preview. No credentials, transport or reservation."""
import argparse
import copy
from pathlib import Path

from fixed_review_suite import digest, read, require, write_private
from private_report_bundle import load
from run_context_boundary_comparison import score

from target_focus_policy import DELTA, focused_request as focused_base


def build_preview(frozen, report, verified_score):
    """Called only after source scoring; no auto interpretation of model reasons."""
    require(verified_score['complete'] and verified_score['mechanicalContractGateMet']
            and verified_score['runKind'] == 'LIVE', 'complete audited live source required')
    labels = {c['name']: c['developmentExpectation'] for c in frozen['source']['cases']}
    require(len(labels) == len(frozen['source']['cases'])
            and all(v in {'PASS', 'REVIEW_REQUIRED'} for v in labels.values()), 'explicit unique development labels required')
    groups = {}
    for job, call, observed in zip(frozen['jobs'], report['calls'], verified_score['results'], strict=True):
        require((job['case'], job['arm']) == (call['case'], call['arm']) == (observed['case'], observed['arm']),
                'source observation order changed')
        ticket = call['stages'][0]['localAttempt']
        require(ticket['judgmentVariant'] == 'CLOSED_SCOPE', 'closed judgment source required')
        baseline = ticket['requestBody']
        request_hash = digest(baseline)
        expected = labels[job['case']]
        if request_hash not in groups:
            # CLOSED_SCOPE replaces the base policy when building an attempt.
            # Work on the actually observed body; this preview is intentionally
            # not an executable attempt or a new contract version.
            body = focused_base(baseline)
            restored = copy.deepcopy(body)
            require(restored['messages'][0]['content'].count(DELTA) == 1, 'isolated target-focus block required')
            restored['messages'][0]['content'] = restored['messages'][0]['content'].replace(DELTA, '', 1)
            require(restored == baseline,
                    'only the system target-focus block may change')
            groups[request_hash] = dict(developmentExpectedDecision=expected,
                labelStatus='OWNER_DEVELOPMENT_ONLY', baselineRequest=copy.deepcopy(baseline),
                candidateRequest=body, observations=[], selectedEvidence=copy.deepcopy(ticket['selectedScope']['evidence']))
        group = groups[request_hash]
        require(group['developmentExpectedDecision'] == expected, 'same input has conflicting development labels')
        group['observations'].append(dict(case=job['case'], arm=job['arm'],
            originalAssessment=copy.deepcopy(observed['assessment']), sourceAttemptHash=ticket['attemptHash']))
    require(groups, 'source observations required')
    result = dict(schemaVersion='target-focus-preview-1', status='OFFLINE_NOT_EXECUTABLE',
        frozenHash=digest(frozen), reportHash=digest(report), sourceScoreHash=digest(verified_score),
        delta=DELTA, comparisons=list(groups.values()), plannedApiCalls=2 * len(groups),
        historicalObservations=len(report['calls']), identicalInputGroups=len(groups),
        apiCalls=0, reservedCostUsd='0', executionEnabled=False, runtimeChanged=False,
        independentGoldApprovals=0, semanticQualityGateMet=None,
        runtimeUseAuthorized=False, trainingUseAuthorized=False,
        blockingReasons=['EXECUTOR_AND_BUDGET_NOT_FROZEN', 'C_D_AND_WIDER_NORMAL_REGRESSION_NOT_INCLUDED',
                         'SINGLE_PAIR_NOT_STABILITY_OR_GENERALIZATION', 'TARGET_FOCUS_EFFECT_UNTESTED'])
    if 'PASS' not in labels.values(): result['blockingReasons'].append('NORMAL_CONTROL_MISSING')
    result['artifactHash'] = digest(result)
    return result


def prepare(frozen, report, audit):
    return build_preview(frozen, report, score(frozen, report, audit))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ('frozen', 'report', 'audit', 'output'): parser.add_argument('--' + flag, required=True)
    args = parser.parse_args()
    supplied_output = Path(args.output)
    output = supplied_output.resolve()
    private_root = Path(__file__).resolve().parents[1] / 'datasets'
    require(output.is_relative_to(private_root) and not output.exists() and not supplied_output.is_symlink(),
            'new output inside this repository private datasets required')
    preview = prepare(read(args.frozen), load(args.report), read(args.audit))
    write_private(output, preview)
    print({'executed': False, 'plannedApiCalls': preview['plannedApiCalls'], 'reservedCostUsd': '0'})
