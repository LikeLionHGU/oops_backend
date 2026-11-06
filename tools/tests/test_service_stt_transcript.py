"""Synthetic STT output only; no network or model calls."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from service_stt_transcript import check_structure, to_frozen_transcript


def stt(*segments):
    return {"language": "ko", "model": "whisper-large-v3", "timestampSource": "groq_segment",
            "segments": [{"startMs": s, "endMs": e, "text": t} for s, e, t in segments]}


class FrozenTranscriptTests(unittest.TestCase):
    def test_mirrors_the_service_mapping_and_speech_input_order(self):
        frozen = to_frozen_transcript(stt((5000, 6000, " 둘째 "), (0, 1000, "첫째"), (5000, 5500, "같은 시작"),
                                          (7000, 8000, "   ")))
        self.assertEqual(frozen["segments"], [
            {"id": "stt-index-0", "startMs": 0, "endMs": 1000, "text": "첫째"},
            {"id": "stt-index-1", "startMs": 5000, "endMs": 5500, "text": "같은 시작"},
            {"id": "stt-index-2", "startMs": 5000, "endMs": 6000, "text": "둘째"}])

    def test_structure_report_counts_and_compares_with_media_duration(self):
        frozen = to_frozen_transcript(stt((0, 1000, "가"), (900, 2000, "나다"), (2000, 2500, "라")))
        report = check_structure(frozen, media_duration_ms=10_000)
        self.assertEqual(report["segments"], 3)
        self.assertEqual(report["textCharacters"], 4)
        self.assertEqual(report["duplicateIds"], [])
        self.assertEqual(report["invalidTimes"], [])
        self.assertEqual(report["overlaps"], 1)
        self.assertEqual((report["firstStartMs"], report["lastEndMs"]), (0, 2500))
        self.assertEqual(report["tailWithoutSpeechMs"], 7500)
        self.assertTrue(report["valid"])

    def test_structure_report_flags_broken_input(self):
        frozen = {"segments": [{"id": "stt-index-0", "startMs": 0, "endMs": 1000, "text": "가"},
                               {"id": "stt-index-0", "startMs": 2000, "endMs": 1500, "text": "나"},
                               {"id": "stt-index-2", "startMs": 3000, "endMs": 12_000, "text": "다"}]}
        report = check_structure(frozen, media_duration_ms=10_000)
        self.assertEqual(report["duplicateIds"], ["stt-index-0"])
        self.assertEqual(report["invalidTimes"], ["stt-index-0"])
        self.assertEqual(report["nonSequentialIds"], ["stt-index-0"])
        self.assertEqual(report["beyondMedia"], ["stt-index-2"])
        self.assertFalse(report["valid"])

    def test_empty_stt_is_rejected(self):
        with self.assertRaises(ValueError):
            to_frozen_transcript(stt((0, 1000, "  ")))


if __name__ == "__main__":
    unittest.main()
