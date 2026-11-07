"""Synthetic casebook entries only."""
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from casebook_reasons import apply_quote_overrides, build_items, extract_quotes, write_items


def statement(sid, known, mechanisms=("group-generalization",), boundaries=()):
    return {"id": sid, "mechanismIds": list(mechanisms),
            "legacyCard": {"knownPoint": {"text": known}, "controversy": {"boundaries": list(boundaries)}}}


def incident(iid, *statements):
    return {"incidentId": iid, "statements": list(statements)}


RUNTIME = {"examples": [{"id": "b#s1"}, {"id": "a#s1"}]}


class QuoteTests(unittest.TestCase):
    def test_extract_quotes_handles_all_quote_styles_and_dedupes(self):
        text = "그가 ‘여기 중국 아니에요?’라고 했고 '소촌이에요' 그리고 “뭐 있겠나” \"발령 기피\" ‘여기 중국 아니에요?’"
        self.assertEqual(extract_quotes(text), ["여기 중국 아니에요?", "소촌이에요", "뭐 있겠나", "발령 기피"])

    def test_single_character_quotes_are_ignored(self):
        self.assertEqual(extract_quotes("‘아’ 그리고 ‘좋아요’"), ["좋아요"])


class ItemTests(unittest.TestCase):
    def setUp(self):
        self.incidents = [
            incident("b", statement("b#s1", "‘둘째 인용’", boundaries=["경계 설명"])),
            incident("a", statement("a#s1", "‘첫째 인용’과 ‘둘째’"), statement("a#s2", "인용 없음", ("insult-mockery",))),
        ]

    def test_items_carry_source_text_mechanisms_and_runtime_flag(self):
        items = {i["statementId"]: i for i in build_items(self.incidents, RUNTIME)}
        self.assertEqual(items["b#s1"]["sourceText"], "‘둘째 인용’\n경계 설명")
        self.assertEqual(items["a#s1"]["familyId"], "a")
        self.assertTrue(items["a#s1"]["inRuntime"])
        self.assertFalse(items["a#s2"]["inRuntime"])
        self.assertEqual(items["a#s2"]["mechanismIds"], ["insult-mockery"])

    def test_statement_without_quotes_is_excluded_with_reason(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "items"
            write_items(build_items(self.incidents, RUNTIME), out)
            doc = json.loads((out / "casebook-items-v1.json").read_text())
            self.assertEqual(doc["excluded"], [{"statementId": "a#s2", "reason": "NO_QUOTE"}])
            families = json.loads((out / "families.json").read_text())
            self.assertNotIn("a#s2", json.loads((out / "targets.json").read_text()).__str__())
            self.assertEqual(len(families), 2)

    def test_video_ids_follow_sorted_statement_ids_and_segments_are_ordered(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "items"
            write_items(build_items(self.incidents, RUNTIME), out)
            families = json.loads((out / "families.json").read_text())
            self.assertEqual(families, {"6001": "a", "6002": "b"})
            segments = json.loads((out / "transcripts" / "6001.json").read_text())["segments"]
            self.assertEqual([(s["id"], s["startMs"], s["endMs"], s["text"]) for s in segments],
                             [("stt-index-0", 0, 2500, "첫째 인용"), ("stt-index-1", 3000, 5500, "둘째")])
            targets = json.loads((out / "targets.json").read_text())
            self.assertEqual(targets[0], {"videoId": 6001, "targetId": "stt-index-0", "statementId": "a#s1"})
            self.assertEqual(len(targets), 3)

    def test_quote_overrides_replace_or_drop_extracted_quotes(self):
        items = apply_quote_overrides(build_items(self.incidents, RUNTIME), {"a#s1": ["첫째 인용"], "b#s1": []})
        by = {i["statementId"]: i for i in items}
        self.assertEqual(by["a#s1"]["quotes"], ["첫째 인용"])
        self.assertEqual(by["b#s1"]["quotes"], [])
        self.assertEqual(by["a#s1"]["quoteSource"], "HUMAN_OVERRIDE")
        self.assertEqual(by["a#s2"]["quoteSource"], "EXTRACTED")
        with self.assertRaises(ValueError):
            apply_quote_overrides(items, {"zzz#s9": ["x"]})

    def test_output_dir_must_be_new(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(FileExistsError):
                write_items(build_items(self.incidents, RUNTIME), Path(tmp))


if __name__ == "__main__":
    unittest.main()
