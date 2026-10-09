import copy
import datetime
import hashlib
import json
import importlib.util
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
SPEC = importlib.util.spec_from_file_location("intake", ROOT / "review_context_intake.py")
tool = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(tool)


def fixture():
    # Synthetic evidence only: no real comments, timing or human approvals.
    return {"schemaVersion": "context-intake-1", "status": "SOURCE_RESEARCH_ONLY",
            "collectionWorkflow": "KNOWN_POINT_THEN_REACTION_MAPPING",
            "counterPolicy": "REFERENCE_ONLY_NO_CANCELLATION", "runtimeEligible": False,
            "trainingUseAuthorized": False, "humanApproved": False,
            "cases": [{"id": "case", "familyId": "family", "split": "DEVELOPMENT",
                       "status": "AWAITING_ORIGINAL_CONTEXT_AND_COMMENTS", "knownPoint": "Synthetic point",
                       "patternHypothesis": "Synthetic hypothesis", "humanApproved": False, "datasetUseAuthorized": False,
                       "sources": [{"id": "source", "url": "https://example.org/report", "role": "DISCOVERY_ONLY"}],
                       "reportedQuotes": [{"text": "Synthetic quote", "sourceId": "source",
                                           "kind": "SOURCE_REPORTED_QUOTE_NOT_TRANSCRIPT", "startMs": None, "endMs": None}],
                       "contextRequirements": ["Full dialogue"], "contextLayers": ["LOCAL_DIALOGUE"],
                       "boundaries": ["Not keyword-only"], "gaps": ["Missing original"],
                       "observedSegments": [], "reactions": [], "reactionMappings": [], "collectionRefs": [],
                       "commentSource": {"videoId": "abcdefghijk", "url": "https://www.youtube.com/watch?v=abcdefghijk",
                                         "discoveredFrom": "https://example.org/report",
                                         "discoveryBasis": "PUBLISHER_PAGE_YOUTUBE_ID_METADATA", "originalClipVerified": False}}]}


class IntakeTests(unittest.TestCase):
    def test_same_incident_cards_share_one_collection(self):
        data = fixture()
        data["cases"].append(dict(copy.deepcopy(data["cases"][0]), id="other-point"))
        result = tool.validate(data)
        self.assertEqual(1, result["incidentFamilies"])
        self.assertEqual(2, result["maximumApiRequestsAtTwoOrders"])
        self.assertFalse(result["collectionExecuted"])

    def test_reported_quote_cannot_have_invented_video_time(self):
        data = fixture()
        data["cases"][0]["reportedQuotes"][0]["startMs"] = 0
        with self.assertRaises(tool.PilotError):
            tool.validate(data)

    def test_report_is_not_a_collected_reaction(self):
        data = fixture()
        data["cases"][0]["reactions"] = [{"text": "Uncollected reaction"}]
        with self.assertRaises(tool.PilotError):
            tool.validate(data)

    def test_missing_youtube_id_stays_pending(self):
        data = fixture()
        data["cases"][0]["commentSource"] = None
        result = tool.validate(data)
        self.assertEqual([], result["uniqueCommentVideoIds"])
        self.assertIsNone(result["suggestedCollectionCommand"])

    def test_approval_and_source_mismatch_are_rejected(self):
        for field in ("runtimeEligible", "sourceMismatch"):
            data = fixture()
            if field == "runtimeEligible":
                data[field] = True
            else:
                data["cases"][0]["commentSource"]["discoveredFrom"] = "https://example.org/unrelated"
            with self.assertRaises(tool.PilotError):
                tool.validate(data)


def mapping_fixture():
    intake = fixture()
    raw = json.dumps({"schemaVersion": "youtube-temporary-review-1", "refreshOrDeleteBy": "2099-01-01T00:00:00+00:00",
                      "comments": [{"videoId": "abcdefghijk", "commentId": "r", "text": "synthetic criticism"}]}).encode()
    draft = {"schemaVersion": "reception-mapping-1", "status": "ASSISTANT_DRAFT_REPORTED_CONTEXT_ONLY",
             "familyId": "family", "split": "DEVELOPMENT", "counterPolicy": "REFERENCE_ONLY_NO_CANCELLATION",
             "runtimeEligible": False, "datasetUseAuthorized": False, "trainingUseAuthorized": False,
             "humanApproved": False, "originalVideoVerified": False,
             "collection": {"sha256": hashlib.sha256(raw).hexdigest(), "refreshOrDeleteBy": "2099-01-01T00:00:00+00:00"},
             "cases": [{"caseId": "case", "contextCoverage": "REPORTED_CONTEXT_ONLY_NOT_VIDEO_TRANSCRIPT",
                        "observedSegments": [], "missingContext": ["Original missing"],
                        "reactions": [{"id": "r1", "videoId": "abcdefghijk", "commentId": "r",
                                       "role": "CRITICISM_SUPPORT", "scope": "SCENE_OR_FLOW", "excerpt": "synthetic criticism",
                                       "criticismReason": "Synthetic", "mappingReason": "Synthetic link",
                                       "sourceIds": ["source"], "unverifiedClaims": []}]}]}
    return intake, draft, raw


class ReceptionMappingTests(unittest.TestCase):
    def test_valid_draft_is_not_semantic_or_video_approval(self):
        intake, draft, raw = mapping_fixture()
        result = tool.validate_mapping(intake, draft, raw)
        self.assertEqual(1, result["uniqueSelectedComments"])
        self.assertFalse(result["semanticMappingVerified"])
        self.assertFalse(result["originalVideoVerified"])

    def test_excerpt_and_card_source_must_match(self):
        for mutation in ("excerpt", "sourceIds", "caseId"):
            intake, draft, raw = mapping_fixture()
            if mutation == "caseId":
                draft["cases"][0][mutation] = "missing"
            else:
                draft["cases"][0]["reactions"][0][mutation] = ["missing"] if mutation == "sourceIds" else "invented"
            with self.assertRaises(tool.PilotError):
                tool.validate_mapping(intake, draft, raw)

    def test_no_video_transcript_or_direct_point_promotion(self):
        for mutation in ("observedSegments", "scope"):
            intake, draft, raw = mapping_fixture()
            if mutation == "scope":
                draft["cases"][0]["reactions"][0]["scope"] = "EXACT_POINT"
            else:
                draft["cases"][0][mutation] = [{"startMs": 0, "rawText": "invented"}]
            with self.assertRaises(tool.PilotError):
                tool.validate_mapping(intake, draft, raw)

    def test_source_hash_and_retention(self):
        intake, draft, raw = mapping_fixture()
        with self.assertRaises(tool.PilotError):
            tool.validate_mapping(intake, draft, raw + b" ")
        with self.assertRaises(tool.PilotError):
            tool.validate_mapping(intake, draft, raw, now=datetime.datetime(2100, 1, 1, tzinfo=datetime.timezone.utc))


class PatternReferenceTests(unittest.TestCase):
    def fixture(self):
        pilot = {"schemaVersion": "context-pilot-2", "status": "ASSISTANT_DRAFT", "familyId": "pilot-family",
                 "split": "DEVELOPMENT", "humanApproved": False, "cases": [{"id": "pilot-a"}, {"id": "pilot-b"}]}
        registry = {"schemaVersion": "context-pattern-hypotheses-1", "status": "DRAFT_NOT_RUNTIME_RULES",
                    "runtimeEligible": False, "humanApproved": False, "modifiers": [],
                    "mechanisms": [{"id": "synthetic", "question": "Synthetic?", "requiredEvidence": ["Context"],
                                    "boundaries": ["No keyword inference"], "transferStatus": "HYPOTHESIS_NOT_VALIDATED",
                                    "cases": [{"caseId": cid, "familyId": "pilot-family", "draftKind": "SELECTED_STT_CONTEXT",
                                               "conditionHypothesis": "Synthetic", "gap": "Unchecked audio"}
                                              for cid in ("pilot-a", "pilot-b")]}]}
        return registry, fixture(), pilot

    def test_same_incident_cards_do_not_inflate_generalization(self):
        registry, intake, pilot = self.fixture()
        report = tool.validate_patterns(registry, intake, pilot)
        self.assertEqual(2, report["patterns"][0]["referencedCards"])
        self.assertEqual(1, report["patterns"][0]["distinctIncidentFamilies"])
        self.assertFalse(report["crossIncidentGeneralizationVerified"])

    def test_wrong_family_or_unknown_case_is_rejected(self):
        for field, value in (("familyId", "other"), ("caseId", "missing"), ("draftKind", "VERIFIED_VIDEO")):
            registry, intake, pilot = self.fixture()
            registry["mechanisms"][0]["cases"][0][field] = value
            with self.assertRaises(tool.PilotError):
                tool.validate_patterns(registry, intake, pilot)

    def test_pattern_cannot_self_approve_runtime_or_transfer(self):
        registry, intake, pilot = self.fixture()
        registry["mechanisms"][0]["transferStatus"] = "VALIDATED"
        with self.assertRaises(tool.PilotError):
            tool.validate_patterns(registry, intake, pilot)


if __name__ == "__main__":
    unittest.main()
