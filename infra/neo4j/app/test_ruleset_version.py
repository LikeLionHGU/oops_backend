"""Checks immutable-version safety guards with the actual versioned JSON."""
import copy
import json
from pathlib import Path
import unittest

from import_ruleset_version import assert_exact, build_graph


class VersionSafetyTest(unittest.TestCase):
    def setUp(self):
        directory = Path(__file__).parents[1] / 'versions/0.4.1-draft'
        self.source = json.loads((directory / 'ruleset-oops-all-0.4.1-draft.json').read_text())
        self.stops = json.loads((directory / 'stop-judgments-0.4.1-draft.json').read_text())
        self.nodes, self.rels = build_graph(self.source, self.stops)
        self.actual = {
            'nodes': [{'labels': sorted(['OoPsEntity', n['label']]), 'props': copy.deepcopy(n['props'])} for n in self.nodes],
            'relationships': copy.deepcopy(self.rels),
        }

    def test_exact_version_accepted(self):
        assert_exact(self.actual, self.nodes, self.rels)

    def test_partial_version_rejected(self):
        self.actual['nodes'].pop()
        with self.assertRaises(ValueError):
            assert_exact(self.actual, self.nodes, self.rels)

    def test_edited_text_rejected(self):
        body = next(n for n in self.actual['nodes'] if 'minimum_information' in n['props'])
        body['props']['minimum_information'] += ' changed'
        with self.assertRaises(ValueError):
            assert_exact(self.actual, self.nodes, self.rels)

    def test_cross_version_link_rejected(self):
        link = copy.deepcopy(self.rels[0])
        link['source'] = 'criterion:G01-C01@ALL-v0.4-draft'
        link['props']['id'] = 'unexpected-cross-version-link'
        self.actual['relationships'].append(link)
        with self.assertRaises(ValueError):
            assert_exact(self.actual, self.nodes, self.rels)

    def test_wrong_stop_gate_rejected(self):
        self.stops['criteria'][0]['review_gate_id'] = 'review-gate:G01-C01@ALL-v0.4-draft:1.0'
        with self.assertRaises(ValueError):
            build_graph(self.source, self.stops)

    def test_mixed_criterion_version_rejected(self):
        self.source['groups'][0]['additional'][0]['version'] = '0.4-draft'
        with self.assertRaises(ValueError):
            build_graph(self.source, self.stops)


if __name__ == '__main__':
    unittest.main()
