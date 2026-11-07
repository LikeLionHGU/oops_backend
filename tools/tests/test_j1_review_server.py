"""Synthetic review folders only; the HTTP test binds 127.0.0.1 on a free port."""
import json
import sys
import tempfile
import threading
import unittest
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from j1_review_server import ReviewStore, make_server

SEGMENTS = [{"id": f"stt-index-{i}", "startMs": i * 60_000, "endMs": i * 60_000 + 5_000, "text": f"문장 {i}"} for i in range(8)]


def build(base: Path, video_id: int = 5001) -> Path:
    (base / "vid").mkdir()
    (base / "vid" / "transcript.json").write_text(json.dumps({"segments": SEGMENTS}, ensure_ascii=False), encoding="utf-8")
    draw = base / f"draw-{video_id}"
    draw.mkdir()
    (draw / "manifest.json").write_text(json.dumps({"labelVersion": "j1", "videos": [
        {"videoId": video_id, "transcriptPath": "../vid/transcript.json", "targets": ["stt-index-1", "stt-index-5"]}]}))
    (draw / "draw-record.json").write_text(json.dumps({"layers": {"stt-index-1": "RISK_NOMINATED"}}))
    review = base / f"review-{video_id}"
    review.mkdir()
    (review / "review-selection.json").write_text(json.dumps({"cases": [{"targetSegmentId": "stt-index-1", "reason": "AI_FLAGGED"}]}))
    template = {"labelVersion": "j1", "role": "HUMAN_BLIND_REVIEW", "reviewerId": None, "cases": [
        {"videoId": video_id, "targetSegmentId": t, "humanDecision": None, "contextSegmentIds": [], "evidenceSegmentIds": [],
         "note": None} for t in ("stt-index-1", "stt-index-5")]}
    (review / "human-labels-template.json").write_text(json.dumps(template, ensure_ascii=False))
    return base


class StoreTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.base = build(Path(self.tmp.name))
        self.store = ReviewStore(self.base, 5001, window_ms=60_000)

    def tearDown(self):
        self.tmp.cleanup()

    def test_state_has_windows_and_never_layers_or_reasons(self):
        state = self.store.state()
        self.assertEqual([c["targetSegmentId"] for c in state["cases"]], ["stt-index-1", "stt-index-5"])
        self.assertEqual([s["id"] for s in state["cases"][0]["window"]], ["stt-index-0", "stt-index-1", "stt-index-2"])
        text = json.dumps(state)
        for word in ("RISK_NOMINATED", "AI_FLAGGED", "layers", "reason"):
            self.assertNotIn(word, text)
        self.assertFalse(state["finalized"])

    def test_save_writes_a_draft_that_state_reloads(self):
        state = self.store.state()
        state["reviewerId"] = "me"
        state["cases"][0].update(humanDecision="REVIEW_REQUIRED", contextSegmentIds=["stt-index-0"],
                                 evidenceSegmentIds=["stt-index-1"], note="메모")
        self.store.save(state)
        again = ReviewStore(self.base, 5001, window_ms=60_000).state()
        self.assertEqual(again["reviewerId"], "me")
        self.assertEqual(again["cases"][0]["humanDecision"], "REVIEW_REQUIRED")
        self.assertEqual(again["cases"][0]["contextSegmentIds"], ["stt-index-0"])
        self.assertFalse((self.base / "review-5001" / "human-labels.json").exists())

    def test_save_rejects_bad_decision_unknown_id_and_target_as_context(self):
        for change in ({"humanDecision": "MAYBE"}, {"contextSegmentIds": ["stt-index-99"]}, {"contextSegmentIds": ["stt-index-1"]}):
            state = self.store.state()
            state["cases"][0].update(change)
            with self.assertRaises(ValueError):
                self.store.save(state)

    def test_finalize_needs_every_decision_and_a_reviewer_then_writes_once(self):
        state = self.store.state()
        state["cases"][0]["humanDecision"] = "PASS"
        state["reviewerId"] = "me"
        with self.assertRaises(ValueError):
            self.store.finalize(state)
        state["cases"][1]["humanDecision"] = "UNCERTAIN"
        state["cases"][1]["contextSegmentIds"] = ["stt-index-6", "stt-index-4"]
        self.store.finalize(state)
        final = self.base / "review-5001" / "human-labels.json"
        doc = json.loads(final.read_text(encoding="utf-8"))
        self.assertEqual(doc["reviewerId"], "me")
        self.assertEqual(doc["cases"][1], {"videoId": 5001, "targetSegmentId": "stt-index-5", "humanDecision": "UNCERTAIN",
                                           "contextSegmentIds": ["stt-index-4", "stt-index-6"], "evidenceSegmentIds": [],
                                           "note": None})
        self.assertFalse(final.stat().st_mode & 0o222)
        self.assertTrue(self.store.state()["finalized"])
        with self.assertRaises(ValueError):
            self.store.save(state)


class HttpTests(unittest.TestCase):
    def test_serves_page_state_and_save(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = build(Path(tmp))
            server = make_server(base, [5001], port=0)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                url = f"http://127.0.0.1:{server.server_address[1]}"
                self.assertIn("<html", urllib.request.urlopen(url + "/").read().decode("utf-8"))
                state = json.loads(urllib.request.urlopen(url + "/api/state?video=5001").read())
                state["cases"][0]["humanDecision"] = "PASS"
                request = urllib.request.Request(url + "/api/save?video=5001", data=json.dumps(state).encode(),
                                                 headers={"Content-Type": "application/json"}, method="POST")
                self.assertEqual(json.loads(urllib.request.urlopen(request).read())["ok"], True)
                videos = json.loads(urllib.request.urlopen(url + "/api/videos").read())
                self.assertEqual(videos, [{"videoId": 5001, "decided": 1, "total": 2, "finalized": False}])
            finally:
                server.shutdown()
                server.server_close()


if __name__ == "__main__":
    unittest.main()
