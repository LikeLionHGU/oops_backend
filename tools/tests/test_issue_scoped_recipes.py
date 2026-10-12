"""Frozen hashes pinned before the 335d125 recipe extraction; offline only."""
import copy
from decimal import Decimal
import json
from pathlib import Path
import subprocess
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import issue_scoped_recipes as recipes
import run_issue_scoped_comparison as runner
from experiment_costs import positive, reserve
from fixed_review_suite import digest
from test_run_issue_scoped_comparison import source


# Full logical snapshots, including their own artifactHash, from the old runner.
GOLDEN = {
    'initial': 'd9687776a55257160ec59e6d8b2b8cfd13d4f73a2d8fbede24906bba4dda8a07',
    'catalog': 'd49986ed118999c3b2c1145ffb502f2fcc328235f209ea8288554ebec3fe1a48',
    'bound': '331e4396632272da10baa214277dbf485ebec411e133fb70444baf9e76669ecb',
    'funded': 'b7b748aab0da934e9bd7378020661d62195531c83265c06db2c2499635360bff',
    'target': 'c2e7fde16d1f28d578d298ca4f1c8ba4aa9c01b9a7c2f99745180f2dbd7a2790',
    'full': 'f04fa2e2585493aa7bc8aab22800e322b97e15582460fb41e3e6882ced2039e9',
}


def fixture_chain():
    original = source()
    initial = recipes.prepare(original, recipes.AUTHORIZATION)
    prior = dict(schemaVersion=initial['schemaVersion'], artifactHash=initial['artifactHash'],
                 authorizationHash=digest(recipes.AUTHORIZATION), status='COMPLETED_PENDING_JAVA_AUDIT',
                 reservedCostUsd=initial['reservedCostUsd'])
    catalog = recipes.prepare_catalog(original, recipes.AUTHORIZATION, prior)
    bound = recipes.prepare_bound(original, recipes.AUTHORIZATION, catalog)
    # Only synthetic fixture approval metadata; never claim a ledger reservation.
    funding = dict(recipes.BOUND_FUNDING, previousRemainingUsd=bound['remainingBudgetUsd'])
    with patch('issue_scoped_recipes.BOUND_FUNDING', funding):
        funded = recipes.fund_bound(bound, funding)
        meta = dict(schemaVersion=recipes.BOUND_SCHEMA, artifactHash=funded['artifactHash'],
                    additionalFundingHash=funded['additionalFundingHash'], status='COMPLETED_PENDING_JAVA_AUDIT',
                    reservedCostUsd=funded['reservedCostUsd'], runKind='LIVE')
        target = recipes.prepare_target_selection(funded, meta)
        next_funding = dict(recipes.BOUNDARY_FUNDING, previousRemainingUsd=target['remainingBudgetUsd'])
        previous = dict(meta, artifactHash=target['artifactHash'], reservedCostUsd=target['reservedCostUsd'])
        with patch('issue_scoped_recipes.BOUNDARY_FUNDING', next_funding):
            full = recipes.prepare_full_boundary(funded, meta, previous, next_funding)
            for artifact in (initial, catalog, bound, funded, target, full): recipes.validate(artifact)
    return dict(initial=initial, catalog=catalog, bound=bound, funded=funded, target=target, full=full)


class RecipeTests(unittest.TestCase):
    def test_historical_six_snapshots_and_requests_remain_identical(self):
        with patch('reservation_ledger.claim', side_effect=AssertionError('reservation forbidden')), \
             patch('urllib.request.OpenerDirector.open', side_effect=AssertionError('network forbidden')), \
             patch('collect_youtube_comments.read_key', side_effect=AssertionError('key loading forbidden')):
            chain = fixture_chain()
        for name, artifact in chain.items():
            with self.subTest(recipe=name):
                self.assertEqual(digest(artifact), GOLDEN[name])
                self.assertEqual(artifact['artifactHash'], digest({k: v for k, v in artifact.items() if k != 'artifactHash'}))
                for job in artifact['jobs']:
                    for stage in job['stages']: self.assertEqual(stage['requestHash'], digest(stage['body']))

    def test_tampered_source_or_approval_is_not_accepted(self):
        original = source(); before = copy.deepcopy(original)
        bad = dict(recipes.AUTHORIZATION, userAuthorized=False)
        with self.assertRaises(ValueError): recipes.prepare(original, bad)
        self.assertEqual(original, before)
        artifact = recipes.prepare(original, recipes.AUTHORIZATION)
        artifact['jobs'][0]['stages'][0]['body']['store'] = True
        artifact['artifactHash'] = digest({k: v for k, v in artifact.items() if k != 'artifactHash'})
        with self.assertRaises(ValueError): recipes.validate(artifact)

    def test_existing_runner_entry_points_are_compatibility_exports(self):
        for name in ('prepare', 'validate', 'prepare_catalog', 'prepare_bound', 'fund_bound',
                     'prepare_target_selection', 'prepare_full_boundary', 'bound_judgment_request'):
            self.assertIs(getattr(runner, name), getattr(recipes, name))

    def test_recipe_import_does_not_load_execution_or_ledger(self):
        code = 'import sys,json; sys.path.insert(0,sys.argv[1]); import issue_scoped_recipes; print(json.dumps([m for m in ("run_issue_scoped_comparison","run_boundary_comparison","reservation_ledger") if m in sys.modules]))'
        result = subprocess.run([sys.executable, '-c', code, str(Path(__file__).resolve().parents[1])],
                                check=True, capture_output=True, text=True, timeout=10)
        self.assertEqual(json.loads(result.stdout), [])

    def test_reservation_math_and_legacy_cost_exports_are_unchanged(self):
        from run_boundary_comparison import positive as legacy_positive, reserve as legacy_reserve
        self.assertIs(legacy_positive, positive); self.assertIs(legacy_reserve, reserve)
        body = {'text': '원문'}
        config = dict(maxCompletionTokens=8000, pricing=dict(inputUsdPerMillion='0.25', outputUsdPerMillion='2'))
        tokens, cost = reserve(body, config)
        self.assertEqual(tokens, len(json.dumps(body, ensure_ascii=False).encode('utf-8')) + 2000)
        self.assertEqual(cost, (Decimal(tokens) * Decimal('.25') + Decimal(8000) * 2) / 1000000)
        for bad in (True, 0, -1, 'NaN', 'Infinity'):
            with self.subTest(value=bad), self.assertRaises(ValueError): positive(bad)
