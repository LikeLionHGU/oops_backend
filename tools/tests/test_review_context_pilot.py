import copy
import datetime
import hashlib
import importlib.util
import json
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("pilot", Path(__file__).resolve().parents[1] / "review_context_pilot.py")
tool = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(tool)


def fixture():
    # Synthetic source and claims only. No real comments or human approvals in tests.
    raw = json.dumps({"schemaVersion": "youtube-temporary-review-1", "refreshOrDeleteBy": "2099-01-01T00:00:00+00:00",
                      "comments": [{"videoId": "video", "commentId": "comment", "text": "synthetic criticism"}]}).encode()
    draft = {"schemaVersion": "context-pilot-2", "collectionWorkflow": "KNOWN_POINT_THEN_REACTION_MAPPING",
             "version": "test", "status": "ASSISTANT_DRAFT", "familyId": "synthetic",
             "split": "DEVELOPMENT", "datasetUseAuthorized": False, "trainingUseAuthorized": False, "humanApproved": False,
             "counterPolicy": "REFERENCE_ONLY_NO_CANCELLATION",
             "collection": {"sha256": hashlib.sha256(raw).hexdigest(), "refreshOrDeleteBy": "2099-01-01T00:00:00+00:00"},
             "cases": [{"id": "synthetic", "status": "DRAFT", "knownControversyPoint": "Synthetic point", "pointBasis": "Synthetic",
                        "windowMs": [0, 1000], "anchorSegmentId": "s1",
                        "segments": [{"id": "s1", "startMs": 0, "endMs": 1000, "rawText": "Synthetic speech"}],
                        "contextChain": [{"claim": "Synthetic relation", "segmentIds": ["s1"]}],
                        "reactions": [{"id": "r1", "videoId": "video", "commentId": "comment", "role": "CRITICISM_SUPPORT",
                                       "scope": "EXACT_POINT", "excerpt": "synthetic criticism", "claim": "Synthetic interpretation"}],
                        "contextCoverage": "SELECTED_EXCERPTS_NOT_FULL_TRANSCRIPT", "missingContext": ["Audio not checked"],
                        "reactionMappings": [{"reactionId": "r1", "segmentIds": ["s1"],
                                              "connectionReason": "Synthetic connection", "unverifiedClaims": []}],
                        "generalPattern": {"id": "SYNTHETIC", "description": "Synthetic", "conditions": ["Synthetic"],
                                           "boundaries": ["Synthetic"], "additionalEvidenceNeeded": ["Synthetic"],
                                           "transferStatus": "HYPOTHESIS_NOT_VALIDATED"}}]}
    return draft, raw


class PilotTests(unittest.TestCase):
    def test_valid_draft_does_not_certify_semantics_or_approval(self):
        draft, raw = fixture()
        result = tool.validate(draft, raw)
        self.assertTrue(result["valid"])
        self.assertFalse(result["semanticLinkingVerified"])
        self.assertEqual(0, result["humanApprovedCases"])

    def test_source_identity_and_exact_quote_checks(self):
        for name, value in (("commentId", "missing"), ("videoId", "wrong"), ("excerpt", "invented")):
            draft, raw = fixture()
            draft["cases"][0]["reactions"][0][name] = value
            with self.assertRaises(tool.PilotError):
                tool.validate(draft, raw)

    def test_reference_counter_does_not_remove_criticism(self):
        draft, raw = fixture()
        draft["cases"][0]["reactions"].append(dict(copy.deepcopy(draft["cases"][0]["reactions"][0]),
                                                   id="r2", role="COUNTER_REFERENCE_ONLY", scope="INCIDENT_ONLY"))
        draft["cases"][0]["reactionMappings"].append({"reactionId": "r2", "segmentIds": [],
                                                       "connectionReason": "Reference only", "unverifiedClaims": []})
        result = tool.validate(draft, raw)["cases"][0]
        self.assertEqual(1, result["criticisms"])
        self.assertEqual(1, result["referenceCounters"])
        draft["counterPolicy"] = "MAJORITY_VOTE"
        with self.assertRaises(tool.PilotError):
            tool.validate(draft, raw)

    def test_incident_reaction_is_not_counted_as_exact_point(self):
        draft, raw = fixture()
        draft["cases"][0]["reactions"][0]["scope"] = "INCIDENT_ONLY"
        draft["cases"][0]["reactionMappings"][0]["segmentIds"] = []
        self.assertEqual(0, tool.validate(draft, raw)["cases"][0]["exactPointCriticisms"])

    def test_changed_source_and_expired_snapshot_fail(self):
        draft, raw = fixture()
        with self.assertRaises(tool.PilotError):
            tool.validate(draft, raw + b" ")
        with self.assertRaises(tool.PilotError):
            tool.validate(draft, raw, now=datetime.datetime(2100, 1, 1, tzinfo=datetime.timezone.utc))

    def test_no_unbacked_context_or_premature_approval(self):
        draft, raw = fixture()
        draft["cases"][0]["contextChain"][0]["segmentIds"] = ["invented"]
        with self.assertRaises(tool.PilotError):
            tool.validate(draft, raw)
        draft, raw = fixture()
        draft["humanApproved"] = True
        with self.assertRaises(tool.PilotError):
            tool.validate(draft, raw)

    def test_mapping_requires_all_reactions_and_real_segment_ids(self):
        for mutation in ("missing", "unknown_segment", "unknown_reaction"):
            draft, raw = fixture()
            mapping = draft["cases"][0]["reactionMappings"]
            if mutation == "missing":
                mapping.clear()
            elif mutation == "unknown_segment":
                mapping[0]["segmentIds"] = ["missing"]
            else:
                mapping[0]["reactionId"] = "missing"
            with self.assertRaises(tool.PilotError):
                tool.validate(draft, raw)

    def test_incident_scope_cannot_be_promoted_to_segment_evidence(self):
        draft, raw = fixture()
        draft["cases"][0]["reactions"][0]["scope"] = "INCIDENT_ONLY"
        with self.assertRaisesRegex(tool.PilotError, "MAPPING_SCOPE_BOUNDARY"):
            tool.validate(draft, raw)

    def test_handoff_keeps_raw_context_and_separates_comment_claims(self):
        draft, raw = fixture()
        case = draft["cases"][0]
        case["windowMs"] = [0, 2000]
        case["segments"].append({"id": "s2", "startMs": 1000, "endMs": 2000, "rawText": "Required continuation"})
        case["reactionMappings"][0]["unverifiedClaims"] = ["Provider present (comment claim)"]
        packet = tool.context_handoff(draft, "synthetic", raw)
        self.assertEqual(case["segments"], packet["videoEvidence"]["segments"])
        self.assertEqual(case["contextChain"], packet["interpretationHypotheses"])
        self.assertEqual(case["missingContext"], packet["missingContext"])
        self.assertFalse(packet["runtimeEligible"])
        self.assertNotIn("unverifiedClaims", packet["videoEvidence"])
        packet["videoEvidence"]["segments"][0]["rawText"] = "changed"
        self.assertEqual("Synthetic speech", case["segments"][0]["rawText"])

    def test_handoff_cannot_use_expired_source_or_missing_case(self):
        draft, raw = fixture()
        with self.assertRaises(tool.PilotError):
            tool.context_handoff(draft, "missing", raw)
        with self.assertRaises(tool.PilotError):
            tool.context_handoff(draft, "synthetic", raw, now=datetime.datetime(2100, 1, 1, tzinfo=datetime.timezone.utc))


if __name__ == "__main__":
    unittest.main()
