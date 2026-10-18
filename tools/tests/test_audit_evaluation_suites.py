import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import audit_evaluation_suites as audit


class EvaluationSuiteAuditTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "datasets/controversy/evaluation").mkdir(parents=True)
        cases = []
        labels = []
        for name in ["R01", "R03", "R04"]:
            rows = [{"id": name + str(i), "text": "synthetic text " + str(i), "startMs": i * 2, "endMs": i * 2 + 1} for i in range(2)]
            cases.append({"reviewId": name, "sourceKind": "STORED_STT" if name == "R01" else "SYNTHETIC_DRAFT",
                          "anchorId": rows[1]["id"], "raw": rows})
            labels.append({"reviewId": name, "reviewStatus": "USER_APPROVED_DEVELOPMENT_LABEL",
                           "userAnswer": "approved test fixture", "expectedDecision": "REVIEW_REQUIRED" if name == "R04" else "PASS",
                           "presentedExcerptIds": [r["id"] for r in rows][1:] if name == "R01" else [r["id"] for r in rows]})
        self.packet = {"cases": cases}
        self.review = {"reviewStatus": "USER_APPROVED_DEVELOPMENT_LABELS",
                       "blindPacketCanonicalSha256": audit.sha(audit.canonical(self.packet)), "cases": labels}
        self.registry = {"schemaVersion": "evaluation-suite-registry-1", "activeSuite": "test", "purpose": "DEVELOPMENT",
                         "activeDecisions": {"R03": "PASS", "R04": "REVIEW_REQUIRED"}, "scopedDiagnostics": ["R01"],
                         "focusCase": "R01", "otherMaterials": [], "historyRoots": ["datasets/controversy/evaluation"]}
        self.sync()

    def sync(self):
        self.review["blindPacketCanonicalSha256"] = audit.sha(audit.canonical(self.packet))
        for key, name, value in [("sourcePacket", "source.json", self.packet), ("humanReview", "review.json", self.review)]:
            raw = audit.canonical(value)
            (self.root / name).write_bytes(raw)
            self.registry[key] = {"path": name, "sha256": audit.sha(raw)}

    def add_contrasts(self):
        original = Path(__file__).resolve().parents[2] / "oops-backend/src/test/resources/evals/context-content-contrasts-v1.json"
        raw = original.read_bytes()
        (self.root / "contrasts.json").write_bytes(raw)
        self.registry["supplementalContrasts"] = {
            "path": "contrasts.json", "sha256": audit.sha(raw),
            "activeDecisions": {"A01": "PASS", "A02": "REVIEW_REQUIRED", "A03": "PASS", "A04": "REVIEW_REQUIRED"}}

    def test_supplements_preserve_core_and_exclude_advisory_and_notes(self):
        before = audit.prepare(self.root, self.registry)
        self.add_contrasts()
        after = audit.prepare(self.root, self.registry)
        self.assertEqual(before["inputs.json"], after["inputs.json"][:2])
        self.assertEqual(before["labels.json"], after["labels.json"][:2])
        self.assertEqual(6, after["manifest.json"]["activeCount"])
        wire = json.dumps(after["inputs.json"])
        for forbidden in ["expectedDecision", "reviewerNotes", "proposedTags", "approval", "advisory"]:
            self.assertNotIn(forbidden, wire)
        self.assertTrue(all(not x["advisoryLabelApproved"] for x in after["labels.json"][2:]))

    def test_supplement_hash_or_label_drift_is_rejected(self):
        self.add_contrasts()
        self.registry["supplementalContrasts"]["activeDecisions"]["A01"] = "REVIEW_REQUIRED"
        with self.assertRaisesRegex(ValueError, "CONTRAST_LABEL_NOT_APPROVED"):
            audit.prepare(self.root, self.registry)
        (self.root / "contrasts.json").write_text("{}")
        with self.assertRaisesRegex(ValueError, "CONTRAST_HASH_MISMATCH"):
            audit.prepare(self.root, self.registry)

    def test_explicitly_unapproved_context_is_rejected_even_with_new_hash(self):
        self.add_contrasts()
        path = self.root / "contrasts.json"
        fixture = json.loads(path.read_bytes())
        fixture["cases"][0]["context"]["status"] = "DRAFT"
        raw = audit.canonical(fixture)
        path.write_bytes(raw)
        self.registry["supplementalContrasts"]["sha256"] = audit.sha(raw)
        with self.assertRaisesRegex(ValueError, "CONTRAST_LABEL_NOT_APPROVED"):
            audit.prepare(self.root, self.registry)

    def test_baseline_snapshot_is_configuration_not_a_performance_score(self):
        self.registry["baselineSources"] = ["source.json"]
        self.registry["workflow"] = {"candidate": None, "maximumConcurrentCandidates": 1}
        snapshot = audit.prepare(self.root, self.registry)["baseline-snapshot.json"]
        self.assertIsNone(snapshot["measuredSuiteResults"])
        self.assertFalse(snapshot["environmentOverridesCaptured"])
        self.assertFalse(snapshot["deploymentVerified"])
        self.assertEqual(self.registry["sourcePacket"]["sha256"], snapshot["files"][0]["sha256"])

    def test_only_full_approved_dialogues_are_active_with_separate_labels(self):
        before = audit.canonical(self.packet)
        result = audit.prepare(self.root, self.registry)
        self.assertEqual(["R03", "R04"], [c["caseId"] for c in result["inputs.json"]])
        self.assertNotIn("expectedDecision", json.dumps(result["inputs.json"]))
        self.assertIsNone(result["focus-R01.json"]["expectedDecision"])
        self.assertEqual("PASS", result["focus-R01.json"]["priorApproval"]["expectedDecision"])
        self.assertEqual(before, audit.canonical(self.packet))
        self.assertFalse(result["manifest.json"]["independentGold"])

    def test_mismatch_of_approval_and_active_window_is_rejected(self):
        self.review["cases"][1]["presentedExcerptIds"] = ["R030"]
        self.sync()
        with self.assertRaisesRegex(ValueError, "ACTIVE_APPROVAL_SCOPE_MISMATCH"):
            audit.prepare(self.root, self.registry)

    def test_changed_source_is_not_silently_accepted(self):
        (self.root / "source.json").write_text("{}")
        with self.assertRaisesRegex(ValueError, "SOURCE_HASH_MISMATCH"):
            audit.prepare(self.root, self.registry)

    def test_unapproved_label_and_unknown_case_cannot_be_promoted(self):
        self.review["cases"][1]["reviewStatus"] = "DRAFT"
        self.sync()
        with self.assertRaisesRegex(ValueError, "LABEL_NOT_APPROVED"):
            audit.prepare(self.root, self.registry)
        other = copy.deepcopy(self.registry)
        other["activeDecisions"]["unapproved"] = "PASS"
        with self.assertRaisesRegex(ValueError, "CASE_ALLOWLIST_MISMATCH"):
            audit.prepare(self.root, other)

    def test_duplicate_ids_and_changed_decision_are_rejected(self):
        self.packet["cases"][0]["raw"][0]["id"] = "R011"
        self.sync()
        with self.assertRaisesRegex(ValueError, "DUPLICATE_CASE_ID"):
            audit.prepare(self.root, self.registry)
        self.packet["cases"][0]["raw"][0]["id"] = "R010"
        self.sync()
        self.registry["activeDecisions"]["R03"] = "REVIEW_REQUIRED"
        with self.assertRaisesRegex(ValueError, "ACTIVE_LABEL_MISMATCH"):
            audit.prepare(self.root, self.registry)

    def test_private_output_never_overwrites_sources(self):
        result = audit.prepare(self.root, self.registry)
        output = self.root / "datasets/controversy/evaluation/active-test"
        audit.write_new(self.root, output, result)
        self.assertEqual(0o700, output.stat().st_mode & 0o777)
        self.assertEqual(0o600, (output / "inputs.json").stat().st_mode & 0o777)
        with self.assertRaisesRegex(ValueError, "NEW_PRIVATE_OUTPUT_REQUIRED"):
            audit.write_new(self.root, output, result)
        with self.assertRaisesRegex(ValueError, "NEW_PRIVATE_OUTPUT_REQUIRED"):
            audit.write_new(self.root, self.root / "outside", result)

    def focused_review(self):
        focus = audit.canonical({"source": self.packet["cases"][0]})
        (self.root / "focus.json").write_bytes(focus)
        review = {"status": "USER_APPROVED_FULL_WINDOW_UNITS_DEVELOPMENT_ONLY", "sourceReviewId": "R01",
                  "sourcePacketSha256": self.registry["sourcePacket"]["sha256"], "familyId": "one-shared-dialogue",
                  "sourceFile": {"path": "focus.json", "sha256": audit.sha(focus)},
                  "reviewedSegmentIds": ["R010", "R011"], "independentGold": False,
                  "runtimePromotionApproved": False, "trainingUseApproved": False,
                  "units": [{"reviewUnitId": "focus-" + str(i), "anchorId": "R01" + str(i),
                             "userAnswer": "PASS — approved test fixture", "expectedDecision": "PASS"} for i in range(2)]}
        self.sync_focused(review)
        return review

    def sync_focused(self, review):
        raw = audit.canonical(review)
        (self.root / "approved-focus.json").write_bytes(raw)
        self.registry["approvedFocusedReview"] = {"path": "approved-focus.json", "sha256": audit.sha(raw),
                                                  "sourceReviewId": "R01", "activeDecisions": {"focus-0": "PASS", "focus-1": "PASS"}}

    def test_full_window_approved_units_share_one_family_without_relabeling_old_case(self):
        self.focused_review()
        result = audit.prepare(self.root, self.registry)
        self.assertEqual(4, result["manifest.json"]["activeCount"])
        self.assertEqual(1, result["manifest.json"]["realVideoFamiliesInActiveSuite"])
        self.assertEqual(2, result["manifest.json"]["storedSttIssueCount"])
        self.assertEqual(result["inputs.json"][-1]["raw"], result["inputs.json"][-2]["raw"])
        self.assertNotIn("expectedDecision", json.dumps(result["inputs.json"]))
        self.assertIsNone(result["focus-R01.json"]["expectedDecision"])
        self.assertFalse(result["manifest.json"]["excludedFromMainScore"][0]["approvalRevoked"])
        self.assertEqual("PASS", result["labels.json"][-1]["expectedDecision"])

    def test_focused_approval_cannot_cover_a_different_or_partial_window(self):
        review = self.focused_review()
        review["reviewedSegmentIds"] = ["R011"]
        self.sync_focused(review)
        with self.assertRaisesRegex(ValueError, "FOCUSED_WINDOW_SCOPE_MISMATCH"):
            audit.prepare(self.root, self.registry)
        review["reviewedSegmentIds"] = ["R010", "R011"]
        self.sync_focused(review)
        (self.root / "focus.json").write_text("{}")
        with self.assertRaisesRegex(ValueError, "FOCUSED_SOURCE_MISMATCH"):
            audit.prepare(self.root, self.registry)

    def test_focused_pending_record_and_changed_answer_are_not_promoted(self):
        review = self.focused_review()
        review["status"] = "PENDING"
        self.sync_focused(review)
        with self.assertRaisesRegex(ValueError, "FOCUSED_REVIEW_NOT_APPROVED"):
            audit.prepare(self.root, self.registry)
        review["status"] = "USER_APPROVED_FULL_WINDOW_UNITS_DEVELOPMENT_ONLY"
        review["units"][0]["expectedDecision"] = "REVIEW_REQUIRED"
        self.sync_focused(review)
        with self.assertRaisesRegex(ValueError, "FOCUSED_LABEL_INVALID"):
            audit.prepare(self.root, self.registry)


if __name__ == "__main__":
    unittest.main()
