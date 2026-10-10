import unittest
from evaluate_analysis_run import evaluate, compare, assessment_template


def fixture():
    benchmark = {"version": "synthetic-1", "familyId": "synthetic", "status": "DRAFT",
                 "goals": [{"id": "B", "windowMs": [0, 5000], "needsScene": False}]}
    event = {"id": "1", "startMs": 0, "endMs": 1000, "text": "가상 원문", "type": "SPEECH"}
    run = {"schemaVersion": "analysis-run-snapshot-1", "report": {"videoId": "1", "status": "COMPLETED", "events": [event]},
           "status": {"videoId": "1", "status": "COMPLETED", "startedAt": "2026-01-01T00:00:00Z", "completedAt": "2026-01-01T00:00:02Z"},
           "diagnostics": {"snapshot": None}}
    assessment = {"videoId": "1", "benchmarkVersion": "synthetic-1", "reviewerId": "synthetic-reviewer",
                  "matches": [{"goalId": "B", "eventId": "1", "status": "MATCH", "reason": "가상 검수",
                               "reportQuote": "가상 원문", "targetCorrect": True, "interpretationCorrect": True,
                               "normalContrastChecked": True, "originalEvidenceChecked": True}],
                  "falsePositiveEventIds": [], "normalControlsReviewed": True}
    return benchmark, run, assessment


class EvaluationTests(unittest.TestCase):
    def test_template_never_preapproves_observed_cards(self):
        b, r, _ = fixture()
        template = assessment_template(b, r)
        self.assertTrue(all(m["status"] == "UNASSESSED" and m["eventId"] is None for m in template["matches"]))
        self.assertFalse(template["qualityReview"]["allEventsReviewed"])
        with self.assertRaises(ValueError):
            evaluate(b, r, template)
    def test_without_human_review_quality_and_cost_remain_unknown(self):
        b, r, _ = fixture()
        out = evaluate(b, r)
        for key in ("goalEvaluation", "qualityGateMet", "semanticDuplicateExtraCards", "unsupportedInterpretationCards", "costUsd"):
            self.assertIsNone(out[key])
        self.assertEqual(2, out["elapsedSeconds"])
        self.assertFalse(out["generalAccuracyMeasured"])

    def test_same_quote_hint_is_not_semantic_duplicate_label(self):
        b, r, _ = fixture()
        r["report"]["events"].append(dict(r["report"]["events"][0], id="2"))
        out = evaluate(b, r)
        self.assertEqual([["1", "2"]], out["mechanicalDuplicateGroups"])
        self.assertIsNone(out["semanticDuplicateExtraCards"])

    def test_complete_quality_review_required_for_quality_gate(self):
        b, r, a = fixture()
        self.assertIsNone(evaluate(b, r, a)["qualityGateMet"])
        a["qualityReview"] = {"allEventsReviewed": True, "duplicateGroups": [], "unsupportedInterpretationEventIds": []}
        self.assertTrue(evaluate(b, r, a)["qualityGateMet"])
        a["qualityReview"]["unsupportedInterpretationEventIds"] = ["1"]
        self.assertFalse(evaluate(b, r, a)["qualityGateMet"])

    def test_unknown_and_reused_duplicate_ids_are_rejected(self):
        b, r, a = fixture()
        for groups in ([["1", "unknown"]], [["1", "1"]]):
            a["qualityReview"] = {"allEventsReviewed": True, "duplicateGroups": groups, "unsupportedInterpretationEventIds": []}
            with self.assertRaises(ValueError):
                evaluate(b, r, a)

    def test_goal_review_cannot_bypass_existing_grounding_checks(self):
        b, r, a = fixture()
        a["matches"][0]["originalEvidenceChecked"] = False
        with self.assertRaises(ValueError):
            evaluate(b, r, a)

    def test_repeat_card_counts_are_not_recall_or_consistency(self):
        b, r, _ = fixture()
        first = evaluate(b, r)
        r["report"]["videoId"] = r["status"]["videoId"] = "2"
        second = evaluate(b, r)
        out = compare([first, second])
        self.assertIsNone(out["goalOutcomeConsistent"])
        self.assertFalse(out["generalAccuracyMeasured"])
        with self.assertRaises(ValueError):
            compare([first, first])

    def test_mixed_protocols_and_ids_are_rejected(self):
        b, r, _ = fixture()
        first = evaluate(b, r)
        second = dict(first, videoId="2", benchmarkVersion="different")
        with self.assertRaises(ValueError):
            compare([first, second])
        r["status"]["videoId"] = "2"
        with self.assertRaises(ValueError):
            evaluate(b, r)


if __name__ == "__main__":
    unittest.main()
