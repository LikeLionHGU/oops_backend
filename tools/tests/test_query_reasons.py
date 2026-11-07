"""Synthetic plans and a fake model only."""
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from query_reasons import build_input, check_query, write_reasons

SEGMENTS = {f"stt-index-{i}": {"id": f"stt-index-{i}", "text": t} for i, t in enumerate(["가 말", "나 말", "다 말", "라 말"])}
GOOD = "특정 지역 주민을 먹을거리도 없는 사람들로 일반화해 조롱한다면 지역 비하로 비판받을 수 있다."


class FakeModel:
    def __init__(self, *texts):
        self.texts, self.calls = list(texts), 0

    def __call__(self, system, user):
        self.calls += 1
        return self.texts[min(self.calls, len(self.texts)) - 1], {"usage": {"prompt_tokens": 3, "completion_tokens": 2}}


def plan_targets(*keys):
    return [{"videoId": v, "targetId": t, "contextIds": c} for v, t, c in keys]


class BuildInputTests(unittest.TestCase):
    def test_build_input_marks_target_and_keeps_transcript_order(self):
        text = build_input(SEGMENTS["stt-index-1"], SEGMENTS, ["stt-index-3", "stt-index-0"])
        self.assertEqual(text.splitlines(), ["가 말", "[대상] 나 말", "라 말"])


class CheckQueryTests(unittest.TestCase):
    def test_statuses(self):
        self.assertEqual(check_query({"reason": GOOD}), "OK")
        self.assertEqual(check_query({"reason": None}), "NULL")
        self.assertEqual(check_query({"reason": "가" * 301}), "INVALID")
        self.assertEqual(check_query({"reason": 3}), "INVALID")
        self.assertEqual(check_query({}), "INVALID")


class WriteReasonsTests(unittest.TestCase):
    def test_invalid_json_or_overlong_reason_is_recorded_invalid_not_retried(self):
        model = FakeModel("not json", json.dumps({"reason": "가" * 301}))
        targets = plan_targets((1, "stt-index-0", []), (1, "stt-index-1", ["stt-index-0"]))
        with tempfile.TemporaryDirectory() as tmp:
            out = write_reasons(targets, {1: SEGMENTS}, model, "지시", Path(tmp) / "q", max_calls=5)
            self.assertEqual(model.calls, 2)
            self.assertEqual(out["1/stt-index-0"], {"reason": None, "status": "INVALID"})
            self.assertEqual(out["1/stt-index-1"]["status"], "INVALID")

    def test_null_reason_is_status_null_and_duplicates_run_once(self):
        model = FakeModel(json.dumps({"reason": None}))
        targets = plan_targets((1, "stt-index-0", []), (1, "stt-index-0", []))
        with tempfile.TemporaryDirectory() as tmp:
            out = write_reasons(targets, {1: SEGMENTS}, model, "지시", Path(tmp) / "q", max_calls=5)
            self.assertEqual(out, {"1/stt-index-0": {"reason": None, "status": "NULL"}})
            self.assertEqual(model.calls, 1)
            self.assertTrue((Path(tmp) / "q" / "run-record.json").exists())

    def test_max_calls_cap(self):
        model = FakeModel(json.dumps({"reason": GOOD}))
        targets = plan_targets((1, "stt-index-0", []), (1, "stt-index-1", []))
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(RuntimeError):
                write_reasons(targets, {1: SEGMENTS}, model, "지시", Path(tmp) / "q", max_calls=1)
            self.assertEqual(model.calls, 0)


if __name__ == "__main__":
    unittest.main()
