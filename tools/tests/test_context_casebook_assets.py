"""Synthetic fixtures for inventory, source-linked boundaries and preview isolation."""
import copy
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from audit_context_casebook import inventory, protected_files
from build_context_assets import canonical, digest
from build_speech_act_mechanism import compile_mechanism
import test_build_context_assets as assets


class CasebookAssetTests(unittest.TestCase):
    def setUp(self):
        self.fixture = assets.ContextAssetTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.root = self.fixture.fixture.root
        self.unit = self.fixture.unit
        self.runtime, _ = self.fixture.fixture.compile(self.unit)

    def save(self, path, value):
        raw = (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode()
        (self.root / path).write_bytes(raw)
        return {"path": path, "sha256": digest(raw)}

    def mechanism(self):
        inputs, labels, cases = [], [], []
        for i, decision in enumerate(("PASS", "REVIEW_REQUIRED", "PASS")):
            cid = "C" + str(i)
            inputs.append({"caseId": cid, "anchorId": "line", "raw": [{"id": "line", "text": "SYNTHETIC_RAW_SENTINEL", "startMs": 0, "endMs": 100}],
                           "timeBasis": "SYNTHETIC_RELATIVE_NOT_MEDIA"})
            labels.append({"caseId": cid, "expectedDecision": decision, "labelScope": "USER_APPROVED_SYNTHETIC", "userAnswer": "fixture"})
            cases.append({"id": "dev#" + cid, "caseId": cid, "familyId": "synthetic", "evidenceIds": ["line"],
                          "context": {k: "작성 맥락 " + k for k in ("situation", "target", "relation", "requiredCondition", "insufficientCondition", "normalBoundary", "unknown")}})
        return {"schemaVersion": "speech-act-mechanism-plan-1", "status": "AUTHOR_DRAFT_WITH_EXISTING_DEVELOPMENT_LABELS", "independentGold": False,
                "evaluationInputs": self.save("inputs.json", inputs), "evaluationLabels": self.save("labels.json", labels),
                "victimProfile": self.save("profile.json", self.fixture.profile), "victimUnit": self.save("unit.json", self.unit),
                "mechanismReference": {"id": "dev#packet", "familyId": "synthetic", "mechanismIds": ["broad"],
                    "searchText": "작성된 관계 검색", "situation": "작성 상황", "relations": ["관계 설명"],
                    "boundaries": ["정상 경계"], "unknowns": ["독립 검수 미완료"]},
                "packetSupport": [{"field": "relations", "index": 0, "caseIds": ["dev#C0", "dev#C1"]},
                                  {"field": "boundaries", "index": 0, "caseIds": ["dev#C2"]}],
                "cases": cases, "boundaryLinks": [
                    {"normal": "dev#C0", "review": "dev#C1", "difference": "정상과 요청 관계 대조"},
                    {"normal": "dev#C2", "review": "dev#C1", "difference": "다른 정상 관계 대조"},
                    {"normal": self.unit["id"], "review": "dev#C1", "difference": "피해 인용과 현재 실행 대조"}]}

    def test_inventory_preserves_legacy_without_inventing_approval(self):
        incidents = [{"trainingUseAuthorized": False, "statements": [
            {"id": cid, "legacyCard": {"evidence": {"level": "REPORTED_CONTEXT_ONLY", "segments": []}, "review": {"status": "PENDING"}}}
            for cid in ("old", "retained")]}]
        original = copy.deepcopy(self.runtime)
        report = inventory(self.runtime, [self.unit], incidents, self.root)
        self.assertEqual(report["summary"]["examples"], 3)
        self.assertEqual(report["summary"]["migration"]["LEGACY_RETAINED_NEEDS_CONTEXT_CURATION"], 2)
        self.assertEqual(report["summary"]["withContrasts"], 1)
        self.assertEqual(self.runtime, original)
        self.assertFalse(any(row["independentGold"] for row in report["rows"]))

    def test_inventory_rejects_unlinked_legacy(self):
        with self.assertRaises(ValueError): inventory(self.runtime, [self.unit], [], self.root)

    def test_mechanism_is_deterministic_and_raw_labels_are_separate(self):
        plan = self.mechanism()
        original = copy.deepcopy(self.runtime)
        result = compile_mechanism(plan, self.root, self.runtime)
        self.assertEqual(result, compile_mechanism(plan, self.root, self.runtime))
        self.assertEqual(self.runtime, original)
        self.assertEqual(len(result["queries"]), 4)
        self.assertIn("SYNTHETIC_RAW_SENTINEL", canonical(result["evidence"]).decode())
        for key in ("references", "preview-runtime"):
            encoded = canonical(result[key]).decode()
            self.assertNotIn("expectedDecision", encoded)
            self.assertNotIn("SYNTHETIC_RAW_SENTINEL", encoded)
        self.assertFalse(result["references"]["independentGold"])
        self.assertFalse(result["development-use-registry"]["independentHoldoutEligible"])

    def test_mechanism_rejects_bad_link_and_wrong_boundary_polarity(self):
        for change in ("unknown", "polarity", "missing", "evidence", "unapproved"):
            plan = self.mechanism()
            if change == "unknown": plan["boundaryLinks"][0]["normal"] = "outside"
            if change == "polarity": plan["boundaryLinks"][0]["normal"] = "dev#C1"
            if change == "missing": plan["boundaryLinks"] = plan["boundaryLinks"][:1]
            if change == "evidence": plan["cases"][0]["evidenceIds"] = ["outside"]
            if change == "unapproved":
                labels = json.loads((self.root / "labels.json").read_bytes())
                labels[0]["labelScope"] = "MODEL_PREDICTION"
                plan["evaluationLabels"] = self.save("labels.json", labels)
            with self.subTest(change=change), self.assertRaises(ValueError): compile_mechanism(plan, self.root, self.runtime)

    def test_mechanism_rejects_source_tampering(self):
        plan = self.mechanism()
        (self.root / "inputs.json").write_bytes(b"[]")
        with self.assertRaises(ValueError): compile_mechanism(plan, self.root, self.runtime)

    def test_packet_requires_complete_internal_case_links(self):
        for change in ("missing", "foreign", "duplicate", "bool_index"):
            plan = self.mechanism()
            if change == "missing": plan["packetSupport"].pop()
            if change == "foreign": plan["packetSupport"][0]["caseIds"] = ["outside"]
            if change == "duplicate": plan["packetSupport"].append(plan["packetSupport"][0])
            if change == "bool_index": plan["packetSupport"][0]["index"] = False
            with self.subTest(change=change), self.assertRaises(ValueError): compile_mechanism(plan, self.root, self.runtime)

    def test_freeze_detects_changed_common_prompt(self):
        directory = self.root / "oops-backend/src/main/java"
        directory.mkdir(parents=True)
        prompt = directory / "Prompt.java"
        prompt.write_text("frozen")
        before = protected_files(self.root, self.root / "datasets/controversy")
        prompt.write_text("modified")
        self.assertNotEqual(before, protected_files(self.root, self.root / "datasets/controversy"))
