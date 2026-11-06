"""Synthetic transcripts and labels only; no real incident labels."""
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from label_packets import case_type, context_distance, export, make_packets, validate

SEGMENTS = [
    {"id": "stt-index-0", "startMs": 0, "endMs": 1500, "text": "요즘 신입들은 끈기가 없어"},
    {"id": "stt-index-1", "startMs": 2000, "endMs": 3500, "text": "그건 너무 일반화야"},
    {"id": "stt-index-2", "startMs": 60000, "endMs": 61500, "text": "점심 메뉴 이야기"},
    {"id": "stt-index-3", "startMs": 200000, "endMs": 201500, "text": "아까 신입 얘기는 내가 틀렸어"},
    {"id": "stt-index-4", "startMs": 400000, "endMs": 401500, "text": "다시 신입 이야기로 돌아가자"},
]


class LabelPacketTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        (self.dir / "video-1.json").write_text(json.dumps({"segments": SEGMENTS}, ensure_ascii=False), encoding="utf-8")
        self.manifest = {"labelVersion": "dev-v0", "nearWindowMs": 30000,
                         "videos": [{"videoId": 1, "clusterId": "video-1", "transcriptPath": "video-1.json",
                                     "targets": ["stt-index-0", "stt-index-2"]}]}
        (self.dir / "manifest.json").write_text(json.dumps(self.manifest), encoding="utf-8")
        self.t1 = {"role": "T1", "annotatorId": "ann-a", "labels": [
            {"videoId": 1, "targetSegmentId": "stt-index-0", "targetDecisionT1": "REVIEW_REQUIRED", "t1Rationale": "집단 일반화"},
            {"videoId": 1, "targetSegmentId": "stt-index-2", "targetDecisionT1": "PASS", "t1Rationale": "일상 대화"}]}
        self.t2 = {"role": "T2", "annotatorId": "ann-a", "t1FileSha256": None, "labels": [
            {"videoId": 1, "targetSegmentId": "stt-index-0", "finalDecision": "PASS",
             "goldContextSegmentIds": ["stt-index-3"], "contextNecessary": True, "contextDistance": "Far-video",
             "evidenceSegmentIds": [], "necessityRationale": "뒤에서 주장을 철회함"},
            {"videoId": 1, "targetSegmentId": "stt-index-2", "finalDecision": "PASS",
             "goldContextSegmentIds": [], "contextNecessary": False, "contextDistance": None,
             "evidenceSegmentIds": [], "necessityRationale": ""}]}
        self.speakers = {"role": "SPEAKER", "annotatorId": "ann-s", "labels": {"1": {
            "stt-index-0": {"speakerId": "A", "ambiguous": False}, "stt-index-1": {"speakerId": "B", "ambiguous": False},
            "stt-index-2": {"speakerId": None, "ambiguous": True}, "stt-index-3": {"speakerId": "A", "ambiguous": False},
            "stt-index-4": {"speakerId": "B", "ambiguous": False}}}}
        self.topics = {"role": "TOPIC", "annotatorId": "ann-t", "episodeStarts": {"1": ["stt-index-2", "stt-index-4"]}}

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, name, value):
        path = self.dir / name
        path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
        return path

    def files(self):
        t1 = self.write("t1.json", self.t1)
        if self.t2["t1FileSha256"] is None:
            self.t2["t1FileSha256"] = hashlib.sha256(t1.read_bytes()).hexdigest()
        return (self.dir / "manifest.json", t1, self.write("t2.json", self.t2),
                self.write("speakers.json", self.speakers), self.write("topics.json", self.topics))

    def test_packets_hide_what_each_role_must_not_see(self):
        out = self.dir / "packets"
        make_packets(self.dir / "manifest.json", out)
        t1 = json.loads((out / "t1-packet.json").read_text(encoding="utf-8"))
        self.assertEqual([c["targetText"] for c in t1["cases"]], ["요즘 신입들은 끈기가 없어", "점심 메뉴 이야기"])
        self.assertNotIn("segments", json.dumps(t1, ensure_ascii=False))
        self.assertNotIn("그건 너무 일반화야", json.dumps(t1, ensure_ascii=False))
        t2 = json.loads((out / "t2-packet.json").read_text(encoding="utf-8"))
        self.assertEqual(len(t2["videos"][0]["segments"]), 5)
        self.assertIsNone(t2["cases"][0]["finalDecision"])
        for role in ("speaker", "topic"):
            packet = (out / f"{role}-packet.json").read_text(encoding="utf-8")
            self.assertNotIn("targets", packet)
            self.assertNotIn("Decision", packet)
        with self.assertRaises(FileExistsError):
            make_packets(self.dir / "manifest.json", out)

    def test_distance_uses_the_near_window_then_topic_episodes(self):
        episodes = {"stt-index-0": 0, "stt-index-1": 0, "stt-index-2": 1, "stt-index-3": 1, "stt-index-4": 2}
        by_id = {s["id"]: s for s in SEGMENTS}
        target = by_id["stt-index-0"]
        self.assertEqual(context_distance(target, [by_id["stt-index-1"]], episodes, 30000), "Near")
        self.assertEqual(context_distance(by_id["stt-index-2"], [by_id["stt-index-3"]], episodes, 30000), "Far-topic")
        self.assertEqual(context_distance(target, [by_id["stt-index-1"], by_id["stt-index-3"]], episodes, 30000), "Far-video")
        self.assertIsNone(context_distance(target, [], episodes, 30000))

    def test_valid_batch_has_no_errors(self):
        report = validate(*self.files())
        self.assertEqual(report["fileErrors"], [])
        self.assertEqual(report["caseErrors"], [])
        self.assertEqual(report["validCases"], 2)

    def test_collects_case_errors_without_stopping(self):
        bad = self.t2["labels"][0]
        bad.update({"goldContextSegmentIds": ["stt-index-0", "stt-index-9"], "contextDistance": "Near",
                    "finalDecision": "UNCERTAIN", "evidenceSegmentIds": ["stt-index-8"]})
        self.t2["labels"][1].update({"finalDecision": "REVIEW_REQUIRED"})
        report = validate(*self.files())
        errors = {e["targetSegmentId"]: set(e["errors"]) for e in report["caseErrors"]}
        self.assertTrue({"SELF_CONTEXT_REFERENCE", "INVALID_CONTEXT_IDS", "INVALID_EVIDENCE_IDS",
                         "UNCERTAIN_CONTEXT_CONFLICT"} <= errors["stt-index-0"])
        self.assertIn("MISSING_RISK_EVIDENCE", errors["stt-index-2"])
        self.assertEqual(report["validCases"], 0)

    def test_flags_necessity_distance_and_rationale_inconsistencies(self):
        self.t2["labels"][0].update({"contextDistance": "Near", "necessityRationale": ""})
        self.t2["labels"][1].update({"contextNecessary": True})
        report = validate(*self.files())
        errors = {e["targetSegmentId"]: set(e["errors"]) for e in report["caseErrors"]}
        self.assertIn("CONTEXT_DISTANCE_MISMATCH", errors["stt-index-0"])
        self.assertIn("MISSING_NECESSITY_RATIONALE", errors["stt-index-0"])
        self.assertTrue({"CONTEXT_NECESSITY_MISMATCH", "MISSING_CONTEXT_DISTANCE"} <= errors["stt-index-2"])

    def test_file_level_rules_freeze_t1_keep_annotators_blind_and_cover_every_segment(self):
        self.t2["t1FileSha256"] = "0" * 64
        self.speakers["annotatorId"] = "ann-a"
        del self.speakers["labels"]["1"]["stt-index-4"]
        self.t2["labels"].pop()
        report = validate(*self.files())
        self.assertTrue({"T1_NOT_FROZEN", "BLINDNESS_VIOLATION", "SPEAKER_LABELS_INCOMPLETE"} <= set(report["fileErrors"]))
        errors = {e["targetSegmentId"]: set(e["errors"]) for e in report["caseErrors"]}
        self.assertIn("MISSING_T2", errors["stt-index-2"])

    def test_case_type_follows_t2_only(self):
        self.assertEqual(case_type({"finalDecision": "UNCERTAIN", "contextNecessary": False}), "INTRINSICALLY_UNCERTAIN")
        self.assertEqual(case_type({"finalDecision": "PASS", "contextNecessary": True}), "CONTEXT_DEPENDENT_PASS")
        self.assertEqual(case_type({"finalDecision": "REVIEW_REQUIRED", "contextNecessary": False}),
                         "CONTEXT_INDEPENDENT_REVIEW_REQUIRED")

    def test_export_writes_cli_and_aggregator_inputs_for_valid_cases_only(self):
        self.t2["labels"].append({"videoId": 1, "targetSegmentId": "stt-index-4", "finalDecision": "UNCERTAIN",
                                  "goldContextSegmentIds": [], "contextNecessary": False, "contextDistance": None,
                                  "evidenceSegmentIds": [], "necessityRationale": ""})
        self.manifest["videos"][0]["targets"].append("stt-index-4")
        self.write("manifest.json", self.manifest)
        self.t1["labels"].append({"videoId": 1, "targetSegmentId": "stt-index-4", "targetDecisionT1": "UNCERTAIN", "t1Rationale": "정보 부족"})
        out = self.dir / "export"
        summary = export(*self.files(), out)
        self.assertEqual(summary["exportedCases"], 3)
        gold = json.loads((out / "gold-oracle.json").read_text(encoding="utf-8"))
        self.assertEqual(gold["labelVersion"], "dev-v0")
        self.assertEqual(gold["labels"]["1"]["stt-index-0"],
                         {"goldContextSegmentIds": ["stt-index-3"], "evidenceSegmentIds": []})
        speakers = json.loads((out / "speakers-a6h.json").read_text(encoding="utf-8"))
        self.assertEqual(speakers["labels"]["1"]["stt-index-2"], {"speakerId": None, "ambiguous": True})
        cases = json.loads((out / "gold-cases.json").read_text(encoding="utf-8"))
        self.assertEqual([(c["targetId"], c["expected"]) for c in cases["binary"]],
                         [("stt-index-0", "NO_RISK"), ("stt-index-2", "NO_RISK")])
        self.assertEqual([c["targetId"] for c in cases["intrinsicallyUncertain"]], ["stt-index-4"])
        self.assertEqual(cases["binary"][0]["clusterId"], "video-1")
        self.assertEqual(cases["binary"][0]["caseType"], "CONTEXT_DEPENDENT_PASS")
        self.assertEqual(cases["nearWindowMs"], 30000)
        with self.assertRaises(FileExistsError):
            export(*self.files(), out)

    def test_export_refuses_a_batch_with_file_level_errors(self):
        self.t2["t1FileSha256"] = "0" * 64
        with self.assertRaises(ValueError):
            export(*self.files(), self.dir / "export")


if __name__ == "__main__":
    unittest.main()
