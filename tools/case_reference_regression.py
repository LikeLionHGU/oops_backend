"""Offline paired source-case/regression packets. No provider calls or promotion.

Reported quotations are not original video transcripts. Semantic worksheets are
human declarations; literal validation alone never awards detection success.
"""
import argparse
import copy
import json
from pathlib import Path

from fixed_review_suite import digest, read, require, write_private
from extend_context_references import validate_additions
from run_boundary_comparison import validate_output


def validate_text_input(payload):
    require(set(payload) <= {'mode', 'segments', 'contextNotes'}
            and {'mode', 'segments'} <= set(payload), 'unexpected text input fields')
    require(payload['mode'] in {'SAVED_STT_WINDOW','REPORTED_QUOTE','SYNTHETIC_TEXT'}, 'unsupported text mode')
    require(isinstance(payload['segments'], list) and 1 <= len(payload['segments']) <= 1000,
            'nonempty bounded text required')
    for segment in payload['segments']:
        require(set(segment) == {'text','startMs','endMs'}
                and isinstance(segment['text'], str) and 0 < len(segment['text'].strip()) <= 10000,
                'literal text required')
        if payload['mode'] == 'SAVED_STT_WINDOW':
            require(type(segment['startMs']) is int and type(segment['endMs']) is int
                    and 0 <= segment['startMs'] < segment['endMs'], 'invalid saved times')
        else:
            require(segment['startMs'] is None and segment['endMs'] is None, 'text must not invent video times')
    notes = payload.get('contextNotes', [])
    require(isinstance(notes, list) and len(notes) <= 6, 'bounded context notes required')
    for note in notes:
        require(set(note) == {'kind','text'} and note['kind'] == 'SOURCE_REPORTED_CONTEXT'
                and isinstance(note['text'], str) and 0 < len(note['text'].strip()) <= 350,
                'only source-reported setting allowed')


def user_input(payload, reference):
    """Shared export for text tests; notes have no segment ID or speech time."""
    validate_text_input(payload)
    result = {'inputMode': payload['mode'], 'raw': [
        {'id': f'segment-{i:03d}', **s} for i,s in enumerate(payload['segments'], 1)],
        'contextReference': copy.deepcopy(reference)}
    if 'contextNotes' in payload:
        result['contextNotes'] = copy.deepcopy(payload['contextNotes'])
        result['contextNotesAreUtterances'] = False
    return result


def prepare(base, additions, packet):
    validate_additions(additions, base)
    prohibited = {s['id'] for s in additions['sources']
                  if s.get('aiDataUseStatus') == 'PROHIBITED'}
    require(not any(prohibited & set(c['sourceIds']) for c in additions['cases']),
            'AI-prohibited source cannot enter model comparison inputs')
    inputs, manifest, labels = (packet[k] for k in
        ('model-inputs.json', 'manifest.json', 'owner-labels.json'))
    require(manifest['modelInputsHash'] == digest(inputs), 'regression input changed')
    indexed = {u['unitId']: u['input'] for u in inputs['units']}
    owner = {r['assessment']['caseId']: r['assessment'] for r in labels['labels']}
    model_units, rows = [], []
    text_first = additions['schemaVersion'] == 'source-first-context-additions-2'

    def add(cid, payload, expected, role, family, basis, reference_id=None):
        validate_text_input(payload)
        uid = f'unit-{len(rows)+1:03d}'
        model_units.append({'unitId': uid, 'input': copy.deepcopy(payload)})
        rows.append({'unitId': uid, 'caseId': cid, 'familyId': family,
                     'inputHash': digest(payload), 'role': role,
                     'developmentExpectedDecision': expected, 'labelBasis': basis,
                     'referenceCaseId': reference_id,
                     'videoDetectionEligible': payload['mode'] == 'SAVED_STT_WINDOW'})
        if text_first:
            rows[-1]['textDetectionEligible'] = True
            rows[-1]['videoRequired'] = False

    require(len(manifest['cases']) == 7 and len(indexed) == 7,
            'B/C/D and four controls required')
    require({'pisik-B', 'pisik-C', 'pisik-D'} <= {r['caseId'] for r in manifest['cases']},
            'B/C/D regression missing')
    for row in manifest['cases']:
        payload, label = indexed[row['unitId']], owner[row['caseId']]
        require(row['inputHash'] == label['inputHash'] == digest(payload), 'owner input mismatch')
        expected = 'REVIEW_REQUIRED' if row['caseId'] in {'pisik-B','pisik-C','pisik-D'} else 'PASS'
        require(label['developmentExpectedDecision'] == expected, 'regression label changed')
        add(row['caseId'], payload, expected, 'REGRESSION', row['familyId'], 'OWNER_DEVELOPMENT_ONLY')
    for case in additions['cases']:
        # HOLDOUT is preserved separately, not an obligatory warning label.
        payload = {'mode': 'REPORTED_QUOTE', 'segments': [
            {'text': q['text'], 'startMs': None, 'endMs': None} for q in case['reportedQuotes']]}
        if text_first:
            # Background is not dialogue and may not be cited as spoken evidence.
            payload['contextNotes'] = [{'kind': n['kind'], 'text': n['text']} for n in case['textContext']]
        add(case['id'], payload, None, case['split'], case['familyId'],
            'PENDING_TEXT_CONTEXT_AND_HUMAN_LABEL' if text_first else 'PENDING_SOURCE_SUFFICIENCY_AND_HUMAN_LABEL',
            case['id'] if case['split'] == 'DEVELOPMENT' else None)
        add(case['id'] + '-normal', {'mode': 'SYNTHETIC_TEXT', 'segments': [
            {'text': case['normalContrast']['inputText'] if text_first else case['normalContrast']['text'],
             'startMs': None, 'endMs': None}]},
            None, case['split'], case['familyId'], 'PENDING_SYNTHETIC_CONTROL_REVIEW')
    revision = '2' if text_first else '1'
    model = {'schemaVersion': 'case-regression-inputs-' + revision, 'units': model_units}
    plan = {'schemaVersion': 'case-regression-plan-' + revision, 'modelInputsHash': digest(model),
            'baseArchiveHash': digest(base), 'additionsHash': digest(additions),
            'regressionPacketHash': digest(packet), 'units': rows, 'repetitions': 3,
            'scope': 'TEXT_WINDOW_NOT_CANDIDATE_OR_FULL_VIDEO_REPLAY',
            'arms': ['BASELINE', 'EXTENDED'], 'settings': None,
            'baselineReferenceHash': None, 'extendedReferenceHash': None,
            'apiExecutionEnabled': False, 'runtimeUseAuthorized': False,
            'trainingUseAuthorized': False, 'qualityGateMet': None}
    if text_first:
        plan['collectionMode'] = 'TEXT_FIRST_VIDEO_OPTIONAL'
        plan['videoRequired'] = False
        plan['contextPolicy'] = 'SOURCE_REPORTED_SETTING_NOT_QUOTABLE_DIALOGUE; NO_KNOWN_POINT_OR_REACTIONS_IN_INPUT'
    # A plan hash binds both model data and evaluator-only expectations.
    plan['planHash'] = digest(plan)
    worksheet = {'schemaVersion': 'case-regression-observations-1', 'planHash': plan['planHash'],
                 'modelInputsHash': digest(model), 'calls': []}
    return {'model-inputs.json': model, 'plan.json': plan, 'observations.json': worksheet}


def evaluate(model, plan, observations):
    unsigned = {k: v for k, v in plan.items() if k != 'planHash'}
    require(plan['schemaVersion'] in {'case-regression-plan-1', 'case-regression-plan-2'}
            and plan['planHash'] == digest(unsigned), 'plan changed')
    require(model['schemaVersion'] == plan['schemaVersion'].replace('-plan-', '-inputs-'), 'input contract mismatch')
    require(plan['modelInputsHash'] == observations['modelInputsHash'] == digest(model)
            and observations['planHash'] == plan['planHash']
            and observations['schemaVersion'] == 'case-regression-observations-1', 'observation mismatch')
    inputs = {u['unitId']: u['input'] for u in model['units']}
    rows = {u['unitId']: u for u in plan['units']}
    require(len(rows) == len(plan['units']) and set(rows) == set(inputs), 'unit mismatch')
    require(all(r['inputHash'] == digest(inputs[uid]) for uid,r in rows.items()), 'input changed')
    for payload in inputs.values(): validate_text_input(payload)
    results, seen = [], set()
    for call in observations['calls']:
        uid, arm, repeat = call['unitId'], call['arm'], call['repeat']
        require(uid in rows and arm in plan['arms'] and type(repeat) is int
                and 1 <= repeat <= plan['repetitions'], 'invalid job')
        key = (uid, arm, repeat)
        require(key not in seen, 'duplicate job'); seen.add(key)
        raw = user_input(inputs[uid], [])['raw']
        require(call['outputHash'] == digest(call['output']), 'response changed')
        try:
            validate_output(call['output'], raw)
            literal_valid = True
        except (ValueError, KeyError, TypeError):
            literal_valid = False
        review = call.get('humanReview', {})
        reviewed = (review.get('status') == 'REVIEWED' and bool(review.get('reviewerId'))
                    and review.get('outputHash') == call['outputHash'])
        expected = rows[uid]['developmentExpectedDecision']
        # New cases must be labelled against the input separately, not by AI or
        # by assuming an incident reported as controversial deserves a warning.
        if expected is None:
            reviewed = False
        semantic = (reviewed and review.get('targetIssueCorrect') is True
                    and review.get('unsupportedInference') is False
                    and review.get('duplicateIssue') is False)
        settings_valid = (plan['settings'] is not None and call.get('settings') == plan['settings']
                          and call.get('referenceHash') == plan[arm.lower() + 'ReferenceHash']
                          and call.get('referenceHash') is not None)
        selected = call.get('selectedExampleIds')
        require(isinstance(selected, list) and len(selected) == len(set(selected)), 'selection record required')
        withheld = {r['caseId'].removesuffix('-normal') for r in rows.values() if r['role'] == 'HOLDOUT'}
        require(not withheld & set(selected), 'holdout reference leakage')
        results.append({'unitId': uid, 'arm': arm, 'repeat': repeat,
            'literalEvidenceValid': literal_valid, 'humanReviewCompleted': reviewed,
            'controlledSettings': settings_valid,
            'expectedReferenceDelivered': rows[uid]['referenceCaseId'] in selected
                if rows[uid]['referenceCaseId'] else None,
            'developmentMatch': literal_valid and semantic and settings_valid
                and isinstance(call['output'], dict) and call['output'].get('decision') == expected})
    required = {(uid, arm, repeat) for uid in rows for arm in plan['arms']
                for repeat in range(1, plan['repetitions']+1)}
    missing = len(required - seen)
    reasons = []
    if plan['settings'] is None: reasons.append('SETTINGS_AND_REFERENCES_NOT_FROZEN')
    if any(r['developmentExpectedDecision'] is None for r in rows.values()):
        reasons.append('NEW_CASE_INPUT_SUFFICIENCY_AND_LABELS_PENDING')
    if missing: reasons.append('PAIRED_REPEATED_RESULTS_MISSING')
    if any(not r['humanReviewCompleted'] for r in results): reasons.append('SEMANTIC_REVIEW_PENDING')
    if any(not r['literalEvidenceValid'] for r in results): reasons.append('INVALID_ORIGINAL_QUOTE')
    if any(not r['controlledSettings'] for r in results): reasons.append('UNCONTROLLED_COMPARISON')
    extended = [r for r in results if r['arm'] == 'EXTENDED']
    if any(r['expectedReferenceDelivered'] is False for r in extended): reasons.append('EXPECTED_REFERENCE_NOT_DELIVERED')
    if any(not r['developmentMatch'] for r in extended): reasons.append('EXTENDED_DEVELOPMENT_TARGET_NOT_MET')
    return {'schemaVersion': 'case-regression-status-1', 'planHash': plan['planHash'],
        'plannedJobs': len(required), 'completedJobs': len(seen), 'missingJobs': missing,
        'blockingReasons': reasons, 'results': results,
        'developmentGateMet': not reasons, 'qualityGateMet': None,
        'observationTrust': 'DECLARED_OFFLINE_RECORDS_NOT_INDEPENDENTLY_AUTHENTICATED',
        'selectionEvidenceVerified': False,
        'runtimeAdoptionAllowed': False, 'fullPipelineReplayed': False,
        'videoRequired': plan.get('videoRequired', False),
        'generalAccuracyMeasured': False, 'apiCallsMadeByThisTool': 0,
        'qualification': ('Text-case development; video is optional and needed only for modality-dependent claims. '
                          'Runtime candidate/domain integration remains a separate test.'
                          if plan['schemaVersion'] == 'case-regression-plan-2' else
                          'Text-window development only; candidate/domain and original video tests still required.')}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    p = commands.add_parser('prepare')
    for flag in ('base', 'additions', 'regression-packet', 'output-dir'):
        p.add_argument('--' + flag, required=True)
    p = commands.add_parser('check')
    p.add_argument('directory'); p.add_argument('--output')
    args = parser.parse_args()
    if args.command == 'prepare':
        packet = {n: read(Path(args.regression_packet) / n) for n in
                  ('model-inputs.json','manifest.json','owner-labels.json')}
        bundle = prepare(read(args.base), read(args.additions), packet)
        directory = Path(args.output_dir).resolve()
        require('datasets' in directory.parts and not directory.exists(), 'new private directory required')
        directory.mkdir(mode=0o700, parents=True)
        for name, value in bundle.items(): write_private(directory / name, value)
        result = evaluate(bundle['model-inputs.json'],bundle['plan.json'],bundle['observations.json'])
        write_private(directory / 'status.json', result)
    else:
        directory = Path(args.directory)
        result = evaluate(*(read(directory / n) for n in ('model-inputs.json','plan.json','observations.json')))
        if args.output: write_private(Path(args.output), result)
    print(json.dumps({k:v for k,v in result.items() if k != 'results'}, ensure_ascii=False, indent=2))


if __name__ == '__main__': main()
