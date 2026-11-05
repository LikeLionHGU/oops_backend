"""Synthetic segments and a fake encoder only; no model download or network calls."""
import hashlib
import json
import math
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from embed_segments import FORMAT, INPUT_VERSION, build_file, ordered_segments, transcript_sha256, write_file

META = {
    "modelId": "BAAI/bge-m3",
    "revision": "a" * 40,
    "modelArtifactsHash": "artifacts",
    "generationLibraryVersions": {"python": "3.13"},
}


def fake_encoder(texts):
    vectors = []
    for text in texts:
        raw = [float(len(text)), 1.0, float(text.count("발"))]
        norm = math.sqrt(sum(v * v for v in raw))
        vectors.append([v / norm for v in raw])
    return vectors


class EmbedSegmentsTests(unittest.TestCase):
    def segments(self):
        return [
            {"id": "stt-index-1", "startMs": 2000, "endMs": 3500, "text": "hello"},
            {"id": "stt-index-0", "startMs": 0, "endMs": 1500, "text": "안녕"},
        ]

    def test_digest_matches_the_java_selector(self):
        ordered = ordered_segments(self.segments())
        self.assertEqual([s["id"] for s in ordered], ["stt-index-0", "stt-index-1"])
        self.assertEqual(transcript_sha256(ordered), "a9a6035dc95ffece838e2b11be3d705199216fbcbb47f4121a8e8bad264278c8")

    def test_ordering_follows_start_end_then_id(self):
        segments = [
            {"id": "b", "startMs": 0, "endMs": 10, "text": "x"},
            {"id": "a", "startMs": 0, "endMs": 10, "text": "y"},
            {"id": "c", "startMs": 0, "endMs": 5, "text": "z"},
        ]
        self.assertEqual([s["id"] for s in ordered_segments(segments)], ["c", "a", "b"])

    def test_builds_the_reproducibility_contract_with_query_vectors(self):
        result = build_file(self.segments(), fake_encoder, META)
        self.assertEqual(result["format"], FORMAT)
        self.assertEqual(result["embeddingInputVersion"], INPUT_VERSION)
        self.assertEqual(result["vectorDtype"], "float32")
        self.assertTrue(result["normalized"])
        self.assertEqual(result["vectorDimension"], 3)
        self.assertEqual(set(result["documentVectors"]), {"stt-index-0", "stt-index-1"})
        self.assertEqual(set(result["queryVectors"]), {"stt-index-0", "stt-index-1"})
        for vector in result["documentVectors"].values():
            self.assertAlmostEqual(math.sqrt(sum(v * v for v in vector)), 1.0, places=5)

    def test_query_text_is_the_target_raw_text_only(self):
        seen = []
        build_file(self.segments(), lambda texts: seen.append(list(texts)) or fake_encoder(texts), META)
        self.assertEqual(seen, [["안녕", "hello"], ["안녕", "hello"]])

    def test_rejects_bad_input(self):
        duplicate = self.segments() + [{"id": "stt-index-0", "startMs": 5, "endMs": 6, "text": "x"}]
        with self.assertRaises(ValueError):
            build_file(duplicate, fake_encoder, META)
        with self.assertRaises(ValueError):
            build_file([{"id": "x", "startMs": 0, "endMs": 1, "text": "a\0b"}], fake_encoder, META)
        with self.assertRaises(ValueError):
            build_file(self.segments(), lambda texts: [[2.0, 0.0, 0.0] for _ in texts], META)
        with self.assertRaises(ValueError):
            build_file(self.segments(), fake_encoder, {**META, "revision": "main"})

    def test_write_refuses_overwrite_and_returns_the_file_digest(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "embeddings.json"
            digest = write_file(build_file(self.segments(), fake_encoder, META), path)
            self.assertEqual(digest, hashlib.sha256(path.read_bytes()).hexdigest())
            self.assertEqual(json.loads(path.read_text(encoding="utf-8"))["format"], FORMAT)
            with self.assertRaises(FileExistsError):
                write_file({}, path)


if __name__ == "__main__":
    unittest.main()
