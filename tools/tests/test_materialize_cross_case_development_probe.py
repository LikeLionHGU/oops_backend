import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from tools.materialize_cross_case_development_probe import (
    canonical_segment_hash,
    materialize,
    select_segments,
)


class MaterializeCrossCaseProbeTests(unittest.TestCase):
    def test_canonical_hash_is_utf8_compact_json_with_final_newline(self):
        rows = [{"startMs": 1, "endMs": 2, "text": "태권도"}]
        canonical = '[{"startMs":1,"endMs":2,"text":"태권도"}]\n'.encode("utf-8")
        self.assertEqual(hashlib.sha256(canonical).hexdigest(), canonical_segment_hash(rows))

    def test_array_slice_selects_and_verifies_frozen_hash(self):
        rows = [
            {"startMs": 0, "endMs": 1, "text": "앞"},
            {"startMs": 1, "endMs": 2, "text": "검은 띠"},
            {"startMs": 2, "endMs": 3, "text": "대충 다녀도"},
        ]
        expected = rows[1:]
        selector = {
            "jsonPointer": "/data",
            "mode": "array-slice",
            "startIndexInclusive": 1,
            "endIndexExclusive": 3,
            "expectedSegmentCount": 2,
            "selectedContentSha256": canonical_segment_hash(expected),
        }
        self.assertEqual(expected, select_segments({"data": rows}, selector))

    def test_time_filter_preserves_source_order_and_upper_bound_is_exclusive(self):
        rows = [
            {"startMs": 10, "endMs": 11, "text": "선택"},
            {"startMs": 20, "endMs": 21, "text": "제외"},
            {"startMs": 15, "endMs": 16, "text": "선택"},
        ]
        expected = [rows[0], rows[2]]
        selector = {
            "jsonPointer": "/segments",
            "mode": "array-filter-by-startMs",
            "startMsInclusive": 10,
            "startMsExclusive": 20,
            "expectedSegmentCount": 2,
            "selectedContentSha256": canonical_segment_hash(expected),
        }
        self.assertEqual(expected, select_segments({"segments": rows}, selector))

    def test_materializer_emits_only_opaque_ids_and_text_segments(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "transcript.json"
            rows = [{"startMs": 1, "endMs": 2, "text": "일반 문장"}]
            source.write_text(json.dumps({"data": rows}, ensure_ascii=False), encoding="utf-8")
            selectors = {
                "id": "probe-v1",
                "status": "FROZEN_TEXT_SELECTORS_NOT_RUN",
                "sourcePolicy": {"expectedLabelsIncluded": False},
                "items": [{
                    "sampleId": "DEV-01",
                    "source": {"path": "transcript.json", "sha256": hashlib.sha256(source.read_bytes()).hexdigest()},
                    "selector": {
                        "jsonPointer": "/data",
                        "mode": "array-slice",
                        "startIndexInclusive": 0,
                        "endIndexExclusive": 1,
                        "expectedSegmentCount": 1,
                    },
                    "selectedContentSha256": canonical_segment_hash(rows),
                }],
            }
            result = materialize(selectors, root)
            self.assertEqual("MATERIALIZED_TEXT_ONLY_NO_LABELS", result["status"])
            self.assertEqual({"sampleId", "segments"}, set(result["items"][0]))
            self.assertEqual(rows, result["items"][0]["segments"])
            self.assertNotIn("expectedDecision", json.dumps(result))

    def test_materializer_rejects_modified_source(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "transcript.json").write_text('{"data":[]}', encoding="utf-8")
            selectors = {
                "id": "probe-v1",
                "status": "FROZEN_TEXT_SELECTORS_NOT_RUN",
                "sourcePolicy": {"expectedLabelsIncluded": False},
                "items": [{
                    "sampleId": "DEV-01",
                    "source": {"path": "transcript.json", "sha256": "0" * 64},
                    "selector": {},
                }],
            }
            with self.assertRaisesRegex(ValueError, "source hash changed"):
                materialize(selectors, root)


if __name__ == "__main__":
    unittest.main()
