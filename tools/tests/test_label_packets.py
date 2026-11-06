"""Synthetic transcripts and labels only; no real incident labels."""
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from label_packets import case_type, derive_context_distance, export, make_packets, validate

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
        self.manifest = {"labelVersion": "dev-v0",
                         "videos": [{"videoId": 1, "clusterId": "video-1", "transcriptPath": "video-1.json",
                                     "targets": ["stt-index-0", "stt-index-2"]}]}
        (self.dir / "manifest.json").write_text(json.dumps(self.manifest), encoding="utf-8")
        self.t1 = {"role": "T1", "annotatorId": "ann-a", "labels": [
            {"videoId": 1, "targetSegmentId": "stt-index-0", "targetDecisionT1": "REVIEW_REQUIRED", "t1Rationale": "집단 일반화"},
            {"videoId": 1, "targetSegmentId": "stt-index-2", "targetDecisionT1": "PASS", "t1Rationale": "일상 대화"}]}
        self.t2 = {"role": "T2", "annotatorId": "ann-a", "t1FileSha256": None, "labels": [
            {"videoId": 1, "targetSegmentId": "stt-index-0", "finalDecision": "PASS",
             "goldContextSegmentIds": ["stt-index-3"], "contextNecessary": True,
             "evidenceSegmentIds": [], "necessityRationale": "뒤에서 주장을 철회함"},
            {"videoId": 1, "targetSegmentId": "stt-index-2", "finalDecision": "PASS",
             "goldContextSegmentIds": [], "contextNecessary": False,
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

    def a0(self, provided=None, manifest_sha=None):
        manifest = self.dir / "manifest.json"
        transcript = self.dir / "video-1.json"
        value = {"format": "a0-provided-v1", "selectorId": "A0-T:test", "labelVersion": "dev-v0",
                 "manifestSha256": manifest_sha or hashlib.sha256(manifest.read_bytes()).hexdigest(),
                 "transcriptSha256": {"1": hashlib.sha256(transcript.read_bytes()).hexdigest()},
                 "provided": {"1": provided or {
                     "stt-index-0": {"status": "PROVIDED", "contextSegmentIds": ["stt-index-1"]},
                     "stt-index-2": {"status": "PROVIDED", "contextSegmentIds": ["stt-index-3"]},
                     "stt-index-4": {"status": "NOT_PLANNED", "contextSegmentIds": []}}}}
        return self.write("a0-provided.json", value)

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

    def test_distance_is_near_when_a0_provides_all_gold_context_else_follows_topic_episodes(self):
        topics = {"stt-index-0": 0, "stt-index-1": 0, "stt-index-2": 1, "stt-index-3": 1, "stt-index-4": 2}

        def derive(gold, provided, necessary=True, decision="PASS", target="stt-index-0"):
            return derive_context_distance(target, gold, necessary, decision, provided, topics)

        self.assertEqual(derive(["stt-index-1"], ["stt-index-1", "stt-index-2"]), "Near")
        self.assertEqual(derive(["stt-index-2"], ["stt-index-1", "stt-index-2"]), "Near")
        self.assertEqual(derive(["stt-index-1"], []), "Far-topic")
        self.assertEqual(derive(["stt-index-3"], [], target="stt-index-2"), "Far-topic")
        self.assertEqual(derive(["stt-index-2"], []), "Far-video")
        self.assertEqual(derive(["stt-index-4"], []), "Far-video")
        self.assertEqual(derive(["stt-index-1", "stt-index-2"], ["stt-index-1"]), "Far-video")
        self.assertIsNone(derive(["stt-index-1"], [], decision="UNCERTAIN"))
        self.assertIsNone(derive([], [], necessary=False))

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

    def test_flags_necessity_rationale_and_labelled_distance(self):
        self.t2["labels"][0].update({"contextDistance": "Near", "necessityRationale": ""})
        self.t2["labels"][1].update({"contextNecessary": True})
        report = validate(*self.files())
        errors = {e["targetSegmentId"]: set(e["errors"]) for e in report["caseErrors"]}
        self.assertIn("CONTEXT_DISTANCE_IS_DERIVED", errors["stt-index-0"])
        self.assertIn("MISSING_NECESSITY_RATIONALE", errors["stt-index-0"])
        self.assertIn("CONTEXT_NECESSITY_MISMATCH", errors["stt-index-2"])

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
                                  "goldContextSegmentIds": [], "contextNecessary": False,
                                  "evidenceSegmentIds": [], "necessityRationale": ""})
        self.manifest["videos"][0]["targets"].append("stt-index-4")
        self.write("manifest.json", self.manifest)
        self.t1["labels"].append({"videoId": 1, "targetSegmentId": "stt-index-4", "targetDecisionT1": "UNCERTAIN", "t1Rationale": "정보 부족"})
        out = self.dir / "export"
        summary = export(*self.files(), out, self.a0())
        self.assertEqual(summary["exportedCases"], 3)
        self.assertEqual(summary["distanceStatusCounts"], {"COMPUTED": 1, "NOT_APPLICABLE": 2})
        self.assertEqual(summary["contextDistanceCounts"], {"Near": 0, "Far-topic": 0, "Far-video": 1})
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
        self.assertEqual(cases["binary"][0]["contextDistance"], "Far-video")
        self.assertEqual(cases["binary"][0]["distanceStatus"], "COMPUTED")
        self.assertEqual((cases["binary"][1]["contextDistance"], cases["binary"][1]["distanceStatus"]), (None, "NOT_APPLICABLE"))
        self.assertEqual(cases["intrinsicallyUncertain"][0]["distanceStatus"], "NOT_APPLICABLE")
        self.assertTrue(cases["distanceComputed"])
        self.assertNotIn("nearWindowMs", json.dumps(cases))
        with self.assertRaises(FileExistsError):
            export(*self.files(), out, self.a0())

    def test_export_without_a0_planner_output_marks_distance_not_computed(self):
        out = self.dir / "export"
        export(*self.files(), out, None)
        cases = json.loads((out / "gold-cases.json").read_text(encoding="utf-8"))
        self.assertFalse(cases["distanceComputed"])
        self.assertEqual((cases["binary"][0]["contextDistance"], cases["binary"][0]["distanceStatus"]), (None, "NOT_COMPUTED"))

    def test_near_when_a0_already_provides_gold_context_from_another_episode(self):
        out = self.dir / "export"
        export(*self.files(), out, self.a0({
            "stt-index-0": {"status": "PROVIDED", "contextSegmentIds": ["stt-index-1", "stt-index-3"]},
            "stt-index-2": {"status": "PROVIDED", "contextSegmentIds": []}}))
        cases = json.loads((out / "gold-cases.json").read_text(encoding="utf-8"))
        self.assertEqual(cases["binary"][0]["contextDistance"], "Near")

    def test_target_outside_the_a0_plan_has_no_distance(self):
        out = self.dir / "export"
        export(*self.files(), out, self.a0({
            "stt-index-0": {"status": "NOT_PLANNED", "contextSegmentIds": []},
            "stt-index-2": {"status": "PROVIDED", "contextSegmentIds": []}}))
        row = json.loads((out / "gold-cases.json").read_text(encoding="utf-8"))["binary"][0]
        self.assertEqual((row["contextDistance"], row["distanceStatus"]), (None, "TARGET_NOT_PLANNED"))
        report = json.loads((out / "export-report.json").read_text(encoding="utf-8"))
        self.assertEqual(report["distanceStatusCounts"]["TARGET_NOT_PLANNED"], 1)
        self.assertEqual(sum(report["contextDistanceCounts"].values()), 0)

    def test_export_refuses_a0_output_for_another_manifest_or_missing_targets(self):
        with self.assertRaises(ValueError):
            export(*self.files(), self.dir / "export-a", self.a0(manifest_sha="0" * 64))
        with self.assertRaises(ValueError):
            export(*self.files(), self.dir / "export-b", self.a0({
                "stt-index-0": {"status": "PROVIDED", "contextSegmentIds": []}}))


    def test_export_refuses_a_batch_with_file_level_errors(self):
        self.t2["t1FileSha256"] = "0" * 64
        with self.assertRaises(ValueError):
            export(*self.files(), self.dir / "export", None)


if __name__ == "__main__":
    unittest.main()
