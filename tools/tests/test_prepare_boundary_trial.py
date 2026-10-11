import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import prepare_boundary_trial as tool
from test_run_boundary_comparison import packet
from review_boundary_pairs import comparison_draft


class PreparationTests(unittest.TestCase):
    def fixture(self):
        p = packet()
        # Seven distinct synthetic fixture units; no real human labels.
        original = p['model-inputs.json']['units'][0]
        p['model-inputs.json']['units'] = [dict(original, unitId=f'eval-{i:03d}') for i in range(7)]
        p['manifest.json']['modelInputsHash'] = tool.digest(p['model-inputs.json'])
        p['comparison-draft.json'] = comparison_draft(p, p['boundary-pairs.json'], 3)
        library = {'schemaVersion': 'review-guidelines-4', 'guidelines': [{
            k: 'fixture' for k in ('id', 'axis', 'condition', 'normalContrast', 'requiredEvidence', 'missingContext')}]}
        source = 'static final String POLICY = """\n    fixture policy\n    """;'
        rates = {'inputUsdPerMillion': 0.1, 'outputUsdPerMillion': 0.5,
                 'sourceUrl': 'https://example.test/fixture', 'verifiedOn': 'fixture'}
        return p, source, library, rates

    def test_only_procedure_differs_and_no_case_examples_are_sent(self):
        config, artifact, provenance = tool.prepare(*self.fixture())
        self.assertEqual(config['baselinePrompt'] + tool.DELTA, config['candidatePrompt'])
        self.assertEqual(42, len(artifact['requests']))
        self.assertNotIn('sourceCaseIds', str(config['referenceSnapshot']))
        self.assertFalse(provenance['independentGeneralizationTest'])
        self.assertIsNone(provenance['qualityGateMet'])

    def test_rejects_over_budget_and_wrong_source(self):
        p, source, library, rates = self.fixture()
        rates['inputUsdPerMillion'] = 100000
        with self.assertRaises(ValueError): tool.prepare(p, source, library, rates)
        with self.assertRaises(ValueError): tool.prepare(p, 'no POLICY block', library, rates)
