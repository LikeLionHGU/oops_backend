"""Synthetic transcripts only; no network calls."""
import hashlib
import json
import random
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from j1_targets import draw_random, eligible, main, review_sample, review_sheet

SEGMENTS = [{"id": f"stt-index-{i}", "startMs": i * 10_000, "endMs": i * 10_000 + 9_000, "text": f"문장 {i}"}
            for i in range(50)]
SEGMENTS[3]["text"] = "…"
SHA = "a" * 64


class DrawTests(unittest.TestCase):
    def test_eligible_needs_two_letters_or_digits(self):
        self.assertEqual([s["id"] for s in eligible(SEGMENTS)].count("stt-index-3"), 0)
        self.assertTrue(eligible([{"id": "x", "text": "a1"}]))
        self.assertFalse(eligible([{"id": "x", "text": "a!"}]))

    def test_one_pick_per_block_from_the_seeded_generator(self):
        pool = eligible(SEGMENTS)
        rng = random.Random(int(hashlib.sha256(f"{SHA}:j1-random-v1".encode()).hexdigest()[:16], 16))
        expected = []
        for i in range(20):
            block = pool[i * len(pool) // 20:(i + 1) * len(pool) // 20]
            expected.append(block[rng.randrange(len(block))]["id"])
        record = draw_random(SEGMENTS, SHA, nominated=[])
        self.assertEqual(record["drawn"], expected)
        self.assertEqual(record["targets"], expected)
        self.assertEqual((record["eligible"], record["excluded"]), (49, 1))

    def test_overlap_with_nominated_is_dropped_not_refilled(self):
        first = draw_random(SEGMENTS, SHA, nominated=[])["drawn"]
        record = draw_random(SEGMENTS, SHA, nominated=[first[0], "stt-index-49"])
        self.assertEqual(record["drawn"], first)
        self.assertEqual(record["overlapWithNominated"], [first[0]])
        self.assertEqual(record["targets"], first[1:])


def case(target, decision):
    return {"targetSegmentId": target, "t2Decision": decision, "exportStatus": "VALID"}


class ReviewSampleTests(unittest.TestCase):
    def test_covers_flags_nominated_and_a_seeded_pass_sample(self):
        layers = {"stt-index-1": "RISK_NOMINATED", "stt-index-2": "RANDOM", "stt-index-4": "RANDOM"}
        layers.update({f"stt-index-{i}": "RANDOM" for i in range(10, 30)})
        cases = [case("stt-index-1", "PASS"), case("stt-index-2", "UNCERTAIN"), case("stt-index-4", "REVIEW_REQUIRED")]
        cases += [case(f"stt-index-{i}", "PASS") for i in range(10, 30)]
        order = {s["id"]: n for n, s in enumerate(SEGMENTS)}
        sample = review_sample(cases, layers, order, SHA, pass_sample=10)
        reasons = {r["targetSegmentId"]: r["reason"] for r in sample["cases"]}
        self.assertEqual(reasons["stt-index-1"], "RISK_NOMINATED")
        self.assertEqual(reasons["stt-index-2"], "AI_FLAGGED")
        self.assertEqual(reasons["stt-index-4"], "AI_FLAGGED")
        passes = [t for t, r in reasons.items() if r == "RANDOM_PASS_SAMPLE"]
        pool = [f"stt-index-{i}" for i in range(10, 30)]
        rng = random.Random(int(hashlib.sha256(f"{SHA}:j1-review-sample-v1".encode()).hexdigest()[:16], 16))
        self.assertEqual(sorted(passes, key=order.get), sorted(rng.sample(pool, 10), key=order.get))
        self.assertEqual(sorted(sample["aiOnlyPass"], key=order.get), sorted(set(pool) - set(passes), key=order.get))
        self.assertEqual([r["targetSegmentId"] for r in sample["cases"]],
                         sorted(reasons, key=order.get))

    def test_invalid_labels_are_listed_for_review(self):
        cases = [{"targetSegmentId": "stt-index-2", "t2Decision": None, "exportStatus": "INVALID"}]
        sample = review_sample(cases, {"stt-index-2": "RANDOM"}, {"stt-index-2": 0}, SHA, pass_sample=10)
        self.assertEqual(sample["cases"][0]["reason"], "AI_INVALID")


class ReviewSheetTests(unittest.TestCase):
    def test_sheet_shows_window_without_ai_decision_or_layer(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "review"
            review_sheet(SEGMENTS, 5001, ["stt-index-20"], out, window_ms=25_000)
            sheet = (out / "review-sheet.md").read_text(encoding="utf-8")
            template = json.loads((out / "human-labels-template.json").read_text(encoding="utf-8"))
        self.assertIn("**stt-index-20**", sheet)
        self.assertIn("stt-index-18", sheet)
        self.assertNotIn("stt-index-17 ", sheet)
        for word in ("RISK_NOMINATED", "RANDOM", "t2Decision", "PASS_SAMPLE"):
            self.assertNotIn(word, sheet)
        self.assertEqual(template["cases"], [{"videoId": 5001, "targetSegmentId": "stt-index-20", "humanDecision": None,
                                              "contextSegmentIds": [], "evidenceSegmentIds": [], "note": None}])


class CliTests(unittest.TestCase):
    def test_draw_then_review(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            (base / "vid").mkdir()
            transcript = base / "vid" / "transcript.json"
            transcript.write_text(json.dumps({"segments": SEGMENTS}, ensure_ascii=False), encoding="utf-8")
            nominations = base / "nom.json"
            nominations.write_text(json.dumps({"videos": [{"targets": ["stt-index-49"]}]}), encoding="utf-8")
            main(["draw", str(transcript), str(nominations), str(base / "draw"), "--video-id", "5001",
                  "--cluster-id", "youtube-vid"])
            manifest = json.loads((base / "draw" / "manifest.json").read_text())
            targets = manifest["videos"][0]["targets"]
            self.assertIn("stt-index-49", targets)
            self.assertEqual(manifest["videos"][0]["transcriptPath"], "../vid/transcript.json")
            (base / "labels").mkdir()
            cases = [case(t, "PASS") for t in targets]
            (base / "labels" / "diagnostic-t2-initial.json").write_text(json.dumps({"cases": cases}))
            main(["review", str(base / "draw"), str(base / "labels"), str(base / "review")])
            selection = json.loads((base / "review" / "review-selection.json").read_text())
            self.assertEqual(sum(c["reason"] == "RANDOM_PASS_SAMPLE" for c in selection["cases"]), 10)
            self.assertIn({"targetSegmentId": "stt-index-49", "reason": "RISK_NOMINATED"}, selection["cases"])


if __name__ == "__main__":
    unittest.main()
