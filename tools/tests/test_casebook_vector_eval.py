"""Synthetic run records only."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from casebook_vector_eval import false_alarm_rate, statement_flag_rate


def rec(video, target, repeat, state):
    return {"videoId": video, "targetId": target, "repeat": repeat, "targetState": state}


TARGETS = [{"videoId": 6001, "targetId": "stt-index-0", "statementId": "a#s1"},
           {"videoId": 6001, "targetId": "stt-index-1", "statementId": "a#s1"},
           {"videoId": 6002, "targetId": "stt-index-0", "statementId": "b#s1"}]
MECH = {"a#s1": ["group"], "b#s1": ["sexual"]}


class StatementFlagTests(unittest.TestCase):
    def test_any_flagged_target_flags_the_statement_for_that_repeat(self):
        records = [rec(6001, "stt-index-0", 0, "NO_CANDIDATE"), rec(6001, "stt-index-1", 0, "REVIEW_REQUIRED"),
                   rec(6001, "stt-index-0", 1, "PASS"), rec(6001, "stt-index-1", 1, "NO_CANDIDATE"),
                   rec(6002, "stt-index-0", 0, "PASS"), rec(6002, "stt-index-0", 1, "REVIEW_REQUIRED"),
                   rec(5001, "stt-index-9", 0, "REVIEW_REQUIRED")]
        out = statement_flag_rate(records, TARGETS, MECH)
        self.assertEqual(out["count"], "2/4")
        self.assertEqual(out["value"], 0.5)
        self.assertEqual(out["byMechanism"]["group"]["count"], "1/2")
        self.assertEqual(out["byMechanism"]["sexual"]["count"], "1/2")


class FalseAlarmTests(unittest.TestCase):
    def test_false_alarm_counts_only_random_layer_pass_targets(self):
        records = [rec(5001, "stt-index-1", 0, "REVIEW_REQUIRED"), rec(5001, "stt-index-1", 1, "NO_CANDIDATE"),
                   rec(5001, "stt-index-2", 0, "REVIEW_REQUIRED"), rec(6001, "stt-index-0", 0, "REVIEW_REQUIRED")]
        out = false_alarm_rate(records, {"5001/stt-index-1"})
        self.assertEqual(out, {"count": "1/2", "value": 0.5})

    def test_empty_denominator_is_na(self):
        self.assertEqual(false_alarm_rate([], {"5001/stt-index-1"}), {"count": "N/A (0 runs)", "value": None})


if __name__ == "__main__":
    unittest.main()
