"""Synthetic schema fixtures, not human-approved labels."""
import copy
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location("fixed_review_suite", Path(__file__).resolve().parents[1] / "fixed_review_suite.py")
tool = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(tool)


def fixture():
    families = {
        "pisik-yeongyang": ["pisik-B", "pisik-C", "pisik-D"],
        "sgbg-military-ad-20240623": ["service", "timing"],
        "workman-caption-20200311": ["caption"],
        "chim-football-20240715": ["fan"],
        "jang-gender-201504": ["gender"],
        "kwak-naeun-20240916": ["third-party"],
    }
    cards = {"incidents": [{"familyId": family, "cards": [
        {"id": cid, "knownPoint": {"text": "fixture only"},
         "evidence": {"reportedQuotes": [] if cid == "timing" else [{"text": "fixture " + cid}],
                      "sources": []}} for cid in ids]} for family, ids in families.items()]}
    run = {"transcript": [{"startMs": t, "endMs": t + 1000, "text": "fixture " + str(t)}
                          for t in (14500, 55000, 72000, 79000)]}
    return tool.build(cards, run)


class FixedSuiteTests(unittest.TestCase):
    def test_counts_and_blocked_status(self):
        result = tool.validate(fixture())
        self.assertEqual(30, result["slots"])
        self.assertEqual(0, result["approvedLabels"])
        self.assertIsNone(result["qualityGateMet"])
        self.assertEqual("BLOCKED", result["adoption"])

    def test_no_model_output_or_research_note_in_input(self):
        for row in fixture()["cases"]:
            self.assertEqual({"mode", "segments"}, set(row["input"]))
            self.assertIsNone(row["approvedDecision"])

    def test_source_missing_not_fabricated(self):
        rows = fixture()["cases"]
        self.assertEqual([], next(r for r in rows if r["id"] == "timing")["input"]["segments"])
        self.assertEqual([], rows[-1]["input"]["segments"])

    def test_source_transcript_not_mutated(self):
        suite = fixture()
        row = suite["cases"][0]
        self.assertEqual("fixture 14500", row["input"]["segments"][0]["text"])
        for r in suite["cases"]:
            if r["input"]["mode"] != "SAVED_STT_WINDOW":
                for s in r["input"]["segments"]:
                    self.assertIsNone(s["startMs"])

    def test_frozen_input_and_registry_tampering(self):
        suite = fixture()
        suite["cases"][0]["input"]["segments"][0]["text"] = "tampered"
        with self.assertRaisesRegex(ValueError, "frozen input"):
            tool.validate(suite)
        suite = fixture()
        suite["cases"][0]["researchNote"] = "changed"
        with self.assertRaisesRegex(ValueError, "registry"):
            tool.validate(suite)

    def test_draft_cannot_be_approved(self):
        suite = fixture()
        suite["cases"][0].update(humanReview="APPROVED", approvedDecision="REVIEW_REQUIRED")
        with self.assertRaisesRegex(ValueError, "cannot approve"):
            tool.validate(suite)

    def test_answer_leakage_rejected(self):
        suite = fixture()
        row = suite["cases"][0]
        row["input"]["knownPoint"] = "answer"
        row["inputHash"] = tool.digest(row["input"])
        with self.assertRaisesRegex(ValueError, "invalid input fields"):
            tool.validate(suite)

    def test_baseline_is_full_commit_pinned(self):
        suite = fixture()
        suite["baselineCommit"] = "different"
        with self.assertRaisesRegex(ValueError, "baseline commit"):
            tool.validate(suite)

    def test_family_leakage_preserving_split_counts(self):
        suite = fixture()
        a = suite["cases"][0]
        b = next(r for r in suite["cases"] if r["split"] == "CONFIRMATION_CANDIDATE")
        a["split"], b["split"] = b["split"], a["split"]
        with self.assertRaisesRegex(ValueError, "leakage"):
            tool.validate(suite)

    def test_duplicate_text_across_splits(self):
        suite = fixture()
        a = suite["cases"][0]
        b = next(r for r in suite["cases"] if r["split"] == "CONFIRMATION_CANDIDATE")
        b["input"] = copy.deepcopy(a["input"])
        b["inputHash"] = tool.digest(b["input"])
        with self.assertRaisesRegex(ValueError, "duplicate input"):
            tool.validate(suite)

    def test_no_fake_reported_timestamp(self):
        suite = fixture()
        row = next(r for r in suite["cases"] if r["input"]["mode"] == "REPORTED_QUOTE")
        row["input"]["segments"][0].update(startMs=0, endMs=1000)
        row["inputHash"] = tool.digest(row["input"])
        with self.assertRaisesRegex(ValueError, "invent"):
            tool.validate(suite)


if __name__ == "__main__":
    unittest.main()
