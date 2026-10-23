"""Synthetic-only context asset contracts; no model, network or real labels."""
import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from build_context_assets import canonical, compile_assets, digest
import test_build_context_units as fixtures


class ContextAssetTests(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.ContextUnitTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.unit = self.fixture.standalone()
        self.profile = {"schemaVersion": "context-asset-profile-1", "unitId": self.unit["id"],
                        "unitSha256": digest(canonical(self.unit)), "status": "AUTHOR_DRAFT_NOT_LABEL_APPROVAL",
                        "context": {"situation": "과거 경험 설명", "target": "과거 발언을 들은 사람",
                                    "observedRelations": [{"description": "경험과 반응 설명", "evidenceIds": ["s1", "s2"]}],
                                    "reviewMechanism": "인용과 현재 공격의 혼동",
                                    "requiredConditions": ["현재 공격은 별도 근거 필요"],
                                    "insufficientConditions": ["부정 단어만으로 부족"],
                                    "normalBoundary": "경험 설명", "unknowns": ["원음 미확인"]}}

    def build(self):
        return compile_assets(self.profile, self.unit, self.fixture.root)

    def test_separates_raw_and_label_from_context_and_search(self):
        before = copy.deepcopy(self.unit)
        result = self.build()
        for key in ("reference", "search"):
            encoded = canonical(result[key]).decode()
            self.assertNotIn("평가 표현", encoded)
            self.assertNotIn("developmentDecision", encoded)
            self.assertNotIn("source.json", encoded)
        self.assertEqual(result["evidence"]["segments"][0]["text"], "평가 표현")
        self.assertEqual(self.unit, before)
        self.assertEqual(result, self.build())

    def test_rejects_missing_boundary_foreign_evidence_and_unknown_field(self):
        for change in ("boundary", "evidence", "field", "status", "hash"):
            original = copy.deepcopy(self.profile)
            if change == "boundary": self.profile["context"]["insufficientConditions"] = []
            if change == "evidence": self.profile["context"]["observedRelations"][0]["evidenceIds"] = ["outside"]
            if change == "field": self.profile["context"]["rawQuotes"] = ["quote"]
            if change == "status": self.profile["status"] = "APPROVED"
            if change == "hash": self.profile["unitSha256"] = "0" * 64
            with self.subTest(change=change), self.assertRaises(ValueError): self.build()
            self.profile = original

    def test_source_tampering_is_rejected(self):
        (self.fixture.root / "source.json").write_bytes(b"changed")
        with self.assertRaises(ValueError): self.build()
