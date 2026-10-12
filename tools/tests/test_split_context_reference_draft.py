import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from split_context_reference_draft import split


def fixture():
    return {'schemaVersion': 'review-guidelines-4', 'version': 'fixture',
            'status': 'WORKING_REFERENCE_NOT_VALIDATED', 'humanValidated': False,
            'contextDictionarySha256': 'a' * 64,
            'guidelines': [{'id': 'local-life-devaluation', 'sourceCaseIds': ['pisik-B', 'pisik-C'],
                            'condition': 'mixed', 'normalContrast': 'preserved'}],
            'examples': [{'id': key, 'mechanismIds': ['local-life-devaluation'],
                          'context': {'flow': ['preserved original text']}}
                         for key in ('pisik-B', 'pisik-C')]}


class SplitTests(unittest.TestCase):
    def test_split_preserves_base_and_example_text(self):
        base = fixture()
        frozen = copy.deepcopy(base)
        result, meta = split(base)
        self.assertEqual(base, frozen)
        self.assertEqual(result['examples'][0], base['examples'][0])
        self.assertEqual(result['examples'][1]['context'], base['examples'][1]['context'])
        self.assertEqual(result['examples'][1]['mechanismIds'], ['offering-choice-devaluation'])
        self.assertTrue(meta['ruleWordingChanged'])
        self.assertFalse(meta['defaultRuntimeChanged'])
        self.assertFalse(meta['detectionImprovementMeasured'])

    def test_new_source_ownership_requires_review(self):
        base = fixture()
        base['guidelines'][0]['sourceCaseIds'].append('new-source')
        with self.assertRaises(ValueError): split(base)

    def test_approved_base_and_repeated_split_refused(self):
        base = fixture()
        base['humanValidated'] = True
        with self.assertRaises(ValueError): split(base)
        candidate, _ = split(fixture())
        with self.assertRaises(ValueError): split(candidate)

    def test_missing_or_unexpected_example_refused(self):
        for missing in (True, False):
            base = fixture()
            if missing: base['examples'].pop()
            else: base['examples'][1]['mechanismIds'].append('other')
            with self.assertRaises(ValueError): split(base)
