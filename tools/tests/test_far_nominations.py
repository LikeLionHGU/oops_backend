"""Synthetic transcript and a fake nominator only; no network calls."""
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from far_nominations import check_nominations, nominate

SEGMENTS = [{"id": f"stt-index-{i}", "startMs": i * 1000, "endMs": i * 1000 + 900, "text": f"문장 {i}"} for i in range(8)]


def doc(*nominations, **override):
    value = {"role": "FAR_NOMINATOR", "annotatorId": "AI_M_FAR_NOMINATOR", "aiDraft": True,
             "nominations": [{"targetSegmentId": t, "expectedContextSegmentIds": c, "reason": "이유"} for t, c in nominations]}
    value.update(override)
    return value


class CheckNominationsTests(unittest.TestCase):
    def test_accepts_valid_nominations(self):
        self.assertEqual(check_nominations(doc(("stt-index-6", ["stt-index-1"])), SEGMENTS, 20), [])

    def test_flags_header_unknown_self_duplicate_reason_and_budget(self):
        bad = doc(("stt-index-6", ["stt-index-6"]), ("stt-index-6", ["stt-index-1"]), ("stt-index-9", []), aiDraft=False)
        bad["nominations"][1]["reason"] = ""
        self.assertEqual(set(check_nominations(bad, SEGMENTS, 2)),
                         {"NOMINATION_HEADER", "NOMINATION_OVER_BUDGET", "NOMINATION_UNKNOWN_ID:stt-index-9",
                          "NOMINATION_SELF_CONTEXT:stt-index-6", "NOMINATION_DUPLICATE:stt-index-6",
                          "NOMINATION_REASON:stt-index-6", "NOMINATION_NO_CONTEXT:stt-index-9"})

    def test_risk_role_allows_empty_context_and_checks_its_own_header(self):
        risk = doc(("stt-index-6", []), role="RISK_NOMINATOR", annotatorId="AI_M_RISK_NOMINATOR")
        self.assertEqual(check_nominations(risk, SEGMENTS, 20, role="RISK_NOMINATOR"), [])
        self.assertIn("NOMINATION_HEADER", check_nominations(risk, SEGMENTS, 20))
        self.assertIn("NOMINATION_SELF_CONTEXT:stt-index-6",
                      check_nominations(doc(("stt-index-6", ["stt-index-6"]), role="RISK_NOMINATOR"), SEGMENTS, 20,
                                        role="RISK_NOMINATOR"))


class NominateTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        base = Path(self.tmp.name)
        (base / "vid").mkdir()
        self.transcript = base / "vid" / "transcript.json"
        self.transcript.write_text(json.dumps({"segments": SEGMENTS}, ensure_ascii=False), encoding="utf-8")
        self.instructions = base / "nominate.md"
        self.instructions.write_text("메모\n---\n추천 지시", encoding="utf-8")
        self.out = base / "far-2001"

    def tearDown(self):
        self.tmp.cleanup()

    def run_with(self, output, role="FAR_NOMINATOR"):
        calls = []

        def model(system, user):
            calls.append((system, json.loads(user)))
            return json.dumps(output, ensure_ascii=False), {"id": "n1", "model": "m", "usage": {"prompt_tokens": 5}}

        return nominate(self.transcript, 2001, "youtube-vid", "far-v", self.instructions, self.out, model, {"model": "m"},
                        role=role), calls

    def test_freezes_nominations_and_builds_a_manifest_in_transcript_order(self):
        record, calls = self.run_with(doc(("stt-index-7", ["stt-index-1"]), ("stt-index-5", ["stt-index-0"])))
        system, packet = calls[0]
        self.assertEqual(system, "추천 지시")
        self.assertEqual(packet, {"role": "FAR_NOMINATOR", "videos": [{"videoId": 2001, "segments": SEGMENTS}]})
        manifest = json.loads((self.out / "manifest.json").read_text())
        self.assertEqual(manifest, {"labelVersion": "far-v", "videos": [
            {"videoId": 2001, "clusterId": "youtube-vid", "transcriptPath": "../vid/transcript.json",
             "targets": ["stt-index-5", "stt-index-7"]}]})
        self.assertEqual(record["nominations"], 2)
        for name in ("nominations.json", "manifest.json", "run-record.json", "nomination-packet.json"):
            self.assertFalse((self.out / name).stat().st_mode & 0o222)

    def test_invalid_output_keeps_raw_text_but_no_manifest(self):
        with self.assertRaises(ValueError):
            self.run_with(doc(("stt-index-9", ["stt-index-1"])))
        self.assertTrue((self.out / "raw" / "nomination.txt").exists())
        self.assertFalse((self.out / "manifest.json").exists())
        self.assertEqual(json.loads((self.out / "run-record.json").read_text())["state"], "FAILED")

    def test_zero_nominations_is_recorded_without_a_manifest(self):
        record, _ = self.run_with(doc())
        self.assertEqual((record["state"], record["nominations"]), ("COMPLETE_NO_CANDIDATES", 0))
        self.assertFalse((self.out / "manifest.json").exists())

    def test_risk_role_sends_its_role_and_records_it(self):
        record, calls = self.run_with(doc(("stt-index-3", []), role="RISK_NOMINATOR"), role="RISK_NOMINATOR")
        self.assertEqual(calls[0][1]["role"], "RISK_NOMINATOR")
        self.assertEqual((record["role"], record["state"]), ("RISK_NOMINATOR", "COMPLETE"))


if __name__ == "__main__":
    unittest.main()
