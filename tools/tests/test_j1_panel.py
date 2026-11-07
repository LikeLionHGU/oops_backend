"""Synthetic panel labels only; no network calls."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from j1_panel import compare_cases, final_labels


def case(target, decision, context=(), evidence=(), valid=True):
    return {"targetSegmentId": target, "t2Decision": decision, "exportStatus": "VALID" if valid else "INVALID",
            "goldContextSegmentIds": list(context), "evidenceSegmentIds": list(evidence)}


class CompareTests(unittest.TestCase):
    def test_agreement_is_silver_with_union_context_and_disagreement_is_flagged(self):
        a = [case("s1", "PASS"), case("s2", "REVIEW_REQUIRED", ["s0"], ["s2"]), case("s3", "PASS"), case("s4", "UNCERTAIN")]
        b = [case("s1", "PASS"), case("s2", "REVIEW_REQUIRED", ["s1"], ["s2"]), case("s3", "REVIEW_REQUIRED"),
             case("s4", "UNCERTAIN")]
        order = {f"s{i}": i for i in range(5)}
        rows = {r["target"]: r for r in compare_cases(a, b, order)}
        self.assertEqual(rows["s1"]["status"], "SILVER")
        self.assertEqual(rows["s2"]["silver"], {"decision": "REVIEW_REQUIRED", "contextSegmentIds": ["s0", "s1"],
                                                "evidenceSegmentIds": ["s2"]})
        self.assertEqual((rows["s3"]["status"], rows["s3"]["a"], rows["s3"]["b"]), ("DISAGREEMENT", "PASS", "REVIEW_REQUIRED"))
        self.assertEqual(rows["s4"]["silver"]["decision"], "UNCERTAIN")

    def test_invalid_label_or_missing_case_is_a_disagreement(self):
        rows = compare_cases([case("s1", None, valid=False), case("s2", "PASS")], [case("s1", "PASS")], {"s1": 1, "s2": 2})
        self.assertEqual([r["status"] for r in rows], ["DISAGREEMENT", "DISAGREEMENT"])
        self.assertIsNone(rows[1]["b"])


class FinalTests(unittest.TestCase):
    def setUp(self):
        a = [case("s1", "PASS"), case("s3", "PASS", ["s2"]), case("s5", "PASS")]
        b = [case("s1", "PASS"), case("s3", "REVIEW_REQUIRED", ["s4"], ["s3"]), case("s5", "REVIEW_REQUIRED")]
        self.rows = compare_cases(a, b, {f"s{i}": i for i in range(6)})

    def test_human_choice_takes_that_member_label(self):
        final = {r["target"]: r for r in final_labels(self.rows, {"s3": "B", "s5": "NEITHER"})}
        self.assertEqual(final["s1"], {"target": "s1", "decision": "PASS", "source": "SILVER", "contextSegmentIds": [],
                                       "evidenceSegmentIds": []})
        self.assertEqual(final["s3"], {"target": "s3", "decision": "REVIEW_REQUIRED", "source": "HUMAN_CHOSE_B",
                                       "contextSegmentIds": ["s4"], "evidenceSegmentIds": ["s3"]})
        self.assertEqual(final["s5"]["decision"], "UNCERTAIN")
        self.assertEqual(final["s5"]["source"], "HUMAN_NEITHER")

    def test_unchecked_disagreement_is_panel_disagreement(self):
        final = {r["target"]: r for r in final_labels(self.rows, None)}
        self.assertEqual((final["s3"]["decision"], final["s3"]["source"]), (None, "PANEL_DISAGREEMENT"))

    def test_choices_must_cover_every_disagreement_with_known_values(self):
        with self.assertRaises(ValueError):
            final_labels(self.rows, {"s3": "B"})
        with self.assertRaises(ValueError):
            final_labels(self.rows, {"s3": "B", "s5": "C"})


if __name__ == "__main__":
    unittest.main()
