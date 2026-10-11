"""Bounded replay of recorded candidate requests; no discovery/images/repairs.

The original 18-call preparation compares a procedure delta. Separate frozen
pattern/boundary artifacts can compare references or output instructions.
Common output cap/store=False are explicit departures from original requests.
"""
import argparse
import copy
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import time

from fixed_review_suite import digest, read, require, write_private
from collect_youtube_comments import read_key
from prepare_boundary_trial import DELTA
from replay_draft_cases import verify
from run_boundary_comparison import positive, reserve, transport


def text_hash(value):
    return hashlib.sha256(value.encode()).hexdigest()


def prepare(trace, previous, pricing, prior_attempt=None):
    snapshot = trace['data']['snapshot']
    require(trace['success'] is True and trace['data']['available'] is True
            and snapshot['truncated'] is False and snapshot['droppedRequests'] == 0,
            'complete source trace required')
    require(previous['status'] == 'COMPLETED_CONTRACT_CHECK_ONLY'
            and previous['unknownCostAttempts'] == 0, 'prior cost must be known')
    # Reserve prior upper estimates, rather than assuming cache discounts.
    remaining = Decimal('0.5') - positive(previous['reservedCostUsd'])
    if prior_attempt is not None:
        require(prior_attempt['schemaVersion'] == 'recorded-candidate-results-1'
                and prior_attempt['status'] == 'PARTIAL', 'prior interrupted trial required')
        # Never refund a failed/unknown attempt: conservatively charge the entire
        # previous trial reservation against the cumulative authorization.
        remaining -= positive(prior_attempt['reservedCostUsd'])
    require(remaining > 0, 'authorized cumulative budget exhausted')
    cases = []
    require(len(snapshot['requests']) == 3, 'three recorded BCD requests required')
    for name, recorded in zip(('B', 'C', 'D'), snapshot['requests']):
        require(all(recorded[hash_field] == text_hash(recorded[field])
                    for field, hash_field in (('system', 'systemSha256'), ('input', 'inputSha256'),
                                              ('providerRequestBody', 'providerRequestSha256'))),
                'trace text hash mismatch')
        payload = json.loads(recorded['input'])
        body = json.loads(recorded['providerRequestBody'])
        require(body['model'] == 'gpt-6-luna' and body['response_format'] == {'type': 'json_object'}
                and body['temperature'] == 0.1 and body['reasoning_effort'] == 'none', 'recorded settings mismatch')
        require(len(body['messages']) == 2 and body['messages'][0]['role'] == 'system'
                and body['messages'][1] == {'role': 'user', 'content': recorded['input']}, 'provider input mismatch')
        # Provider may add a common JSON-only suffix; preserve it byte-for-byte.
        require(body['messages'][0]['content'].startswith(recorded['system']), 'provider system mismatch')
        require(len(payload['candidates']) == 1, 'single recorded candidate required')
        body.update({'max_completion_tokens': 4000, 'store': False})
        cases.append({'name': name, 'payload': payload, 'body': body,
                      'originalRequestHash': recorded['providerRequestSha256'], 'inputHash': digest(payload)})
    config = {'pricing': pricing, 'maxCompletionTokens': 4000}
    requests = []
    for repeat in range(1, 4):
        for case in cases:
            for arm in (('BASELINE', 'CANDIDATE') if repeat % 2 else ('CANDIDATE', 'BASELINE')):
                body = copy.deepcopy(case['body'])
                if arm == 'CANDIDATE': body['messages'][0]['content'] += DELTA
                tokens, cost = reserve(body, config)
                require(tokens < 272000, 'long input pricing tier unsupported')
                requests.append({'case': case['name'], 'arm': arm, 'repeat': repeat, 'body': body,
                                 'requestHash': digest(body), 'reservedInputTokensHeuristic': tokens,
                                 'reservedCostUsd': str(cost)})
    total = sum(Decimal(row['reservedCostUsd']) for row in requests)
    require(total <= remaining, 'trial exceeds remaining authorized budget')
    artifact = {'schemaVersion': 'recorded-candidate-trial-1', 'scope': 'RECORDED_TEXT_FIRST_VERIFICATION_ONLY',
                'sourceTraceHash': digest(trace), 'previousReportHash': digest(previous),
                'previousReservedCostUsd': previous['reservedCostUsd'], 'cumulativeBudgetUsd': '0.5',
                'remainingBudgetUsd': str(remaining), 'reservedTotalCostUsd': str(total),
                'config': config, 'cases': cases, 'requests': requests,
                'normalControlsIncluded': False, 'fullPipelineReplayed': False, 'qualityGateMet': None}
    if prior_attempt is not None:
        artifact['priorInterruptedReportHash'] = digest(prior_attempt)
        artifact['priorInterruptedReservedCostUsd'] = prior_attempt['reservedCostUsd']
    artifact['artifactHash'] = digest(artifact)
    return artifact


def checked_output(result, payload):
    choice = result['providerResponse']['choices'][0]
    require(choice['finish_reason'] == 'stop' and not choice['message'].get('refusal'), 'incomplete/refused')
    output = json.loads(choice['message']['content'])
    if result.get('outputAdapter') == 'single-target-evidence-wire-1':
        from target_wire_contract import adapt
        result['wireOutput'] = copy.deepcopy(output)
        try:
            output = adapt(output,payload)
        except (ValueError,KeyError,TypeError):
            result.update(status='PYTHON_WIRE_REJECTED',wireAdaptationError='WIRE_CONTRACT_REJECTED')
            return
    result['output'] = output
    try:
        verify(output, payload['candidates'], payload['raw'])
        result['status'] = 'PYTHON_EVIDENCE_CHECK_ONLY'
    except (ValueError, KeyError, TypeError) as error:
        # Invalid model evidence is a measured outcome, not a network retry.
        result['status'] = 'PYTHON_EVIDENCE_REJECTED'
        result['evidenceValidationError'] = type(error).__name__


def execute(artifact, directory, key, call=transport, prior_report=None):
    require(artifact['artifactHash'] == digest({k: v for k, v in artifact.items() if k != 'artifactHash'}),
            'artifact changed')
    if artifact.get('schemaVersion') == 'recorded-target-wire-trial-1':
        require(len(artifact['requests']) == artifact['plannedCalls'] == 42
                and artifact['additionalBudgetUsd'] == '.5'
                and positive(artifact['remainingBudgetUsd']) == Decimal('.5')
                and artifact['priorReservedCostUsd'] == '0.4994146'
                and prior_report is None, 'new separately authorized 42-call trial required')
        require(positive(artifact['reservedTotalCostUsd']) == sum(positive(r['reservedCostUsd']) for r in artifact['requests']),
                'wire total reservation mismatch')
    elif artifact.get('schemaVersion') == 'recorded-candidate-boundary-trial-1':
        require(len(artifact['priorReservationLedger']) == 5, 'all five prior reservations required')
        require(type(artifact.get('plannedCalls')) is int
                and 1 <= artifact['plannedCalls'] <= 18
                and len(artifact['requests']) == artifact['plannedCalls'], 'bounded call count required')
        prior = sum(positive(r['reservedCostUsd']) for r in artifact['priorReservationLedger'])
        require(Decimal(artifact['cumulativeBudgetUsd']) == Decimal('.5')
                and prior + positive(artifact['remainingBudgetUsd']) == Decimal('.5')
                and len({r['reportHash'] for r in artifact['priorReservationLedger']}) == 5
                and prior_report is None, 'complete cumulative ledger required; no automatic retry')
        require(positive(artifact['reservedTotalCostUsd']) == sum(positive(r['reservedCostUsd']) for r in artifact['requests']),
                'total reservation mismatch')
    else:
        require(artifact.get('schemaVersion') == 'recorded-candidate-trial-1'
                and len(artifact['requests']) == 18, '18-call limit')
    require(sum(positive(r['reservedCostUsd']) for r in artifact['requests'])
            <= positive(artifact['remainingBudgetUsd']), 'budget exceeded')
    cases = {c['name']: c for c in artifact['cases']}
    for row in artifact['requests']:
        if artifact.get('schemaVersion') in {'recorded-candidate-boundary-trial-1','recorded-target-wire-trial-1'}:
            require(row['case'] in cases and row['arm'] in {'BASELINE','CANDIDATE'}
                    and row['body']['model'] == 'gpt-6-luna'
                    and row['body']['response_format'] == {'type':'json_object'}
                    and row['body']['store'] is False
                    and row['body']['temperature'] == .1 and row['body']['reasoning_effort'] == 'none'
                    and type(row['body']['max_completion_tokens']) is int
                    and 1 <= row['body']['max_completion_tokens'] <= 4000
                    and len(row['body']['messages']) == 2
                    and row['body']['messages'][1]['role'] == 'user'
                    and json.loads(row['body']['messages'][1]['content']) == cases[row['case']]['payload'],
                    'bounded request contract mismatch')
        if artifact.get('schemaVersion') == 'recorded-target-wire-trial-1':
            require(row.get('outputAdapter') == ('single-target-evidence-wire-1' if row['arm'] == 'CANDIDATE' else None),
                    'adapter must match frozen comparison arm')
        tokens, cost = reserve(row['body'], artifact['config'])
        require(row['requestHash'] == digest(row['body']) and tokens == row['reservedInputTokensHeuristic']
                and cost == positive(row['reservedCostUsd']), 'request/reservation changed')
    directory = Path(directory)
    require('datasets' in directory.resolve().parts, 'private output required')
    directory.parent.mkdir(parents=True, exist_ok=True)
    directory.mkdir(mode=0o700)
    write_private(directory / 'frozen.json', artifact)
    report = {'schemaVersion': 'recorded-candidate-results-1', 'artifactHash': artifact['artifactHash'],
              'status': 'PARTIAL', 'model': 'gpt-6-luna', 'cases': artifact['cases'], 'calls': [],
              'reservedCostUsd': artifact['reservedTotalCostUsd'], 'estimatedCostUsd': '0',
              'actualBilledCostUsd': None, 'unknownCostAttempts': 0, 'qualityGateMet': None,
              'normalControlsIncluded': artifact.get('normalControlsIncluded', False), 'fullPipelineReplayed': False}
    estimated = Decimal(0)
    if prior_report is not None:
        require(prior_report['artifactHash'] == artifact['artifactHash']
                and prior_report['status'] == 'PARTIAL' and prior_report['unknownCostAttempts'] == 0,
                'known-cost partial report required')
        require(0 < len(prior_report['calls']) < 18, 'unfinished prefix required')
        seeded = copy.deepcopy(prior_report['calls'])
        for row, result in zip(artifact['requests'], seeded):
            require(all(result[k] == row[k] for k in ('case', 'arm', 'repeat', 'requestHash'))
                    and 'estimatedCostUsd' in result and 'providerResponse' in result,
                    'resume prefix mismatch')
            require(result.get('error') in (None, 'PilotError'), 'unsafe failure to resume')
            checked_output(result, cases[row['case']]['payload'])
            result.pop('error', None)
            estimated += positive(result['estimatedCostUsd'])
        report['calls'] = seeded
        report['resumedReportHash'] = digest(prior_report)
    completed = len(report['calls'])
    for i, row in enumerate(artifact['requests'][completed:], completed + 1):
        result = {k: row[k] for k in ('case', 'arm', 'repeat', 'requestHash')}
        if row.get('outputAdapter'):
            require(row['outputAdapter'] == 'single-target-evidence-wire-1', 'unknown output adapter')
            result['outputAdapter'] = row['outputAdapter']
        write_private(directory / f'attempt-{i:03d}.json', result)
        started = time.monotonic()
        try:
            response = call(key, row['body'])
            result['providerResponse'] = response
            usage = response['usage']
            require(all(type(usage.get(k)) is int and usage[k] >= 0 for k in ('prompt_tokens', 'completion_tokens')),
                    'usage unavailable')
            p = artifact['config']['pricing']
            cost = (Decimal(usage['prompt_tokens']) * positive(p['inputUsdPerMillion'])
                    + Decimal(usage['completion_tokens']) * positive(p['outputUsdPerMillion'])) / 1000000
            estimated += cost
            result.update({'usage': usage, 'estimatedCostUsd': str(cost)})
            require(response.get('model') == 'gpt-6-luna', 'unexpected returned model')
            require(usage['prompt_tokens'] <= row['reservedInputTokensHeuristic']
                    and usage['completion_tokens'] <= row['body']['max_completion_tokens']
                    and cost <= positive(row['reservedCostUsd']), 'reservation exceeded')
            payload = cases[row['case']]['payload']
            checked_output(result, payload)
        except Exception as error:
            result.update({'error': type(error).__name__, 'status': 'FAILED_STOP'})
            if 'estimatedCostUsd' not in result: report['unknownCostAttempts'] += 1
        result['latencyMs'] = round((time.monotonic() - started) * 1000)
        report['calls'].append(result)
        write_private(directory / f'result-{i:03d}.json', result)
        print({'completed': i, 'case': row['case'], 'arm': row['arm'], 'status': result['status']}, flush=True)
        if 'error' in result: break
    report['estimatedCostUsd'] = str(estimated)
    if len(report['calls']) == len(artifact['requests']) and not any('error' in c for c in report['calls']): report['status'] = 'COMPLETED_PENDING_JAVA_AUDIT'
    write_private(directory / 'report.json', report)
    return report


def audit_export(report):
    """Administrative ID normalization for existing Java replay; no text edits."""
    adapted = copy.deepcopy(report)
    for call in adapted['calls']:
        if call.get('wireAdaptationError'):
            call['error'] = 'WIRE_ADAPTATION_REJECTED'
    for case in adapted['cases']:
        payload = case['payload']
        mapping = {r['id']: f'stt-replay-{i}' for i, r in enumerate(payload['raw'])}
        for r in payload['raw']: r['id'] = mapping[r['id']]
        candidate = payload['candidates'][0]
        old_id = candidate['candidateId']
        candidate['candidateId'] = 'candidate-1'
        candidate['anchorId'] = mapping[candidate['anchorId']]
        candidate['segmentIds'] = [mapping[s] for s in candidate['segmentIds']]
        for q in candidate['proposedEvidence']: q['segmentId'] = mapping[q['segmentId']]
        for call in adapted['calls']:
            if call['case'] != case['name'] or 'error' in call: continue
            for v in call['output']['verifications']:
                require(v['candidateId'] == old_id, 'response candidate mismatch')
                v['candidateId'] = 'candidate-1'
                a = v['assessment']
                a['segmentId'] = mapping[a['segmentId']]
                for q in a['evidence']: q['segmentId'] = mapping[q['segmentId']]
    return adapted


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('prepare')
    for flag in ('trace', 'previous-report', 'pricing', 'output'): p.add_argument('--' + flag, required=True)
    p.add_argument('--prior-attempt-report')
    p = sub.add_parser('run')
    p.add_argument('artifact'); p.add_argument('--execute', action='store_true')
    p.add_argument('--output-dir'); p.add_argument('--env-file')
    p.add_argument('--resume-report')
    p = sub.add_parser('export-audit')
    p.add_argument('report'); p.add_argument('--output', required=True)
    args = parser.parse_args()
    if args.command == 'prepare':
        artifact = prepare(read(args.trace), read(args.previous_report), read(args.pricing),
                           read(args.prior_attempt_report) if args.prior_attempt_report else None)
        write_private(Path(args.output), artifact)
        print({'plannedCalls': 18, 'reservedCostUsd': artifact['reservedTotalCostUsd'], 'executed': False})
    elif args.command == 'export-audit':
        write_private(Path(args.output), audit_export(read(args.report)))
    elif args.execute:
        require(args.output_dir is not None, 'new output directory required')
        execute(read(args.artifact), args.output_dir, read_key(args.env_file, key_name='OPENAI_API_KEY'),
                prior_report=read(args.resume_report) if args.resume_report else None)
    else:
        print({'plannedCalls': len(read(args.artifact)['requests']), 'executed': False})


if __name__ == '__main__': main()
