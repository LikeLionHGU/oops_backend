"""Synthetic run records only."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from j1_eval import bootstrap, error_breakdown, group_metrics, server_class


def runs(*states):
    return list(states)


class ServerClassTests(unittest.TestCase):
    def test_mapping(self):
        self.assertEqual([server_class(s) for s in ("REVIEW_REQUIRED", "PASS", "NO_CANDIDATE", "UNCERTAIN", "CALL_FAILED", None)],
                         ["RISK", "NO_RISK", "NO_RISK", "ABSTAIN", "NOT_EVALUATED", "NOT_EVALUATED"])


class GroupMetricsTests(unittest.TestCase):
    def test_rates_over_runs_and_case_accuracy(self):
        cases = [
            {"video": "1", "gold": "RISK", "runs": runs("REVIEW_REQUIRED", "PASS", "UNCERTAIN")},
            {"video": "1", "gold": "NO_RISK", "runs": runs("PASS", "PASS", "REVIEW_REQUIRED")},
            {"video": "2", "gold": "NO_RISK", "runs": runs("NO_CANDIDATE", "CALL_FAILED", "PASS")},
        ]
        m = group_metrics(cases)
        self.assertEqual(m["cases"], {"RISK": 1, "NO_RISK": 2})
        self.assertEqual(m["missRate"], {"value": 1 / 3, "count": "1/3"})
        self.assertEqual(m["falseAlarmRate"], {"value": 1 / 6, "count": "1/6"})
        self.assertEqual(m["abstainRate"], {"value": 1 / 9, "count": "1/9"})
        self.assertEqual(m["notEvaluatedRate"], {"value": 1 / 9, "count": "1/9"})
        self.assertEqual(m["coverage"], {"value": 7 / 9, "count": "7/9"})
        self.assertEqual(m["selectiveAccuracy"], {"value": 5 / 7, "count": "5/7"})
        self.assertAlmostEqual(m["caseWeightedAccuracy"], (1 / 3 + 2 / 3 + 2 / 3) / 3)
        self.assertAlmostEqual(m["clusterWeightedAccuracy"], ((1 / 3 + 2 / 3) / 2 + 2 / 3) / 2)

    def test_empty_denominators_are_na(self):
        m = group_metrics([{"video": "1", "gold": "NO_RISK", "runs": runs("PASS")}])
        self.assertEqual(m["missRate"], {"value": None, "count": "N/A (0 RISK runs)"})


class BootstrapTests(unittest.TestCase):
    def test_seeded_and_paired(self):
        a = [{"video": v, "gold": "NO_RISK", "runs": runs("PASS")} for v in ("1", "2", "3")]
        b = [{"video": v, "gold": "NO_RISK", "runs": runs("REVIEW_REQUIRED")} for v in ("1", "2", "3")]
        ci = bootstrap({"A": a, "B": b}, pairs=[("B", "A")], resamples=200, seed=7)
        self.assertEqual(ci["A"], [1.0, 1.0])
        self.assertEqual(ci["B-A"], [-1.0, -1.0])
        self.assertEqual(ci, bootstrap({"A": a, "B": b}, pairs=[("B", "A")], resamples=200, seed=7))


class ErrorBreakdownTests(unittest.TestCase):
    def test_majority_wrong_split_by_oracle(self):
        a0 = {("1", "s1"): {"gold": "RISK", "runs": runs("PASS", "PASS", "REVIEW_REQUIRED")},
              ("1", "s2"): {"gold": "NO_RISK", "runs": runs("REVIEW_REQUIRED", "REVIEW_REQUIRED", "PASS")},
              ("1", "s3"): {"gold": "NO_RISK", "runs": runs("PASS", "PASS", "PASS")}}
        a4 = {("1", "s1"): {"gold": "RISK", "runs": runs("REVIEW_REQUIRED", "REVIEW_REQUIRED", "PASS")},
              ("1", "s2"): {"gold": "NO_RISK", "runs": runs("REVIEW_REQUIRED", "PASS", "REVIEW_REQUIRED")},
              ("1", "s3"): {"gold": "NO_RISK", "runs": runs("PASS", "PASS", "PASS")}}
        out = error_breakdown(a0, a4)
        self.assertEqual(out["counts"], {"MISSING_CONTEXT": 1, "JUDGMENT_ERROR": 1})
        self.assertEqual([c["case"] for c in out["cases"]], ["1/s1", "1/s2"])


if __name__ == "__main__":
    unittest.main()
