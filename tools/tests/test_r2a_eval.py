"""Synthetic condition and label records only."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from r2a_eval import summarize_group

COND = ("S0_A0", "S1_PAGE_OVERLAP", "S2_NEIGHBOR_GUARANTEE", "S3_BOUNDARY_EXPANSION")


def case(gold, s0, extra, stratum="EDGE", trimmed=(), status="OK"):
    conditions = {}
    for c in COND:
        theoretical = s0 + (extra if c == "S2_NEIGHBOR_GUARANTEE" else [])
        within = [x for x in theoretical if x not in trimmed] if c == "S2_NEIGHBOR_GUARANTEE" else theoretical
        conditions[c] = {"theoretical": theoretical, "withinLimit": within,
                         "trimmed": list(trimmed) if c == "S2_NEIGHBOR_GUARANTEE" else [],
                         "addedSegments": len(extra) if c == "S2_NEIGHBOR_GUARANTEE" else 0,
                         "addedCodePoints": 10 * len(extra) if c == "S2_NEIGHBOR_GUARANTEE" else 0,
                         "status": status if c == "S2_NEIGHBOR_GUARANTEE" else "OK"}
    return {"stratum": stratum, "gold": gold, "conditions": conditions}


class SummarizeTests(unittest.TestCase):
    def test_no_context_cases_are_excluded_and_no_a0_miss_gives_na(self):
        rows = [case([], ["a"], ["b"]), case(["a"], ["a"], ["b"])]
        s = summarize_group(rows)
        self.assertEqual(s["contextNecessary"], 1)
        self.assertEqual(s["S0_A0"]["allContextCoverageWithinLimit"], "1/1")
        self.assertEqual(s["S2_NEIGHBOR_GUARANTEE"]["recoveredFromA0Miss"], "N/A (0 A0-missed)")

    def test_recovery_counts_theoretical_and_within_limit_separately(self):
        rows = [case(["b"], ["a"], ["b"]), case(["c"], ["a"], ["c"], trimmed=("c",), status="TRIMMED")]
        s = summarize_group(rows)
        s2 = s["S2_NEIGHBOR_GUARANTEE"]
        self.assertEqual(s2["allContextCoverageTheoretical"], "2/2")
        self.assertEqual(s2["allContextCoverageWithinLimit"], "1/2")
        self.assertEqual(s2["recoveredFromA0Miss"], "1/2")
        self.assertEqual(s2["trimmedCases"], 1)
        self.assertEqual(s["S0_A0"]["recoveredFromA0Miss"], "0/2")
        self.assertEqual(s2["meanGoldFractionWithinLimit"], 0.5)

    def test_cost_is_reported_over_all_cases_even_without_gold(self):
        rows = [case([], ["a"], ["b", "c"]), case([], ["a"], [])]
        s = summarize_group(rows)
        self.assertEqual(s["S2_NEIGHBOR_GUARANTEE"]["addedSegments"], {"mean": 1.0, "max": 2})
        self.assertEqual(s["S2_NEIGHBOR_GUARANTEE"]["addedCodePoints"], {"mean": 10.0, "max": 20})
        self.assertIsNone(s["S2_NEIGHBOR_GUARANTEE"]["meanGoldFractionWithinLimit"])


if __name__ == "__main__":
    unittest.main()
