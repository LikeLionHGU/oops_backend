import copy
import sys
import subprocess
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import case_reference_regression as tool
from test_extend_context_references import fixture as source_fixture


def fixture():
    base, additions = source_fixture()
    units, cases, labels = [], [], []
    for i, cid in enumerate(['pisik-B','pisik-C','pisik-D','normal-1','normal-2','normal-3','normal-4']):
        uid = f'eval-{i+1:03d}'
        payload = {'mode': 'SAVED_STT_WINDOW' if i < 3 else 'SYNTHETIC_TEXT',
                   'segments': [{'text': 'fixture text', 'startMs': 0 if i < 3 else None,
                                 'endMs': 1000 if i < 3 else None}]}
        units.append({'unitId': uid, 'input': payload})
        cases.append({'unitId': uid, 'caseId': cid, 'familyId': 'family', 'inputHash': tool.digest(payload)})
        labels.append({'assessment': {'caseId': cid, 'inputHash': tool.digest(payload),
                                      'developmentExpectedDecision': 'REVIEW_REQUIRED' if i < 3 else 'PASS'}})
    inputs = {'units': units}
    packet = {'model-inputs.json': inputs, 'manifest.json': {'cases': cases, 'modelInputsHash': tool.digest(inputs)},
              'owner-labels.json': {'labels': labels}}
    bundle = tool.prepare(base, additions, packet)
    return base, additions, packet, bundle


def call(bundle, quote='fixture text'):
    output = {'decision': 'REVIEW_REQUIRED', 'reason': 'fixture', 'missingInformation': [],
              'issues': [{'axis': 'TARGET_TREATMENT', 'reason': 'fixture', 'normalInterpretation': 'fixture',
                          'evidence': [{'segmentId': 'segment-001', 'quote': quote}]}]}
    value = {'unitId': 'unit-001', 'arm': 'EXTENDED', 'repeat': 1, 'output': output,
             'outputHash': tool.digest(output), 'selectedExampleIds': [], 'referenceHash': 'extended',
             'settings': {'model': 'fixture'}, 'humanReview': {'status': 'REVIEWED', 'reviewerId': 'fixture',
             'outputHash': tool.digest(output), 'targetIssueCorrect': True,
             'unsupportedInference': False, 'duplicateIssue': False}}
    bundle['plan.json']['settings'] = {'model': 'fixture'}
    bundle['plan.json']['baselineReferenceHash'] = 'baseline'
    bundle['plan.json']['extendedReferenceHash'] = 'extended'
    plan = bundle['plan.json']
    plan['planHash'] = tool.digest({k:v for k,v in plan.items() if k != 'planHash'})
    bundle['observations.json']['planHash'] = plan['planHash']
    bundle['observations.json']['calls'] = [value]
    return value


def check(bundle):
    return tool.evaluate(*(bundle[n] for n in ('model-inputs.json','plan.json','observations.json')))


class RegressionTests(unittest.TestCase):
    def text_first(self):
        base, additions, packet, _ = fixture()
        additions['schemaVersion'] = 'source-first-context-additions-2'
        additions.update(collectionMode='TEXT_FIRST_VIDEO_OPTIONAL', videoRequired=False)
        for case in additions['cases']:
            case['textContext'] = [{'kind': 'SOURCE_REPORTED_CONTEXT', 'text': 'fixture neutral setting',
                                    'sourceId': 'source', 'sourceLocator': 'fixture paragraph'}]
            case['normalContrast']['inputText'] = 'fixture normal utterance'
        return base, additions, packet

    def test_text_first_notes_are_not_raw_evidence_or_grading_explanation(self):
        bundle = tool.prepare(*self.text_first())
        original, normal = bundle['model-inputs.json']['units'][7:9]
        payload = tool.user_input(original['input'], [])
        self.assertEqual('fixture neutral setting', payload['contextNotes'][0]['text'])
        self.assertFalse(payload['contextNotesAreUtterances'])
        self.assertNotIn('fixture neutral setting', str(payload['raw']))
        self.assertEqual('fixture normal utterance', normal['input']['segments'][0]['text'])
        self.assertNotIn('fixture interpretation', str(bundle['model-inputs.json']))
        self.assertEqual('case-regression-plan-2', bundle['plan.json']['schemaVersion'])
        self.assertFalse(bundle['plan.json']['videoRequired'])
        self.assertFalse(check(bundle)['developmentGateMet'])

    def test_context_summary_cannot_pass_as_spoken_quote(self):
        bundle = tool.prepare(*self.text_first())
        value = call(bundle, 'fixture neutral setting'); value['unitId'] = 'unit-008'
        self.assertFalse(check(bundle)['results'][0]['literalEvidenceValid'])

    def test_invalid_text_first_source_reference_or_context_kind_rejected(self):
        for field, value in (('sourceId','missing'), ('kind','CRITICISM_HYPOTHESIS')):
            base, additions, packet = self.text_first()
            additions['cases'][0]['textContext'][0][field] = value
            with self.assertRaises(ValueError): tool.prepare(base, additions, packet)

    def test_text_first_contrast_must_have_separate_input(self):
        base, additions, packet = self.text_first()
        del additions['cases'][0]['normalContrast']['inputText']
        with self.assertRaises(ValueError): tool.prepare(base, additions, packet)

    def test_text_payload_rejects_labels_and_invented_times(self):
        payload = tool.prepare(*self.text_first())['model-inputs.json']['units'][7]['input']
        for change in ('label','time'):
            changed = copy.deepcopy(payload)
            if change == 'label': changed['expectedDecision'] = 'REVIEW_REQUIRED'
            else: changed['segments'][0]['startMs'] = 100
            with self.assertRaises(ValueError): tool.user_input(changed, [])

    def test_v2_projection_uses_factual_setting_not_interpreted_flow(self):
        base, additions, packet = self.text_first()
        import extend_context_references
        additions['cases'][0]['contextFlow'] = ['SECRET_INTERPRETED_FLOW']
        projected, _ = extend_context_references.project(base, additions)
        self.assertNotIn('SECRET_INTERPRETED_FLOW', str(projected))
        self.assertIn('fixture quote', str(projected))
        self.assertIn('fixture neutral setting', str(projected))

    def test_original_text_and_news_quote_cannot_be_mislabeled(self):
        base, additions, packet = self.text_first()
        for case in additions['cases']:
            case['reportedQuotes'][0]['kind'] = 'ORIGINAL_TEXT_QUOTE'
        with self.assertRaises(ValueError): tool.prepare(base, additions, packet)
        additions['sources'][0]['materialKind'] = 'ORIGINAL_TEXT'
        bundle = tool.prepare(base, additions, packet)
        self.assertFalse(bundle['plan.json']['videoRequired'])

    def test_prepare_preserves_inputs_and_separates_labels(self):
        base, additions, packet, bundle = fixture()
        before = copy.deepcopy(packet)
        tool.prepare(base, additions, packet)
        self.assertEqual(before, packet)
        self.assertNotIn('criticismHypotheses', str(bundle['model-inputs.json']))
        self.assertNotIn('developmentExpectedDecision', str(bundle['model-inputs.json']))
        self.assertEqual(11, len(bundle['plan.json']['units']))

    def test_news_and_controls_have_no_invented_times_or_labels(self):
        bundle = fixture()[-1]
        for row, unit in zip(bundle['plan.json']['units'][7:], bundle['model-inputs.json']['units'][7:]):
            self.assertIsNone(row['developmentExpectedDecision'])
            self.assertFalse(row['videoDetectionEligible'])
            self.assertTrue(all(s['startMs'] is None for s in unit['input']['segments']))

    def test_empty_results_block_not_pass(self):
        status = check(fixture()[-1])
        self.assertEqual(66, status['missingJobs'])
        self.assertFalse(status['developmentGateMet'])
        self.assertIsNone(status['qualityGateMet'])

    def test_invalid_quote_cannot_succeed_despite_human_declaration(self):
        bundle = fixture()[-1]; call(bundle, 'not original')
        result = check(bundle)
        self.assertFalse(result['results'][0]['developmentMatch'])
        self.assertIn('INVALID_ORIGINAL_QUOTE', result['blockingReasons'])

    def test_valid_literal_without_semantic_review_is_not_success(self):
        bundle = fixture()[-1]; value = call(bundle); value['humanReview'] = {}
        self.assertFalse(check(bundle)['results'][0]['developmentMatch'])

    def test_valid_development_match_never_authorizes_runtime(self):
        bundle = fixture()[-1]; call(bundle)
        result = check(bundle)
        self.assertTrue(result['results'][0]['developmentMatch'])
        self.assertFalse(result['runtimeAdoptionAllowed'])
        self.assertFalse(result['fullPipelineReplayed'])

    def test_hash_and_duplicate_jobs_rejected(self):
        for mutation in ('hash','duplicate'):
            bundle = fixture()[-1]; value = call(bundle)
            if mutation == 'hash': value['outputHash'] = 'changed'
            else: bundle['observations.json']['calls'].append(copy.deepcopy(value))
            with self.assertRaises(ValueError): check(bundle)

    def test_holdout_reference_leak_rejected(self):
        bundle = fixture()[-1]; value = call(bundle)
        value['selectedExampleIds'] = ['heldout-case']
        with self.assertRaises(ValueError): check(bundle)

    def test_settings_mismatch_cannot_succeed(self):
        bundle = fixture()[-1]; value = call(bundle); value['settings']['model'] = 'different'
        self.assertFalse(check(bundle)['results'][0]['developmentMatch'])

    def test_non_object_response_is_retained_as_invalid(self):
        bundle = fixture()[-1]; value = call(bundle)
        value['output'] = []; value['outputHash'] = tool.digest([])
        self.assertFalse(check(bundle)['results'][0]['literalEvidenceValid'])

    def test_corrupt_regression_input_rejected(self):
        base, additions, packet, _ = fixture()
        packet['model-inputs.json']['units'][0]['input']['segments'][0]['text'] = 'changed'
        with self.assertRaises(ValueError): tool.prepare(base, additions, packet)

    def test_reference_projection_cli_includes_regression_bundle(self):
        base, additions, packet, _ = fixture()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / 'datasets'
            tool.write_private(root / 'base.json', base)
            tool.write_private(root / 'additions.json', additions)
            for name, value in packet.items(): tool.write_private(root / 'packet' / name, value)
            process = subprocess.run([sys.executable, str(Path(tool.__file__).with_name('extend_context_references.py')),
                '--base', str(root / 'base.json'), '--additions', str(root / 'additions.json'),
                '--regression-packet', str(root / 'packet'), '--output-dir', str(root / 'projection')],
                capture_output=True, text=True, timeout=10)
            self.assertEqual(0, process.returncode, process.stderr)
            status = tool.read(root / 'projection' / 'regression-check' / 'status.json')
            self.assertEqual(66, status['plannedJobs'])
            self.assertFalse(status['developmentGateMet'])
