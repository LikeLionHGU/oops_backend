"""Synthetic cases only; common-card tests do not require local datasets."""
import copy
import hashlib
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import controversy_cards as tool


def fixture():
    incident = {"familyId": "synthetic-family", "split": "DEVELOPMENT", "collectionId": "collection",
                "privateDraft": "synthetic-draft.json", "evidenceLevel": "REPORTED_CONTEXT_ONLY"}
    reactions = []
    stages = {"r1": "CONTENT", "r2": "POST_CONTROVERSY_RESPONSE", "r3": "CONTENT", "r4": "UNKNOWN"}
    for rid in stages:
        reactions.append({"id": rid, "videoId": "synthetic-video", "commentId": rid,
                          "role": "COUNTER_REFERENCE_ONLY" if rid == "r3" else "CRITICISM_SUPPORT",
                          "scope": "SCENE_OR_FLOW", "excerpt": "가상 반응 " + rid,
                          "criticismReason": "Synthetic interpretation", "mappingReason": "Synthetic connection",
                          "sourceIds": ["source"], "unverifiedClaims": ["Synthetic unverified scene claim"]})
    draft = {"schemaVersion": "reception-mapping-1", "cases": [
        {"caseId": "synthetic-card", "contextCoverage": "REPORTED_CONTEXT_ONLY_NOT_VIDEO_TRANSCRIPT",
         "missingContext": ["Original unavailable"], "reactions": reactions}]}
    raw = json.dumps({"comments": [{"videoId": "synthetic-video", "commentId": r["id"],
                                    "text": "😀 앞 " + r["excerpt"] + " 뒤", "publishedAt": None} for r in reactions]}).encode()
    draft_bytes = json.dumps(draft).encode()
    intake = {"cases": [{"id": "synthetic-card", "knownPoint": "Synthetic known point",
                          "sources": [{"id": "source", "url": "https://example.org"}],
                          "reportedQuotes": [{"sourceId": "source", "text": "Synthetic reported quote",
                                              "kind": "SOURCE_REPORTED_QUOTE_NOT_TRANSCRIPT", "startMs": None, "endMs": None}],
                          "boundaries": ["Not keyword detection"]}]}
    plan = {"reactions": {"synthetic-family": {rid: {"stage": stage, "basis": "Synthetic draft classification"}
                                               for rid, stage in stages.items()}}}
    converted = tool.convert(incident, draft_bytes, raw, intake, plan)
    bundle = {"schemaVersion": "controversy-cards-1", "status": "UNREVIEWED_DRAFT",
              "counterPolicy": "REFERENCE_ONLY_NO_CANCELLATION", "runtimeEligible": False,
              "trainingUseAuthorized": False, "humanApproved": False,
              "collections": [{"id": "collection"}], "incidents": [converted]}
    return bundle, incident, draft_bytes, raw, intake, plan


class CommonCardTests(unittest.TestCase):
    def setUp(self):
        self.bundle, self.incident, self.draft, self.raw, self.intake, self.plan = fixture()
        self.card = self.bundle["incidents"][0]["cards"][0]

    def test_common_structure_preserves_source_and_unknowns(self):
        report = tool.validate_bundle(self.bundle)
        self.assertEqual(4, report["reactions"])
        self.assertFalse(report["runtimeEligible"])
        self.assertEqual(hashlib.sha256(self.draft).hexdigest(), self.card["provenance"]["legacySha256"])
        self.assertEqual(self.intake["cases"][0]["reportedQuotes"], self.card["evidence"]["reportedQuotes"])
        self.assertIsNone(self.card["context"]["target"])
        self.assertIsNone(self.card["evidence"]["windowMs"])

    def test_unicode_span_and_comment_claims_preserved(self):
        r = self.card["reactions"][0]
        self.assertEqual(4, r["excerpt"]["startCodePoint"])
        raw_text = tool.parse(self.raw)["comments"][0]["text"]
        self.assertEqual(r["excerpt"]["text"], raw_text[r["excerpt"]["startCodePoint"]:r["excerpt"]["endCodePoint"]])
        self.assertEqual(["Synthetic unverified scene claim"], r["unverifiedCommentClaims"])

    def test_post_response_counter_and_unknown_not_reason_support(self):
        self.assertEqual(["r1"], self.card["controversy"]["reasonSupportReactionIds"])
        self.assertEqual("POST_RESPONSE_RESEARCH", self.card["reactions"][1]["usage"]["purpose"])
        self.card["controversy"]["reasonSupportReactionIds"].append("r2")
        with self.assertRaises(tool.PilotError):
            tool.validate_bundle(self.bundle)

    def test_missing_classification_does_not_guess_stage(self):
        del self.plan["reactions"]["synthetic-family"]["r1"]
        with self.assertRaises(tool.PilotError):
            tool.convert(self.incident, self.draft, self.raw, self.intake, self.plan)

    def test_no_self_approval_or_reaction_promotion(self):
        for key in ("humanApproved", "trainingUseAuthorized", "runtimeEligible"):
            bundle = copy.deepcopy(self.bundle)
            bundle[key] = True
            with self.assertRaises(tool.PilotError):
                tool.validate_bundle(bundle)
        self.card["reactions"][0]["usage"]["detectionEvidenceEligible"] = True
        with self.assertRaises(tool.PilotError):
            tool.validate_bundle(self.bundle)

    def test_no_invented_quote_times_target_or_direct_mapping(self):
        for mode in ("time", "target", "scope"):
            bundle = copy.deepcopy(self.bundle)
            card = bundle["incidents"][0]["cards"][0]
            if mode == "time":
                card["evidence"]["reportedQuotes"][0]["startMs"] = 1
            elif mode == "target":
                card["context"]["target"] = "Invented target"
            else:
                card["reactions"][0]["mapping"]["scope"] = "INCIDENT_ONLY"
            with self.assertRaises(tool.PilotError):
                tool.validate_bundle(bundle)

    def test_reference_purpose_cannot_be_content_support(self):
        self.card["reactions"][2]["usage"]["purpose"] = "CONTENT_REACTION_RESEARCH"
        with self.assertRaises(tool.PilotError):
            tool.validate_bundle(self.bundle)

    def test_reason_text_must_match_referenced_reaction_interpretation(self):
        self.card["controversy"]["reasonHypotheses"][0]["text"] = "Invented reason"
        with self.assertRaises(tool.PilotError):
            tool.validate_bundle(self.bundle)


if __name__ == "__main__":
    unittest.main()
