"""Synthetic context units only; no real incident labels or network calls."""
import copy
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from build_context_units import compile_preview


class ContextUnitTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        raw = json.dumps({"tasks": [{"spec": {"id": "source"}, "input": json.dumps({"raw": [
            {"id": "s1", "text": "평가 표현", "startMs": 0, "endMs": 1000},
            {"id": "s2", "text": "후속 상황", "startMs": 1000, "endMs": 2000}]})}]}).encode()
        (self.root / "source.json").write_bytes(raw)
        self.unit = {"schemaVersion": "context-reference-unit-1", "id": "incident#unit", "parentCaseId": "old",
                     "familyId": "incident", "status": "DRAFT", "humanVerified": False, "trainingUseAuthorized": False,
                     "routingKeys": ["graphic-body-metaphor"], "retrievalText": "검색용 고정 문구",
                     "interpretation": "작성한 해석", "boundary": "원음 검수 전",
                     "source": {"kind": "SAVED_VERIFICATION_PLAN", "path": "source.json",
                                "sha256": hashlib.sha256(raw).hexdigest(), "taskId": "source"},
                     "utteranceIds": ["s1", "s2"], "roles": {"situationIds": ["s2"], "evaluationIds": ["s1"]},
                     "normalContrasts": [{"kind": "SYNTHETIC_DEVELOPMENT_CONTROL", "text": "합성 정상 대조"}]}
        context = {"coverage": "REPORTED_CONTEXT_ONLY_NOT_VIDEO_TRANSCRIPT", "flow": ["기존 사건 요약"],
                   "criticismHypotheses": [], "missingContext": ["출처 검수 전"], "sourceInterpretations": []}
        self.base = {"schemaVersion": "review-guidelines-4", "examples": [
            {"id": "old", "familyId": "incident", "mechanismIds": ["broad"], "channels": ["SPEECH"], "context": context},
            {"id": "retained", "familyId": "other", "mechanismIds": ["broad"], "channels": ["SPEECH"], "context": context}],
            "guidelines": [{"id": "broad", "condition": "기존 판단 조건", "sourceCaseIds": ["old", "retained"]}]}
        self.catalog = {"schemaVersion": "context-routing-keys-1", "keys": [{"id": "graphic-body-metaphor"}]}

    def compile(self, unit=None):
        return compile_preview(self.base, [unit or self.unit], self.catalog, self.root)

    def test_order_preserves_raw_then_later_context_not_role_order(self):
        out, manifest = self.compile()
        self.assertEqual(out["examples"][0]["context"]["flow"], ["평가 원문: 평가 표현", "상황 원문: 후속 상황"])
        self.assertEqual(out["examples"][0]["context"]["normalContrasts"], ["합성 개발 대조(실제 관측 아님): 합성 정상 대조"])
        self.assertEqual(manifest["sourceLinks"][0]["utterances"][0]["startMs"], 0)
        self.assertFalse(manifest["activeRuntimeChanged"])

    def test_original_inputs_remain_identical_and_unconverted_examples_stay_visible(self):
        before = copy.deepcopy(self.base)
        out, manifest = self.compile()
        self.assertEqual(self.base, before)
        self.assertEqual(manifest["retainedLegacyCount"], 1)
        self.assertEqual(out["examples"][1]["routingKeys"], [])
        self.assertEqual(out["examples"][1]["context"]["flow"], ["기존 사건 요약"])

    def test_judgment_edits_do_not_change_authored_retrieval_text(self):
        first, _ = self.compile()
        self.unit["interpretation"] = "수정한 판단 해석"
        self.base["guidelines"][0]["condition"] = "다른 기준 설명"
        second, _ = self.compile()
        self.assertEqual(first["examples"][0]["retrievalView"], second["examples"][0]["retrievalView"])

    def test_unknown_route_duplicate_and_missing_source_are_rejected(self):
        for change in ({"routingKeys": ["unknown"]}, {"utteranceIds": ["s1", "unknown"]},
                       {"utteranceIds": ["s1", "s1"]}, {"utteranceIds": ["s2", "s1"]},
                       {"roles": {"situationIds": [], "evaluationIds": []}}):
            unit = {**copy.deepcopy(self.unit), **change}
            with self.assertRaises(ValueError):
                self.compile(unit)
        with self.assertRaises(ValueError):
            compile_preview(self.base, [self.unit, self.unit], self.catalog, self.root)

    def test_source_mutation_and_control_promotion_are_rejected(self):
        unit = copy.deepcopy(self.unit)
        unit["normalContrasts"][0]["kind"] = "OBSERVED_APPROVED_VIDEO"
        with self.assertRaises(ValueError): self.compile(unit)
        (self.root / "source.json").write_text("{}")
        with self.assertRaises(ValueError): self.compile()

    def test_null_source_times_are_not_replaced_with_synthetic_timing(self):
        incident = {"incidentId": "incident", "statements": [{"id": "old", "legacyCard": {"evidence": {
            "segments": [{"id": "s1", "text": "평가 표현", "startMs": None, "endMs": None},
                         {"id": "s2", "text": "후속 상황", "startMs": None, "endMs": None}]}}}]}
        raw = json.dumps(incident).encode()
        (self.root / "source.json").write_bytes(raw)
        self.unit["source"].update(kind="CANONICAL_STATEMENT", sha256=hashlib.sha256(raw).hexdigest())
        _, manifest = self.compile()
        self.assertIsNone(manifest["sourceLinks"][0]["utterances"][0]["startMs"])


if __name__ == "__main__": unittest.main()
