import copy
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import review_boundary_pairs as tool
from test_fixed_review_suite import fixture


def boundary(suite):
    rows = {r['id']: r for r in suite['cases']}
    r, n = 'pisik-D', 'pisik-yeongyang-normal_control-3'
    return {'schemaVersion': 'review-boundary-pairs-1', 'status': 'DEVELOPMENT_ONLY',
            'runtimeUseAuthorized': False, 'trainingUseAuthorized': False,
            'curatedPromotionAuthorized': False, 'pairs': [
                {'id': 'body-metaphor-nostalgia', 'reviewCaseId': r, 'normalCaseId': n,
                 'reviewInputHash': rows[r]['inputHash'], 'normalInputHash': rows[n]['inputHash'],
                 'mechanismId': 'graphic-metaphor', 'contextDifference': 'fixture contrast only',
                 'requiredReviewQuotes': ['fixture 79000'], 'forbiddenInferences': ['actual violence'],
                 'reviewStatus': 'OWNER_DEVELOPMENT_ONLY'}]}


class BoundaryTests(unittest.TestCase):
    def test_valid_pair_preserves_original(self):
        suite = fixture()
        original = copy.deepcopy(suite)
        self.assertEqual(['pisik-D', 'pisik-yeongyang-normal_control-3'],
                         tool.validate_pairs(suite['cases'], boundary(suite)))
        self.assertEqual(original, suite)

    def test_rejects_bad_hash_roles_quote_and_duplicate(self):
        suite = fixture()
        for key, value in [('reviewInputHash', 'bad'), ('normalCaseId', 'pisik-C'),
                           ('requiredReviewQuotes', ['invented quote']), ('contextDifference', ''),
                           ('reviewStatus', 'APPROVED')]:
            doc = boundary(suite)
            doc['pairs'][0][key] = value
            with self.assertRaises(ValueError): tool.validate_pairs(suite['cases'], doc)
        doc = boundary(suite)
        doc['pairs'].append(copy.deepcopy(doc['pairs'][0]))
        with self.assertRaises(ValueError): tool.validate_pairs(suite['cases'], doc)

    def test_rejects_split_group_leakage_and_promotion(self):
        for field, value in [('split', 'CONFIRMATION_CANDIDATE'), ('contrastGroupId', 'other')]:
            suite = fixture()
            doc = boundary(suite)
            next(r for r in suite['cases'] if r['id'] == doc['pairs'][0]['normalCaseId'])[field] = value
            with self.assertRaises(ValueError): tool.validate_pairs(suite['cases'], doc)
        suite = fixture()
        doc = boundary(suite)
        doc['runtimeUseAuthorized'] = True
        with self.assertRaises(ValueError): tool.validate_pairs(suite['cases'], doc)

    def test_comparison_remains_unexecuted_and_answers_separate(self):
        suite = fixture()
        doc = boundary(suite)
        ids = tool.validate_pairs(suite['cases'], doc)
        owner = {'schemaVersion': 'owner-development-review-1', 'reviewerRole': 'PROJECT_OWNER',
                 'suiteRegistryHash': suite['registryHash'], 'independentReviewCompleted': False,
                 'curatedPromotionAuthorized': False, 'runtimeUseAuthorized': False, 'trainingUseAuthorized': False,
                 'cases': [{'caseId': cid, 'inputHash': next(r for r in suite['cases'] if r['id'] == cid)['inputHash'],
                            'developmentExpectedDecision': decision}
                           for cid, decision in zip(ids, ['REVIEW_REQUIRED', 'PASS'])]}
        bundle = tool.build_bundle(suite, doc, owner)
        plan = bundle['comparison-draft.json']
        self.assertEqual(12, plan['plannedEvaluationJobs'])
        self.assertIsNone(plan['plannedApiCalls'])
        self.assertFalse(plan['apiExecutionEnabled'])
        self.assertTrue(all(j['decision'] is None and j['targetIssueFound'] is None for j in plan['jobs']))
        self.assertNotIn('fixture contrast', str(bundle['model-inputs.json']))
        owner['cases'][1]['developmentExpectedDecision'] = 'UNCERTAIN'
        with self.assertRaises(ValueError): tool.build_bundle(suite, doc, owner)

    def test_variable_size_boundary_registry_100_and_500(self):
        suite = fixture()
        base = boundary(suite)
        indexed = {r['id']: r for r in suite['cases']}
        for count in (100, 500):
            doc = copy.deepcopy(base)
            doc['pairs'] = []
            rows = []
            for i in range(count):
                pair = copy.deepcopy(base['pairs'][0])
                pair['id'] += f'-{i}'
                for role in ('review', 'normal'):
                    row = copy.deepcopy(indexed[pair[role + 'CaseId']])
                    row['id'] += f'-{i}'
                    pair[role + 'CaseId'] = row['id']
                    rows.append(row)
                doc['pairs'].append(pair)
            self.assertEqual(count * 2, len(tool.validate_pairs(rows, doc)))
