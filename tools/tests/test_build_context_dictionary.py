"""Three-layer drafts: synthetic source strings only; no paid model calls."""
import copy
import hashlib
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build_context_dictionary as tool
from test_controversy_cards import fixture


class ContextDictionaryTests(unittest.TestCase):
    def setUp(self):
        self.bundle = fixture()[0]
        self.bundle["collections"][0]["refreshOrDeleteBy"] = "2099-01-01T00:00:00+00:00"
        self.plan = {"schemaVersion": "context-dictionary-plan-1", "status": "ASSISTANT_DRAFT", "humanApproved": False,
                     "cards": {"synthetic-card": {"topicTags": ["합성 주제"], "mechanismIds": ["mechanism"], "documents": []}}}
        self.guides = {"status": "WORKING_REFERENCE_NOT_VALIDATED", "humanValidated": False,
                       "mechanisms": [{"id": "mechanism", "sourceCaseIds": ["synthetic-card"], "normalContrast": "합성 적용 경계"}]}
        self.raw = "😀 가상 출처 문장\n".encode()
        self.doc = {"id": "doc", "sourceId": "source", "materialKind": "NEWS_BODY", "snapshot": "snapshot.txt",
                    "sha256": hashlib.sha256(self.raw).hexdigest(), "startCodePoint": 2, "endCodePoint": 10,
                    "excerpt": "가상 출처 문장", "statementKind": "AUTHOR_INTERPRETATION", "originGroupId": None,
                    "locationHint": "합성 본문", "snapshotCoverage": "SHORT_EXTRACT_NOT_FULL_ARTICLE", "retrievedOn": "2026-10-10"}

    def build(self, snapshots=None):
        return tool.compile_dictionary(self.bundle, self.plan, self.guides, b"source", snapshots)

    def test_separate_layers_no_raw_comment_duplication_or_approval(self):
        before = copy.deepcopy(self.bundle)
        result = self.build()
        card = result["entries"][0]
        self.assertEqual(before, self.bundle)
        reception = card["audienceReception"]
        self.assertEqual(["r1"], [r["reactionId"] for r in reception["pointReasonUnits"]])
        self.assertEqual(["r2", "r4"], [r["reactionId"] for r in reception["incidentOrOtherReactions"]])
        self.assertEqual(["r3"], [r["reactionId"] for r in reception["counterReferenceOnly"]])
        self.assertFalse(reception["counterCancelsCriticism"])
        self.assertNotIn("가상 반응", str(result))
        self.assertEqual([], card["interpretationMaterial"]["sourceExtracts"])
        self.assertIsNone(card["interpretationMaterial"]["independentEvidenceCount"])
        self.assertEqual([], card["normalComparison"]["observedCases"])
        self.assertFalse(card["review"]["groundTruth"])
        self.assertEqual("2099-01-01T00:00:00+00:00", result["refreshOrDeleteBy"])

    def test_integrity_check_is_not_truth_or_video_evidence(self):
        self.plan["cards"]["synthetic-card"]["documents"] = [self.doc]
        result = self.build({"snapshot.txt": self.raw})
        extract = result["entries"][0]["interpretationMaterial"]["sourceExtracts"][0]
        self.assertEqual("EXTRACT_MATCHED_NOT_FACT_VERIFIED", extract["status"])
        self.assertFalse(extract["humanVerified"])
        self.assertFalse(extract["groundTruth"])
        self.assertEqual([], result["entries"][0]["utteranceEvidence"]["segments"])

    def test_bad_hash_span_source_and_kind_rejected(self):
        for field, value in (("sha256", "0" * 64), ("endCodePoint", 11), ("excerpt", "발명한 문장"),
                             ("statementKind", "GROUND_TRUTH"), ("sourceId", "absent"), ("materialKind", "COMMENT")):
            document = {**self.doc, field: value}
            self.plan["cards"]["synthetic-card"]["documents"] = [document]
            with self.subTest(field=field), self.assertRaises(tool.PilotError):
                self.build({"snapshot.txt": self.raw})

    def test_cannot_omit_cards_or_invent_taxonomy_mapping(self):
        for change in ("missing", "mechanism", "approval"):
            plan = copy.deepcopy(self.plan)
            if change == "missing": plan["cards"] = {}
            if change == "mechanism": plan["cards"]["synthetic-card"]["mechanismIds"] = ["absent"]
            if change == "approval": plan["humanApproved"] = True
            with self.subTest(change=change), self.assertRaises(tool.PilotError):
                tool.compile_dictionary(self.bundle, plan, self.guides, b"source")

    def test_same_origin_is_not_multiple_independent_proofs(self):
        self.plan["cards"]["synthetic-card"]["sourceOriginGroups"] = {"source": "syndicated"}
        card = self.build()["entries"][0]
        link = card["interpretationMaterial"]["researchLinks"][0]
        self.assertEqual("syndicated", link["originGroupId"])
        self.assertFalse(link["independenceVerified"])
        self.assertIsNone(card["interpretationMaterial"]["independentEvidenceCount"])

    def test_no_reason_loss_after_removing_incident_only_scope(self):
        card = self.bundle["incidents"][0]["cards"][0]
        card["reactions"][0]["mapping"] = {"scope": "INCIDENT_ONLY", "segmentIds": [], "sourceIds": [],
                                            "reason": "사건 전체 반응", "humanVerified": False}
        reception = self.build()["entries"][0]["audienceReception"]
        self.assertEqual([], reception["pointReasonUnits"])
        self.assertEqual(4, sum(len(reception[k]) for k in ("pointReasonUnits", "incidentOrOtherReactions", "counterReferenceOnly")))

    def test_new_research_sources_do_not_mutate_utterance_provenance(self):
        config = self.plan["cards"]["synthetic-card"]
        config["additionalResearchSources"] = [{"id": "new", "url": "https://example.org/new",
                                                 "publisher": "Synthetic", "role": "DISCOVERY_ONLY"}]
        config["documents"] = [{**self.doc, "sourceId": "new"}]
        card = self.build({"snapshot.txt": self.raw})["entries"][0]
        self.assertEqual(2, len(card["interpretationMaterial"]["researchLinks"]))
        self.assertEqual(1, len(card["utteranceEvidence"]["sources"]))
        config["additionalResearchSources"][0]["url"] = "file:///private"
        with self.assertRaisesRegex(tool.PilotError, "ADDITIONAL_SOURCE_URL_REQUIRED"):
            self.build({"snapshot.txt": self.raw})

    def test_contradictory_origin_groups_rejected(self):
        config = self.plan["cards"]["synthetic-card"]
        config["sourceOriginGroups"] = {"source": "one-origin"}
        config["documents"] = [{**self.doc, "originGroupId": "different-origin"}]
        with self.assertRaisesRegex(tool.PilotError, "SOURCE_ORIGIN_GROUP_CONFLICT"):
            self.build({"snapshot.txt": self.raw})


if __name__ == "__main__":
    unittest.main()
