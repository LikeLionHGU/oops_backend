"""Synthetic segments, hand-made vectors and a fake scorer only; no model download or network calls."""
import hashlib
import json
import math
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from embed_segments import FORMAT as EMBEDDING_FORMAT, transcript_sha256, ordered_segments
from rerank_segments import ALLOW_PATTERNS, FORMAT, INPUT_VERSION, a2_top_k, build_score_file

R = math.sqrt(0.5)
SEGMENTS = [
    {"id": "stt-index-0", "startMs": 0, "endMs": 1500, "text": "근처 잡담"},
    {"id": "stt-index-1", "startMs": 2000, "endMs": 3500, "text": "대상 발언"},
    {"id": "stt-index-2", "startMs": 4000, "endMs": 5500, "text": "다른 근처 잡담"},
    {"id": "stt-index-3", "startMs": 400000, "endMs": 401500, "text": "멀리 있는 관련 발언"},
    {"id": "stt-index-4", "startMs": 500000, "endMs": 501500, "text": "조금 관련된 발언"},
]
META = {
    "modelId": "BAAI/bge-reranker-v2-m3",
    "revision": "b" * 40,
    "modelArtifactsHash": "artifacts",
    "generationLibraryVersions": {"python": "3.13"},
}


def embeddings(queries=None):
    return {
        "format": EMBEDDING_FORMAT,
        "transcriptSha256": transcript_sha256(ordered_segments(SEGMENTS)),
        "documentVectors": {
            "stt-index-0": [0.0, 1.0, 0.0], "stt-index-1": [1.0, 0.0, 0.0], "stt-index-2": [0.0, 0.0, 1.0],
            "stt-index-3": [1.0, 0.0, 0.0], "stt-index-4": [R, R, 0.0],
        },
        "queryVectors": queries or {"stt-index-1": [1.0, 0.0, 0.0]},
    }


def fake_scorer(pairs):
    return [float(len(passage)) for _, passage in pairs]


class RerankSegmentsTests(unittest.TestCase):
    def test_top_k_matches_the_java_a2_ranking(self):
        ordered = ordered_segments(SEGMENTS)
        self.assertEqual(a2_top_k(ordered, embeddings(), "stt-index-1", 3), ["stt-index-3", "stt-index-4", "stt-index-0"])
        self.assertEqual(a2_top_k(ordered, embeddings(), "stt-index-1", 10),
                         ["stt-index-3", "stt-index-4", "stt-index-0", "stt-index-2"])

    def test_scores_exactly_the_top_k_pairs_with_raw_texts(self):
        seen = []
        result = build_score_file(SEGMENTS, embeddings(), "vectors-sha", 3,
                                  lambda pairs: seen.extend(pairs) or fake_scorer(pairs), META)
        self.assertEqual(seen, [("대상 발언", "멀리 있는 관련 발언"), ("대상 발언", "조금 관련된 발언"), ("대상 발언", "근처 잡담")])
        self.assertEqual(result["format"], FORMAT)
        self.assertEqual(result["rerankInputVersion"], INPUT_VERSION)
        self.assertEqual(result["scoreType"], "raw-logit")
        self.assertEqual(result["candidateSource"], {"vectorFileSha256": "vectors-sha", "topK": 3})
        self.assertEqual(result["transcriptSha256"], embeddings()["transcriptSha256"])
        self.assertEqual(result["scores"], {"stt-index-1": {"stt-index-3": 11.0, "stt-index-4": 9.0, "stt-index-0": 5.0}})

    def test_rejects_inputs_that_break_the_contract(self):
        with self.assertRaises(ValueError):
            build_score_file(SEGMENTS[:-1], embeddings(), "vectors-sha", 3, fake_scorer, META)
        with self.assertRaises(ValueError):
            build_score_file(SEGMENTS, {**embeddings(), "format": "other"}, "vectors-sha", 3, fake_scorer, META)
        with self.assertRaises(ValueError):
            build_score_file(SEGMENTS, embeddings(), "vectors-sha", 0, fake_scorer, META)
        with self.assertRaises(ValueError):
            build_score_file(SEGMENTS, embeddings(), "vectors-sha", 3, fake_scorer, {**META, "revision": "main"})
        with self.assertRaises(ValueError):
            build_score_file(SEGMENTS, embeddings(), "vectors-sha", 3, lambda pairs: [math.nan] * len(pairs), META)
        with self.assertRaises(ValueError):
            build_score_file(SEGMENTS, embeddings(), "vectors-sha", 3, lambda pairs: [1.0], META)

    def test_downloads_only_the_safetensors_cross_encoder_files(self):
        self.assertIn("model.safetensors", ALLOW_PATTERNS)
        self.assertFalse(any(p.startswith("assets") or p.endswith((".bin", ".md")) for p in ALLOW_PATTERNS))


if __name__ == "__main__":
    unittest.main()
