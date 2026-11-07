"""Synthetic packets and a fake model only; no network calls."""
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ai_labels import check_t1, check_topics, fill_t1_sha, instruction_body, parse_output, run_labels

SEGMENTS = [{"id": f"stt-index-{i}", "startMs": i * 1000, "endMs": i * 1000 + 900, "text": f"문장 {i}"} for i in range(6)]


def t1_packet():
    return {"role": "T1", "labelVersion": "v", "cases": [
        {"videoId": 7, "targetSegmentId": "stt-index-1", "targetText": "문장 1"},
        {"videoId": 7, "targetSegmentId": "stt-index-4", "targetText": "문장 4"}]}


def t1_output(**override):
    doc = {"role": "T1", "annotatorId": "AI_M_T1_DRAFT", "labelVersion": "v", "aiDraft": True, "t1InstructionVersion": "v2",
           "labels": [{"videoId": 7, "targetSegmentId": t, "targetDecisionT1": "PASS", "t1Rationale": "이유",
                       "transcriptQualityNote": None} for t in ("stt-index-1", "stt-index-4")]}
    doc.update(override)
    return doc


class PureFunctionTests(unittest.TestCase):
    def test_instruction_body_drops_the_operator_preamble(self):
        self.assertEqual(instruction_body("# 제목\n\n운영자 메모\n\n---\n\n너는 라벨러다.\n"), "너는 라벨러다.")

    def test_instruction_body_requires_a_separator(self):
        with self.assertRaises(ValueError):
            instruction_body("구분선 없음")

    def test_parse_output_accepts_plain_or_fenced_json(self):
        self.assertEqual(parse_output('{"a": 1}'), {"a": 1})
        self.assertEqual(parse_output('```json\n{"a": 1}\n```'), {"a": 1})
        with self.assertRaises(ValueError):
            parse_output("설명 문장")

    def test_check_t1_accepts_complete_labels_in_packet_order(self):
        self.assertEqual(check_t1(t1_output(), t1_packet()), [])

    def test_check_t1_flags_order_decision_rationale_and_header(self):
        bad = t1_output(aiDraft=False, labelVersion="x")
        bad["labels"] = list(reversed(bad["labels"]))
        bad["labels"][0]["targetDecisionT1"] = "MAYBE"
        bad["labels"][1]["t1Rationale"] = " "
        self.assertEqual(set(check_t1(bad, t1_packet())),
                         {"T1_HEADER", "T1_ORDER", "T1_DECISION:stt-index-4", "T1_RATIONALE:stt-index-1"})

    def test_check_topics_flags_unknown_unsorted_first_and_label_mismatch(self):
        ok = {"role": "TOPIC", "annotatorId": "A", "aiDraft": True, "episodeStarts": {"7": ["stt-index-2", "stt-index-4"]},
              "episodeLabels": {"7": {"stt-index-2": "가", "stt-index-4": "나"}}}
        self.assertEqual(check_topics(ok, 7, SEGMENTS), [])
        bad = {"role": "TOPIC", "annotatorId": "A", "aiDraft": True,
               "episodeStarts": {"7": ["stt-index-0", "stt-index-4", "stt-index-2", "stt-index-9"]},
               "episodeLabels": {"7": {"stt-index-2": "가"}}}
        self.assertEqual(set(check_topics(bad, 7, SEGMENTS)),
                         {"TOPIC_UNKNOWN_ID", "TOPIC_NOT_ASCENDING", "TOPIC_FIRST_SEGMENT", "TOPIC_LABELS_MISMATCH"})

    def test_fill_t1_sha_replaces_exactly_one_placeholder(self):
        self.assertEqual(fill_t1_sha('"t1FileSha256": "<T1 파일 SHA-256>"', "ab"), '"t1FileSha256": "ab"')
        with self.assertRaises(ValueError):
            fill_t1_sha("자리 없음", "ab")


class FakeModel:
    def __init__(self, t2_sha_override=None):
        self.calls, self.t2_sha_override = [], t2_sha_override

    def __call__(self, system, user):
        self.calls.append((system, user))
        packet = json.loads(user)
        if packet["role"] == "T1":
            return json.dumps(t1_output(), ensure_ascii=False), {"id": "r1", "usage": {"prompt_tokens": 10}}
        if packet["role"] == "TOPIC":
            return json.dumps({"role": "TOPIC", "annotatorId": "AI_M_TOPIC_DRAFT", "aiDraft": True,
                               "episodeStarts": {"7": ["stt-index-3"]}, "episodeLabels": {"7": {"stt-index-3": "둘째"}}},
                              ensure_ascii=False), {"id": "r2"}
        sha = self.t2_sha_override or system.split('"t1FileSha256": "')[1].split('"')[0]
        labels = [{"videoId": 7, "targetSegmentId": t, "finalDecision": "PASS", "goldContextSegmentIds": [],
                   "contextNecessary": False, "necessityRationale": "", "evidenceSegmentIds": [],
                   "priorReviewConsulted": False, "transcriptQualityNote": None} for t in ("stt-index-1", "stt-index-4")]
        return json.dumps({"role": "T2", "annotatorId": "AI_M_T2_DRAFT", "aiDraft": True, "t1FileSha256": sha,
                           "labels": labels}, ensure_ascii=False), {"id": "r3"}


class RunLabelsTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.handoff = Path(self.tmp.name) / "handoff"
        self.handoff.mkdir()
        video = {"videoId": 7, "segments": SEGMENTS}
        (self.handoff / "t1-packet.json").write_text(json.dumps(t1_packet(), ensure_ascii=False), encoding="utf-8")
        (self.handoff / "topic-packet.json").write_text(json.dumps({"role": "TOPIC", "videos": [video]}), encoding="utf-8")
        (self.handoff / "t2-packet.json").write_text(json.dumps({"role": "T2", "videos": [video], "cases": []}), encoding="utf-8")
        (self.handoff / "T1-instructions.md").write_text("메모\n---\nT1 지시", encoding="utf-8")
        (self.handoff / "Topic-instructions.md").write_text("메모\n---\nTopic 지시", encoding="utf-8")
        (self.handoff / "T2-instructions.md").write_text('메모\n---\nT2 지시 {"t1FileSha256": "<T1 파일 SHA-256>"}', encoding="utf-8")
        self.out = Path(self.tmp.name) / "labels"

    def tearDown(self):
        self.tmp.cleanup()

    def test_runs_t1_topic_then_t2_with_the_frozen_t1_sha(self):
        model = FakeModel()
        record = run_labels(self.handoff, self.out, model, {"model": "m"})
        t1_sha = hashlib.sha256((self.out / "t1-ai-draft.json").read_bytes()).hexdigest()
        self.assertEqual([json.loads(u)["role"] for _, u in model.calls], ["T1", "TOPIC", "T2"])
        self.assertEqual(model.calls[0][0], "T1 지시")
        self.assertIn(t1_sha, model.calls[2][0])
        self.assertEqual(json.loads((self.out / "t2-initial.json").read_text())["t1FileSha256"], t1_sha)
        self.assertEqual(record["files"]["t1-ai-draft.json"], t1_sha)
        self.assertEqual([c["responseId"] for c in record["calls"]], ["r1", "r2", "r3"])
        for name in ("t1-ai-draft.json", "topics-ai-draft.json", "t2-initial.json", "run-record.json"):
            self.assertFalse((self.out / name).stat().st_mode & 0o222)

    def test_t2_with_a_wrong_t1_sha_is_kept_raw_but_not_frozen(self):
        with self.assertRaises(ValueError):
            run_labels(self.handoff, self.out, FakeModel(t2_sha_override="0" * 64), {"model": "m"})
        self.assertFalse((self.out / "t2-initial.json").exists())
        self.assertTrue((self.out / "raw" / "t2.txt").exists())

    def test_refuses_an_existing_output_directory(self):
        self.out.mkdir()
        with self.assertRaises(FileExistsError):
            run_labels(self.handoff, self.out, FakeModel(), {"model": "m"})


if __name__ == "__main__":
    unittest.main()
