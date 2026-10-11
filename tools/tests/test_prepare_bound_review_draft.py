import copy
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prepare_bound_review_draft import build
from run_issue_scoped_comparison import AUTHORIZATION, execute, prepare, prepare_catalog
from fixed_review_suite import digest
from test_run_issue_scoped_comparison import source
from test_span_catalog_review import catalog_response


class BoundDraftTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        s = source(); first = prepare(s, AUTHORIZATION)
        prior = dict(schemaVersion=first['schemaVersion'], artifactHash=first['artifactHash'],
                     authorizationHash=digest(AUTHORIZATION), status='COMPLETED_PENDING_JAVA_AUDIT',
                     reservedCostUsd=first['reservedCostUsd'])
        cls.frozen = prepare_catalog(s, AUTHORIZATION, prior)
        with tempfile.TemporaryDirectory() as tmp:
            cls.report = execute(cls.frozen, Path(tmp)/'datasets'/'run', 'fake', catalog_response)

    def test_all_source_scopes_are_preserved_without_prior_judgment_regrading_or_api(self):
        before = copy.deepcopy((self.frozen, self.report))
        draft = build(self.frozen, self.report)
        self.assertEqual(draft['draftsCount'], 27); self.assertEqual(draft['apiCalls'], 0)
        self.assertFalse(draft['executionEnabled']); self.assertFalse(draft['priorJudgmentsRegraded'])
        self.assertIsNone(draft['semanticQualityGateMet']); self.assertIsNone(draft['reservationUsd'])
        for d, unit in zip(draft['drafts'], self.report['calls']):
            self.assertEqual(d['attempt']['selectedScope'], unit['selectedScope'])
            self.assertNotIn('expectedDecision', d['attempt']['requestBody'])
            self.assertNotIn('providerResponse', d['attempt'])
        self.assertEqual((self.frozen, self.report), before)

    def test_source_tampering_or_wrong_selection_body_is_rejected(self):
        for mode in ('scope', 'raw', 'body', 'identity', 'partial'):
            bad = copy.deepcopy(self.report); unit = bad['calls'][0]; stage = unit['stages'][0]
            if mode == 'scope': unit['selectedScope']['evidence'][0]['quote'] = 'invented'
            if mode == 'raw': stage['rawOutput']['issueSummary'] = 'changed'
            if mode == 'body': stage['body']['store'] = True
            if mode == 'identity': unit['unitIndex'] = 2
            if mode == 'partial': bad['status'] = 'PARTIAL'
            with self.subTest(mode=mode), self.assertRaises(ValueError): build(self.frozen, bad)

    def test_changing_old_judge_does_not_change_new_request_or_regrade_old_output(self):
        before = build(self.frozen, self.report)
        changed = copy.deepcopy(self.report)
        changed['calls'][0]['stages'][1]['providerResponse'] = {'invalid-old-judge': True}
        after = build(self.frozen, changed)
        self.assertEqual(before['drafts'], after['drafts'])
        self.assertNotEqual(before['sourceReportHash'], after['sourceReportHash'])
