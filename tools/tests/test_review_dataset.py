"""Synthetic declarations only; these fixtures are NOT approved human examples."""
import copy
import importlib.util
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("review_dataset", ROOT / "review_dataset.py")
tool = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(tool)


def fixture():
    judgment = {"decision": "PASS", "axis": "NONE", "target": None,
                "reason": "음식 취향의 평가", "normalInterpretation": "음식 취향",
                "missingInformation": [], "evidence": [{"segmentId": "s1", "quote": "맛이 별로다"}]}
    annotations = [dict(copy.deepcopy(judgment), id="ann_" + r, caseId="case1", reviewerId=r,
                        stage="BLIND", reactionIds=[]) for r in ("synthetic_a", "synthetic_b")]
    return {"schemaVersion": "1", "version": "synthetic-test-only", "sources": [
        {"id": "src1", "familyId": "synthetic_family", "kind": "DIRECT_FEEDBACK", "split": "TRAIN",
         "provenance": "Synthetic test fixture; not real consent", "rightsBasis": "Synthetic fixture only",
         "allowedUses": ["LOCAL_REVIEW", "RETRIEVAL"], "privacyReviewed": True, "fullRawSpeech": ["맛이 별로다"]}],
        "cases": [{"id": "case1", "sourceId": "src1", "status": "READY", "contrastGroupId": "taste",
                   "contextSummary": "이 음식은 맛이 별로다", "retrievalTerms": ["음식", "맛이"],
                   "anchorSegmentId": "s1", "segments": [{"id": "s1", "rawText": "맛이 별로다",
                   "verifiedText": None, "startMs": 0, "endMs": 2000}], "frames": []}],
        "reactions": [], "annotations": annotations,
        "adjudications": [dict(copy.deepcopy(judgment), id="adj1", caseId="case1", adjudicatorId="synthetic_c",
                               annotationIds=[a["id"] for a in annotations], reviewedAt="2026-01-01",
                               resolutionReason="Synthetic test agreement")]}


class DatasetTests(unittest.TestCase):
    def test_empty_dataset(self):
        dataset = {"schemaVersion": "1", "version": "synthetic-empty", "sources": [],
                   "cases": [], "reactions": [], "annotations": [], "adjudications": []}
        self.assertTrue(all(not rows for rows in tool.validate(dataset)))
        self.assertEqual([], tool.export_retrieval(dataset, ["pisik-yeongyang"])["cases"])

    def test_export_shape_and_fingerprint(self):
        archive = tool.export_retrieval(fixture(), [])
        case = archive["cases"][0]
        self.assertEqual(tool.fingerprint(["맛이 별로다"]), case["sourceFingerprint"])
        self.assertEqual("APPROVED", case["reviewStatus"])
        self.assertNotIn("annotations", case)
        self.assertNotIn("provenance", case)

    def test_no_automatic_synthetic_or_benchmark_export(self):
        for kind, family, split in (("SYNTHETIC", "synthetic_family", "TRAIN"),
                                    ("DIRECT_FEEDBACK", "pisik-yeongyang", "DEVELOPMENT"),
                                    ("DIRECT_FEEDBACK", "pisik-yeongyang", "TRAIN")):
            dataset = fixture()
            dataset["sources"][0].update(kind=kind, familyId=family, split=split)
            self.assertEqual([], tool.export_retrieval(dataset, ["pisik-yeongyang"])["cases"])

    def test_no_family_or_contrast_leakage(self):
        for same_family in (True, False):
            dataset = fixture()
            source = dict(dataset["sources"][0], id="src2", split="TEST",
                          familyId="synthetic_family" if same_family else "other_family")
            dataset["sources"].append(source)
            dataset["cases"].append(dict(dataset["cases"][0], id="case2", sourceId="src2", status="DRAFT"))
            with self.assertRaises(ValueError):
                tool.validate(dataset)

    def test_rights_and_privacy_are_export_gates(self):
        for field, value in (("allowedUses", ["LOCAL_REVIEW"]), ("privacyReviewed", False)):
            dataset = fixture()
            dataset["sources"][0][field] = value
            with self.assertRaises(ValueError):
                tool.export_retrieval(dataset, [])

    def test_two_independent_blind_reviewers_required(self):
        for field, value in (("reviewerId", "synthetic_a"), ("stage", "REACTION_INFORMED")):
            dataset = fixture()
            dataset["annotations"][1][field] = value
            with self.assertRaises(ValueError):
                tool.validate(dataset)

    def test_disagreement_needs_third_reviewer(self):
        dataset = fixture()
        dataset["annotations"][1].update(decision="UNCERTAIN", missingInformation=["음성 누락"])
        dataset["adjudications"][0]["adjudicatorId"] = "synthetic_a"
        with self.assertRaises(ValueError):
            tool.validate(dataset)
        dataset["adjudications"][0]["adjudicatorId"] = "synthetic_c"
        tool.validate(dataset)

    def test_blind_annotation_cannot_use_reactions(self):
        dataset = fixture()
        dataset["reactions"].append({"id": "r1", "caseId": "case1", "sourceId": "src1", "stance": "CRITICISM",
                                    "redactedText": "너무 과하다", "claim": "과한 비판", "contextRelation": "DIRECT"})
        dataset["annotations"][0]["reactionIds"] = ["r1"]
        with self.assertRaises(ValueError):
            tool.validate(dataset)

    def test_reaction_source_requires_own_rights(self):
        dataset = fixture()
        dataset["sources"].append(dict(dataset["sources"][0], id="r_source", allowedUses=["LOCAL_REVIEW"]))
        dataset["reactions"].append({"id": "r1", "caseId": "case1", "sourceId": "r_source", "stance": "CRITICISM",
                                    "redactedText": "너무 과하다", "claim": "과한 비판", "contextRelation": "DIRECT"})
        with self.assertRaises(ValueError):
            tool.export_retrieval(dataset, [])

    def test_corrected_stt_cannot_replace_raw_evidence(self):
        dataset = fixture()
        dataset["cases"][0]["segments"][0].update(verifiedText="맛이 좋다", verifiedBy="synthetic_a")
        dataset["adjudications"][0]["evidence"][0]["quote"] = "맛이 좋다"
        with self.assertRaises(ValueError):
            tool.validate(dataset)

    def test_omitted_target_retains_context_without_fake_quote(self):
        dataset = fixture()
        dataset["adjudications"][0].update(axis="TARGET_TREATMENT", target={"referent": "가게",
                                             "mentionMode": "OMITTED", "rawMention": None, "contextReason": "Synthetic contextual inference"})
        tool.validate(dataset)
        dataset["adjudications"][0]["target"]["rawMention"] = "가게"
        with self.assertRaises(ValueError):
            tool.validate(dataset)

    def test_uncertainty_is_not_annotator_disagreement(self):
        dataset = fixture()
        dataset["annotations"][0]["missingInformation"] = ["모름"]
        with self.assertRaises(ValueError):
            tool.validate(dataset)

    def test_ready_without_adjudication_rejected(self):
        dataset = fixture()
        dataset["adjudications"] = []
        with self.assertRaises(ValueError):
            tool.validate(dataset)


class BenchmarkTests(unittest.TestCase):
    def setUp(self):
        # Local datasets are Git-ignored. Test the contract with synthetic goals only.
        self.benchmark = {"version": "synthetic-benchmark", "status": "SYNTHETIC_TEST_ONLY",
                          "goals": [{"id": letter, "required": letter != "A", "needsScene": letter == "A",
                                     "windowMs": [i * 1000, (i + 1) * 1000]}
                                    for i, letter in enumerate("ABCD")]}
        self.report = {"data": {"videoId": "synthetic", "status": "COMPLETED", "events": [
            {"id": str(i), "startMs": g["windowMs"][0], "endMs": g["windowMs"][1], "text": "Synthetic original quote"}
            for i, g in enumerate(self.benchmark["goals"])]}}
        self.assessment = {"videoId": "synthetic", "benchmarkVersion": self.benchmark["version"],
                           "reviewerId": "synthetic_a", "matches": [], "normalControlsReviewed": True, "falsePositiveEventIds": []}
        for i, goal in enumerate(self.benchmark["goals"]):
            self.assessment["matches"].append({"goalId": goal["id"], "eventId": str(i), "status": "MATCH",
                "targetCorrect": True, "interpretationCorrect": True, "normalContrastChecked": True,
                "originalEvidenceChecked": True, "sceneChecked": True, "reason": "Synthetic checks only",
                "reportQuote": "Synthetic original quote"})

    def test_three_required_matches_not_general_accuracy(self):
        result = tool.assess_benchmark(self.benchmark, self.report, self.assessment)
        self.assertEqual(3, result["matches"])
        self.assertEqual(3, result["goals"])
        self.assertTrue(result["developmentGoalMet"])
        self.assertFalse(result["generalAccuracyMeasured"])

    def test_unassessed_auxiliary_a_does_not_block_bcd(self):
        self.assessment["matches"] = self.assessment["matches"][1:]
        result = tool.assess_benchmark(self.benchmark, self.report, self.assessment)
        self.assertTrue(result["developmentGoalMet"])
        self.assertEqual({"A": "UNASSESSED"}, result["auxiliaryOutcomes"])

    def test_count_or_time_overlap_is_insufficient(self):
        self.assessment["matches"][1]["interpretationCorrect"] = False
        with self.assertRaises(ValueError):
            tool.assess_benchmark(self.benchmark, self.report, self.assessment)

    def test_same_card_cannot_count_twice(self):
        self.assessment["matches"][1]["eventId"] = "0"
        with self.assertRaises(ValueError):
            tool.assess_benchmark(self.benchmark, self.report, self.assessment)

    def test_scene_evidence_required_for_a(self):
        self.assessment["matches"][0]["sceneChecked"] = False
        with self.assertRaises(ValueError):
            tool.assess_benchmark(self.benchmark, self.report, self.assessment)

    def test_missing_evaluation_is_not_zero_recall(self):
        self.assessment["matches"] = []
        result = tool.assess_benchmark(self.benchmark, self.report, self.assessment)
        self.assertEqual({"UNASSESSED"}, set(result["outcomes"].values()))
        self.assertFalse(result["developmentGoalMet"])

    def test_false_positives_and_unchecked_controls_block_success(self):
        self.assessment["normalControlsReviewed"] = False
        self.assertFalse(tool.assess_benchmark(self.benchmark, self.report, self.assessment)["developmentGoalMet"])
        self.assessment["normalControlsReviewed"] = True
        self.report["data"]["events"].append({"id": "extra"})
        self.assessment["falsePositiveEventIds"] = ["extra"]
        self.assertFalse(tool.assess_benchmark(self.benchmark, self.report, self.assessment)["developmentGoalMet"])

    def test_quote_and_video_identity_checks(self):
        self.assessment["matches"][0]["reportQuote"] = "Invented"
        with self.assertRaises(ValueError):
            tool.assess_benchmark(self.benchmark, self.report, self.assessment)
        self.assessment["videoId"] = "other"
        with self.assertRaises(ValueError):
            tool.assess_benchmark(self.benchmark, self.report, self.assessment)


if __name__ == "__main__":
    unittest.main()
