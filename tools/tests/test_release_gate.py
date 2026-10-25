import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import release_gate as gate


def sha(text):
    return hashlib.sha256(text.encode()).hexdigest()


class ReleaseGateTest(unittest.TestCase):
    def setUp(self):
        self.bundle_payloads = {}
        self.suite = {
            "schemaVersion": "independent-video-suite-1", "suiteId": "heldout-v1",
            "status": "FROZEN_FOR_EVALUATION", "independentGold": True,
            "frozenAt": "2026-01-01T00:00:00Z",
            "developmentSourceGroups": ["dev-a"],
            "reviewProtocol": {"status": "USER_APPROVED_INDEPENDENT_HOLDOUT",
                "preparedWithoutModelOutputs": True, "reviewedAfterCandidateRun": False,
                "trainingUseAuthorized": False},
            "cases": [
                {"caseId": "case-review", "sourceGroupId": "heldout-a", "sourceGroupVerified": True,
                 "sourceSha256": sha("source-a"), "inputSha256": sha("input-a"),
                 "categoryIds": ["body-image"], "labelStatus": "USER_APPROVED",
                 "expectedDecision": "REVIEW_REQUIRED", "referenceReviewStatus": "USER_APPROVED",
                 "expectedRelevantReferenceIds": ["ref-a"]},
                {"caseId": "case-pass", "sourceGroupId": "heldout-b", "sourceGroupVerified": True,
                 "sourceSha256": sha("source-b"), "inputSha256": sha("input-b"),
                 "categoryIds": ["local-review"], "labelStatus": "USER_APPROVED",
                 "expectedDecision": "PASS", "referenceReviewStatus": "USER_APPROVED",
                 "expectedRelevantReferenceIds": []},
            ]}
        fields = gate.QUALITY_METRICS + ("maxCostUsd",)
        zero = {key: 0 for key in fields}
        zero["maxCostUsd"] = "0.05"
        self.criteria = {"schemaVersion": "release-criteria-1", "status": "LOCKED_BEFORE_RUN",
            "lockedAt": "2026-01-02T00:00:00Z",
            "suiteId": "heldout-v1", "comparisonKind": "RELEASE_CANDIDATE",
            "minimumIndependentSourceGroups": 2, "minimumCasesPerCategory": 1,
            "requireNoQualityRegression": True, "requireImprovement": True,
            "maximumCostRatio": "1.5", "overallThresholds": copy.deepcopy(zero),
            "categoryThresholds": {"body-image": copy.deepcopy(zero), "local-review": copy.deepcopy(zero)}}
        self.suite_sha = gate.canonical_sha(self.suite)
        self.criteria_sha = gate.canonical_sha(self.criteria)
        self.baseline = self.make_run("a", "LEXICAL", [
            self.row("case-review", "PASS", ["unrelated"], "0.010"),
            self.row("case-pass", "PASS", [], "0.010")])
        self.candidate = self.make_run("b", "STRUCTURED", [
            self.row("case-review", "REVIEW_REQUIRED", ["ref-a"], "0.012"),
            self.row("case-pass", "PASS", [], "0.012")])

    def row(self, cid, decision, refs, cost):
        return {"caseId": cid, "decision": decision, "status": "COMPLETED",
            "evidenceStatus": "NOT_APPLICABLE", "duplicateFindings": 0,
            "selectedReferenceIds": refs, "payloadCodePoints": 200,
            "payloadBudgetCodePoints": 1000, "costUsd": cost, "costBasis": "PROVIDER_REPORTED"}

    def make_run(self, suffix, variant, rows):
        payloads = {"application.yml": "settings-" + suffix, "runtime.json": "runtime-" + suffix}
        artifacts = [{"path": path, "sha256": sha(content)} for path, content in payloads.items()]
        artifacts = sorted(artifacts, key=lambda row: row["path"])
        run = {"schemaVersion": "release-evaluation-run-1", "suiteId": "heldout-v1",
            "suiteSha256": self.suite_sha, "criteriaSha256": self.criteria_sha,
            "artifactManifest": artifacts, "artifactBundleSha256": gate.canonical_sha(artifacts),
            "startedAt": "2026-01-03T00:00:00Z", "searchVariant": variant,
            "fingerprints": {name: sha(name + ("-changed" if name == "retrieval" and suffix == "b" else ""))
                for name in ("code", "model", "prompt", "policy", "runtime", "settings", "retrieval")},
            "cases": rows}
        self.bundle_payloads[run["artifactBundleSha256"]] = payloads
        return run

    def materialize_bundle(self, run, root):
        root.mkdir()
        for path, content in self.bundle_payloads[run["artifactBundleSha256"]].items():
            target = root / path
            target.write_text(content)

    def compare(self, baseline=None, candidate=None, criteria=None, suite=None, approval=None):
        s, c = suite or self.suite, criteria or self.criteria
        return gate.compare(s, baseline or self.baseline, candidate or self.candidate, c,
                            self.suite_sha if s is self.suite else gate.canonical_sha(s),
                            self.criteria_sha if c is self.criteria else gate.canonical_sha(c), approval)

    def test_improvement_is_reported_but_never_promoted_automatically(self):
        result = self.compare()
        self.assertEqual("READY_FOR_HUMAN_REVIEW", result["decision"])
        self.assertFalse(result["deploymentEligible"])
        self.assertFalse(result["automaticPromotion"])
        self.assertEqual(0, result["candidateMetrics"]["overall"]["missedReviews"])
        self.assertEqual(0, result["candidateMetrics"]["overall"]["missedRelevantReferences"])
        self.assertEqual(1, result["baselineMetrics"]["overall"]["missedReviews"])

    def test_exact_candidate_and_criteria_approval_is_only_eligibility_not_deploy(self):
        approval = {"status": "USER_APPROVED_FOR_DEPLOYMENT",
            "candidateArtifactBundleSha256": self.candidate["artifactBundleSha256"],
            "criteriaSha256": self.criteria_sha, "rollbackRehearsal": "PASS"}
        result = self.compare(approval=approval)
        self.assertFalse(result["deploymentEligible"])
        self.assertEqual("NOT_RUN", result["rollbackRehearsal"])
        self.assertFalse(result["automaticPromotion"])
        approval["candidateArtifactBundleSha256"] = sha("other")
        self.assertFalse(self.compare(approval=approval)["deploymentEligible"])

    def test_exact_bundles_rollback_and_approval_are_all_required_for_eligibility(self):
        approval = {"status": "USER_APPROVED_FOR_DEPLOYMENT",
            "candidateArtifactBundleSha256": self.candidate["artifactBundleSha256"],
            "criteriaSha256": self.criteria_sha, "rollbackRehearsal": "PASS"}
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            base, cand = root / "base", root / "candidate"
            self.materialize_bundle(self.baseline, base)
            self.materialize_bundle(self.candidate, cand)
            result = gate.compare(self.suite, self.baseline, self.candidate, self.criteria,
                self.suite_sha, self.criteria_sha, approval, base, cand, True)
            self.assertTrue(result["deploymentEligible"])
            self.assertEqual("PASS", result["rollbackRehearsal"])
            self.assertFalse(result["automaticPromotion"])

    def test_bundle_verification_rejects_extra_files_and_hash_drift(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "bundle"
            self.materialize_bundle(self.candidate, root)
            (root / "unlisted.txt").write_text("extra")
            with self.assertRaisesRegex(ValueError, "BUNDLE_FILE_SET_MISMATCH"):
                gate.verify_bundle(root, self.candidate["artifactManifest"])
            (root / "unlisted.txt").unlink()
            (root / "runtime.json").write_text("mutated")
            with self.assertRaisesRegex(ValueError, "BUNDLE_FILE_HASH_MISMATCH"):
                gate.verify_bundle(root, self.candidate["artifactManifest"])

    def test_bundle_root_symlink_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            real = Path(temp) / "real"
            self.materialize_bundle(self.candidate, real)
            linked = Path(temp) / "linked"
            linked.symlink_to(real, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, "BUNDLE_ROOT_INVALID"):
                gate.verify_bundle(linked, self.candidate["artifactManifest"])

    def test_category_regression_is_not_hidden_by_overall_threshold(self):
        candidate = copy.deepcopy(self.candidate)
        candidate["cases"][1]["decision"] = "REVIEW_REQUIRED"
        candidate["cases"][1]["evidenceStatus"] = "REVIEWED_VALID"
        result = self.compare(candidate=candidate)
        self.assertEqual("REJECT_CANDIDATE", result["decision"])
        self.assertTrue(any(row["scope"] == "local-review" and row["metric"] == "falseWarnings"
                            for row in result["failures"]))

    def test_independent_eligibility_and_frozen_criteria_fail_closed(self):
        suite = copy.deepcopy(self.suite)
        suite["independentGold"] = False
        with self.assertRaisesRegex(ValueError, "SUITE_NOT_INDEPENDENT_GOLD"):
            self.compare(suite=suite)
        criteria = copy.deepcopy(self.criteria)
        criteria["status"] = "DRAFT"
        with self.assertRaisesRegex(ValueError, "CRITERIA_NOT_PRELOCKED"):
            self.compare(criteria=criteria)

    def test_criteria_locked_after_a_run_is_rejected_even_with_updated_hashes(self):
        criteria = copy.deepcopy(self.criteria)
        criteria["lockedAt"] = "2026-01-04T00:00:00Z"
        criteria_sha = gate.canonical_sha(criteria)
        baseline, candidate = copy.deepcopy(self.baseline), copy.deepcopy(self.candidate)
        baseline["criteriaSha256"] = candidate["criteriaSha256"] = criteria_sha
        with self.assertRaisesRegex(ValueError, "PLAN_NOT_FROZEN_BEFORE_RUNS"):
            gate.compare(self.suite, baseline, candidate, criteria, self.suite_sha, criteria_sha)

    def test_case_from_development_group_and_missing_reference_review_are_rejected(self):
        suite = copy.deepcopy(self.suite)
        suite["cases"][0]["sourceGroupId"] = "dev-a"
        with self.assertRaisesRegex(ValueError, "CASE_SOURCE_NOT_INDEPENDENT"):
            self.compare(suite=suite)
        suite = copy.deepcopy(self.suite)
        suite["cases"][0]["referenceReviewStatus"] = "DRAFT"
        with self.assertRaisesRegex(ValueError, "REFERENCE_RELEVANCE_NOT_REVIEWED"):
            self.compare(suite=suite)

    def test_missing_cases_changed_suite_labels_and_label_leakage_are_rejected(self):
        candidate = copy.deepcopy(self.candidate)
        candidate["cases"].pop()
        with self.assertRaisesRegex(ValueError, "RUN_CASE_COVERAGE_MISMATCH"):
            self.compare(candidate=candidate)
        suite = copy.deepcopy(self.suite)
        suite["cases"][0]["expectedDecision"] = "PASS"
        with self.assertRaisesRegex(ValueError, "RUN_SUITE_MISMATCH"):
            self.compare(suite=suite)
        candidate = copy.deepcopy(self.candidate)
        candidate["cases"][0]["expectedDecision"] = "REVIEW_REQUIRED"
        with self.assertRaisesRegex(ValueError, "RUN_LABEL_LEAKAGE"):
            self.compare(candidate=candidate)

    def test_search_ablation_changes_retrieval_fingerprint_only(self):
        criteria = copy.deepcopy(self.criteria)
        criteria["comparisonKind"] = "SEARCH_ABLATION"
        criteria["maximumCostRatio"] = "2"
        criteria_sha = gate.canonical_sha(criteria)
        baseline, candidate = copy.deepcopy(self.baseline), copy.deepcopy(self.candidate)
        baseline["criteriaSha256"] = candidate["criteriaSha256"] = criteria_sha
        result = gate.compare(self.suite, baseline, candidate, criteria, self.suite_sha, criteria_sha)
        self.assertEqual("READY_FOR_HUMAN_REVIEW", result["decision"])
        candidate["fingerprints"]["prompt"] = sha("prompt-changed")
        with self.assertRaisesRegex(ValueError, "ABLATION_MUST_CHANGE_RETRIEVAL_ONLY"):
            gate.compare(self.suite, baseline, candidate, criteria, self.suite_sha, criteria_sha)

    def test_rollback_round_trip_only_changes_temporary_stage(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            base, cand = root / "base", root / "candidate"
            base.mkdir(); cand.mkdir()
            (base / "runtime.json").write_text('{"version":"old"}')
            (base / "application.yml").write_text("old-settings")
            (cand / "runtime.json").write_text('{"version":"new"}')
            (cand / "new-only.yml").write_text("new-settings")
            before = {p.name: p.read_bytes() for p in base.iterdir()}
            candidate_before = {p.name: p.read_bytes() for p in cand.iterdir()}
            result = gate.rollback_rehearsal(base, cand, ["runtime.json", "application.yml"],
                                             ["runtime.json", "new-only.yml"])
            self.assertEqual("PASS", result["status"])
            self.assertEqual(before, {p.name: p.read_bytes() for p in base.iterdir()})
            self.assertEqual(candidate_before, {p.name: p.read_bytes() for p in cand.iterdir()})

    def test_rollback_rejects_escape_and_secret_paths(self):
        with self.assertRaisesRegex(ValueError, "BUNDLE_PATH_INVALID"):
            gate._safe_relative("../outside")
        with self.assertRaisesRegex(ValueError, "SENSITIVE_BUNDLE_PATH"):
            gate._safe_relative("config/.env")

    def test_current_registry_is_not_promotable(self):
        registry = json.loads((Path(__file__).resolve().parents[1] / "evaluation-suites.json").read_bytes())
        result = gate.current_readiness(registry)
        self.assertEqual("BLOCKED", result["decision"])
        self.assertIn("ACTIVE_SUITE_IS_NOT_INDEPENDENT_HOLDOUT", result["reasons"])
        self.assertFalse(result["automaticPromotion"])


if __name__ == "__main__":
    unittest.main()
