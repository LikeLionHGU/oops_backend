"""Source-first research additions, isolated v4 reference projection, no API calls.

Does not alter the default runtime, patterns, approvals or comment catalog.
HOLDOUT cases and their source text never enter the projected reference.
"""
import argparse
import copy
import datetime
import json
import os
from pathlib import Path
import subprocess
from urllib.parse import urlparse

from fixed_review_suite import digest, read, require, write_private
from build_review_guidelines import prompt_rules


def validate_additions(data, base):
    require(data.get('schemaVersion') == 'source-first-context-additions-1'
            and data.get('status') == 'UNREVIEWED_DRAFT', 'research additions required')
    require(all(data.get(k) is False for k in ('humanApproved', 'trainingUseAuthorized', 'runtimeEligible')),
            'automatic promotion forbidden')
    sources = data['sources']
    source_ids = {s['id'] for s in sources}
    require(len(source_ids) == len(sources) and bool(sources), 'unique sources required')
    for source in sources:
        url = urlparse(source['url'])
        require(url.scheme == 'https' and url.hostname and not url.username and not url.password
                and source['materialKind'] == 'NEWS_BODY' and source['rightsReviewStatus'] == 'PENDING',
                'source metadata required')
        datetime.date.fromisoformat(source['retrievedOn'])
        datetime.date.fromisoformat(source['publishedOn'])
    patterns = {p['id']: p for p in base['guidelines']}
    ids = {s['caseId'] for s in base['sourceCases']}
    families = {s['familyId'] for s in base['sourceCases']}
    require(1 <= len(data['cases']) <= 100, 'addition limit')
    for case in data['cases']:
        require(case['id'] not in ids and case['familyId'] not in families, 'duplicate case/family')
        ids.add(case['id']); families.add(case['familyId'])
        require(case['status'] == 'UNREVIEWED_DRAFT' and case['split'] in ('DEVELOPMENT', 'HOLDOUT')
                and case['humanApproved'] is False and case['originalVerified'] is False
                and case['commentCollectionStatus'] == 'NOT_COLLECTED'
                and case['timestamps'] is None, 'source limits required')
        require(case['mechanismIds'] and set(case['mechanismIds']) <= set(patterns)
                and len(case['mechanismIds']) == len(set(case['mechanismIds'])), 'explicit existing mechanisms required')
        refs = set(case['sourceIds'])
        require(refs and refs <= source_ids and case['reportedQuotes'], 'source links required')
        for quote in case['reportedQuotes']:
            require(quote['sourceId'] in refs and quote['kind'] == 'SOURCE_REPORTED_QUOTE_NOT_TRANSCRIPT'
                    and isinstance(quote['text'], str) and 0 < len(quote['text']) <= 350,
                    'reported quote required')
        for field, low, high in (('contextFlow', 1, 6), ('criticismHypotheses', 1, 8), ('missingContext', 1, 8)):
            require(isinstance(case[field], list) and low <= len(case[field]) <= high
                    and all(isinstance(s, str) and 0 < len(s) <= 350 for s in case[field]), 'bounded context required')
        require(case['interpretationStatus'] == 'ASSISTANT_DRAFT_NOT_VIDEO_FACT'
                and case['normalContrast']['status'] == 'SYNTHETIC_NOT_HISTORICAL_TRANSCRIPT'
                and 0 < len(case['normalContrast']['text']) <= 300,
                'draft interpretation and separate normal contrast required')


def project(base, additions):
    require(base['schemaVersion'] == 'review-guidelines-4' and base['humanValidated'] is False
            and base['status'] == 'WORKING_REFERENCE_NOT_VALIDATED', 'working v4 base required')
    validate_additions(additions, base)
    result = copy.deepcopy(base)
    patterns_before = digest(prompt_rules(base, 'SPEECH'))
    for case in additions['cases']:
        if case['split'] == 'HOLDOUT': continue
        channels = set()
        for rule in result['guidelines']:
            if rule['id'] in case['mechanismIds']:
                rule['sourceCaseIds'].append(case['id'])
                channels.update(rule['channels'])
        result['sourceCases'].append({'caseId': case['id'], 'familyId': case['familyId'], 'status': 'UNREVIEWED_DRAFT'})
        result['examples'].append({'id': case['id'], 'familyId': case['familyId'],
            'mechanismIds': case['mechanismIds'], 'channels': sorted(channels), 'context': {
                'coverage': 'REPORTED_CONTEXT_ONLY_NOT_VIDEO_TRANSCRIPT',
                'flow': case['contextFlow'] + ['비교용 정상 대조(합성, 사건 전사 아님): ' + case['normalContrast']['text']],
                'criticismHypotheses': case['criticismHypotheses'],
                'missingContext': case['missingContext'], 'sourceInterpretations': []}})
    result['version'] = additions['version']
    # Separate provenance sidecar explicitly identifies this supplemental research
    # compilation, not the legacy comment bundle or a human-approved dictionary.
    result['sourceBundleSha256'] = digest({'baseSourceBundleSha256': base['sourceBundleSha256'], 'additions': additions})
    result['contextDictionarySha256'] = digest({'baseContextDictionarySha256': base['contextDictionarySha256'], 'additions': additions})
    require(digest(prompt_rules(result, 'SPEECH')) == patterns_before, 'pattern prompt must not change')
    heldout = [c for c in additions['cases'] if c['split'] == 'HOLDOUT']
    require(not {c['id'] for c in heldout} & {e['id'] for e in result['examples']}, 'holdout leakage')
    provenance = {'schemaVersion': 'source-first-reference-provenance-1', 'baseArchiveHash': digest(base),
        'additionsHash': digest(additions), 'projectedArchiveHash': digest(result),
        'commonSpeechPatternHash': patterns_before, 'defaultRuntimeChanged': False,
        'humanApproved': False, 'trainingPerformed': False, 'qualityGateMet': None,
        'addedReferenceCaseIds': [c['id'] for c in additions['cases'] if c['split'] == 'DEVELOPMENT'],
        'withheldCaseIds': [c['id'] for c in heldout], 'heldoutEvaluationPerformed': False,
        'scope': 'SUPPLEMENTAL_NEWS_RESEARCH_NOT_COMMENT_CATALOG_OR_APPROVED_DATASET'}
    return result, provenance


def routing_input(trace):
    snapshot = trace['data']['snapshot']
    require(trace['success'] is True and trace['data']['available'] is True
            and snapshot['truncated'] is False and snapshot['droppedRequests'] == 0,
            'complete trace required')
    rows, queries = {}, []
    for request in snapshot['requests']:
        payload = json.loads(request['input'])
        require(len(payload['candidates']) == 1, 'single candidate trace required')
        for row in payload['raw']:
            require(row['id'] not in rows or rows[row['id']] == row, 'inconsistent shared raw')
            rows[row['id']] = row
        candidate = payload['candidates'][0]
        focus = {q['segmentId'] for q in candidate['proposedEvidence']}
        raw_ids = {r['id'] for r in payload['raw']}
        require(candidate['anchorId'] in focus and focus <= raw_ids and len(focus) <= 8,
                'focus must be actual raw including anchor')
        # Existing Java audit focuses full segments. Refuse partial quotations
        # rather than silently claim production quotation-routing equivalence.
        require(all(rows[q['segmentId']]['text'] == q['quote'] for q in candidate['proposedEvidence']),
                'full-segment focus quotes required for this audit')
        queries.append({'startMs': min(r['startMs'] for r in payload['raw']),
                        'endMs': max(r['endMs'] for r in payload['raw']),
                        'focusStartMs': sorted(rows[s]['startMs'] for s in focus)})
    return {'transcript': [{k: r[k] for k in ('startMs', 'endMs', 'text')}
                           for r in sorted(rows.values(), key=lambda r: r['startMs'])],
            'candidateQueries': queries}


def summarize_routing(arms, provenance):
    added = set(provenance['addedReferenceCaseIds'])
    withheld = set(provenance['withheldCaseIds'])
    baseline = {s['requestKey']: s for s in arms['baseline']['selections']}
    rows = []
    for selected in arms['extended']['selections']:
        old = baseline[selected['requestKey']]
        require(not withheld & set(selected['exampleIds']), 'holdout selected')
        rows.append({'requestKey': selected['requestKey'], 'baselineExampleIds': old['exampleIds'],
                     'extendedExampleIds': selected['exampleIds'],
                     'newlySelectedCaseIds': sorted(added & set(selected['exampleIds'])),
                     'selectedExampleIdsAndSizesUnchanged': old['exampleIds'] == selected['exampleIds']
                         and old['payloadCodePoints'] == selected['payloadCodePoints']
                         and old['baseCodePoints'] == selected['baseCodePoints']})
    return {'queries': rows, 'anyNewExampleSelected': any(r['newlySelectedCaseIds'] for r in rows),
            'allSelectedExampleIdsAndSizesUnchanged': all(r['selectedExampleIdsAndSizesUnchanged'] for r in rows),
            'detectionImprovementMeasured': False, 'heldoutModelEvaluationPerformed': False}


def audit(directory):
    directory = Path(directory).resolve()
    result = {'schemaVersion': 'source-first-routing-comparison-1', 'apiCalls': 0,
              'fullPipelineReplayed': False, 'qualityGateMet': None, 'arms': {}}
    env = dict(os.environ)
    env['JAVA_HOME'] = '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home'
    for arm in ('baseline', 'extended'):
        command = ['gradle', 'contextReferenceAudit', '--no-daemon',
                   '-PreplayInput=' + str(directory / 'routing-input.json'),
                   '-PguidelineArchive=' + str(directory / f'{arm}-runtime.json')]
        process = subprocess.run(command, cwd=Path(__file__).resolve().parents[1] / 'oops-backend',
                                 env=env, capture_output=True, text=True, timeout=60)
        require(process.returncode == 0, 'Java reference audit failed')
        lines = [line for line in process.stdout.splitlines() if line.startswith('{')]
        require(len(lines) == 1, 'single Java audit output required')
        result['arms'][arm] = json.loads(lines[0])
    result['summary'] = summarize_routing(result['arms'], read(directory / 'provenance.json'))
    write_private(directory / 'routing-comparison.json', result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for flag in ('base', 'additions', 'output-dir'): parser.add_argument('--' + flag, required=True)
    parser.add_argument('--trace')
    parser.add_argument('--audit', action='store_true')
    parser.add_argument('--regression-packet', help='Include paired original-case and B/C/D regression preparation')
    args = parser.parse_args()
    require(not args.audit or args.trace is not None, 'trace required for audit')
    base, additions = read(args.base), read(args.additions)
    result, provenance = project(base, additions)
    regression = None
    if args.regression_packet:
        from case_reference_regression import prepare, evaluate
        packet = {name: read(Path(args.regression_packet) / name) for name in
                  ('model-inputs.json', 'manifest.json', 'owner-labels.json')}
        regression = prepare(base, additions, packet)
    routing = None
    if args.trace:
        trace = read(args.trace)
        routing = routing_input(trace)
        provenance['sourceTraceHash'] = digest(trace)
        provenance['routingInputHash'] = digest(routing)
    directory = Path(args.output_dir).resolve()
    require('datasets' in directory.parts and not directory.exists(), 'new private directory required')
    directory.mkdir(mode=0o700, parents=True)
    write_private(directory / 'baseline-runtime.json', base)
    write_private(directory / 'extended-runtime.json', result)
    write_private(directory / 'provenance.json', provenance)
    if routing is not None:
        write_private(directory / 'routing-input.json', routing)
    if regression is not None:
        regression_dir = directory / 'regression-check'
        regression_dir.mkdir(mode=0o700)
        for name, value in regression.items(): write_private(regression_dir / name, value)
        write_private(regression_dir / 'status.json', evaluate(*(regression[n] for n in
                      ('model-inputs.json', 'plan.json', 'observations.json'))))
    if args.audit:
        audit(directory)
    print(json.dumps({'addedExamples': len(provenance['addedReferenceCaseIds']),
                      'withheldExamples': len(provenance['withheldCaseIds']),
                      'defaultRuntimeChanged': False, 'apiCalls': 0}))


if __name__ == '__main__': main()
