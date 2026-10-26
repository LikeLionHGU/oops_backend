"""Offline contract tests for source-pinned, non-runtime mechanism drafts."""
import copy
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from verify_cross_case_mechanism_review import audit


def write_json(root, path, value):
    target = root / path
    target.parent.mkdir(parents=True, exist_ok=True)
    raw = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    target.write_bytes(raw)
    return hashlib.sha256(raw).hexdigest()


class CrossCaseReviewAuditTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.rows = [
            {"startMs": 0, "endMs": 1000, "text": "합성 대화 첫 문장"},
            {"startMs": 1000, "endMs": 2000, "text": "합성 대화 다음 문장"},
        ]
        self.transcript_sha = write_json(self.root, "experiments/transcript.json", {"data": self.rows})
        self.summary_sha = write_json(self.root, "experiments/run.json", {"videoId": 7, "youtubeId": "fixture-video"})
        self.case_sha = write_json(self.root, "incidents/case.json", {"caseId": "dev#case"})
        self.captions_sha = write_json(self.root, "experiments/captions.json", {
            "events": [{"tStartMs": 1000, "dDurationMs": 500,
                        "segs": [{"utf8": "구체적인 실기 피드백"}]}],
        })
        self.comparison_sha = write_json(self.root, "experiments/comparison.json", {
            "videoId": "8", "sourceId": "other-video", "userGoldLabels": False,
            "points": [{"id": "B02", "serviceOutcome": "REVIEW_REQUIRED", "eventIds": ["event-177"]}],
        })
        self.draft = {
            "schemaVersion": "cross-case-mechanism-review-2",
            "id": "fixture-review",
            "status": "DRAFT_RESEARCH_ONLY_NOT_RUNTIME",
            "sourceBindings": [
                {"id": "case", "path": "incidents/case.json", "sha256": self.case_sha, "format": "JSON"},
                {"id": "transcript", "path": "experiments/transcript.json", "sha256": self.transcript_sha, "format": "JSON"},
                {"id": "run", "path": "experiments/run.json", "sha256": self.summary_sha, "format": "JSON"},
                {"id": "captions", "path": "experiments/captions.json", "sha256": self.captions_sha, "format": "JSON"},
                {"id": "comparison", "path": "experiments/comparison.json", "sha256": self.comparison_sha, "format": "JSON"},
            ],
            "recordChecks": [
                {"bindingId": "case", "jsonPointer": "/caseId", "equals": "dev#case"},
            ],
            "contextUnitLinks": [],
            "approvalChecks": [],
            "lineChecks": [
                {"bindingId": "transcript", "arrayPointer": "/data", "index": 0,
                 "segmentId": "stt-index-0", "startMs": 0, "endMs": 1000,
                 "text": "합성 대화 첫 문장"},
            ],
            "caseLinks": [{"caseId": "dev#case", "assetBindingIds": ["case"]}],
            "researchCluster": {
                "status": "TAXONOMIC_GROUPING_ONLY_NOT_A_SINGLE_DETECTOR_RULE",
                "subpatterns": [{"caseIds": ["dev#case"]}],
            },
            "normalContrastCandidate": {
                "sourceBindingId": "transcript", "runBindingId": "run",
                "videoId": 7, "youtubeVideoId": "fixture-video",
                "status": "PENDING_HUMAN_REVIEW_NOT_A_LABEL", "expectedDecision": None,
                "trainingUseAuthorized": False, "goldEvaluationUseAuthorized": False,
                "humanReview": None,
                "segments": [
                    {"sourceIndex": 0, "id": "stt-index-0", "startMs": 0, "endMs": 1000,
                     "text": "합성 대화 첫 문장"},
                    {"sourceIndex": 1, "id": "stt-index-1", "startMs": 1000, "endMs": 2000,
                     "text": "합성 대화 다음 문장"},
                ],
            },
        "crossVideoNormalControl": {
            "sourceBindingId": "captions", "historicalComparisonBindingId": "comparison",
            "videoId": 8, "youtubeVideoId": "other-video", "timeRangeMs": [900, 1600],
            "status": "HUMAN_REVIEWED_DEVELOPMENT_NORMAL_CONTRAST", "expectedDecision": "PASS",
            "humanReview": {
                "decision": "PASS", "reviewer": "USER_APPROVED_IN_CHAT", "reviewedAt": None,
                "scope": "DEVELOPMENT_NORMAL_CONTRAST_ONLY", "sourceBasis": "PUBLIC_KOREAN_CAPTIONS",
                "audioVerified": False, "recordedResponse": "PASS",
            },
            "historicalServiceOutcome": {
                "decision": "REVIEW_REQUIRED", "eventIds": ["event-177"],
                "versionScope": "HISTORICAL_RUN_NOT_CURRENT_BASELINE", "userGoldLabelsAtRun": False,
            },
            "trainingUseAuthorized": False, "goldEvaluationUseAuthorized": False,
            "cueChecks": [{"eventIndex": 0, "tStartMs": 1000, "dDurationMs": 500,
                           "text": "구체적인 실기 피드백"}],
        },
            "promotion": {
                "runtimeChange": False, "promptChange": False,
                "existingMechanismIdsChanged": False, "trainingUseAuthorized": False,
                "reason": "fixture",
            },
        }

    def tearDown(self):
        self.temp.cleanup()

    def test_source_links_and_unlabelled_control_pass(self):
        result = audit(self.root, self.draft)
        self.assertEqual(result["sourceBindingsVerified"], 5)
        self.assertEqual(result["normalCandidateSegmentsVerified"], 2)
        self.assertFalse(result["expectedDecisionPresent"])
        self.assertFalse(result["humanDecisionRecorded"])
        self.assertFalse(result["runtimeChanged"])
        self.assertEqual(result["crossVideoNormalControlCuesVerified"], 1)
        self.assertEqual(result["crossVideoHistoricalOutcome"], "REVIEW_REQUIRED")

    def test_source_change_requires_recheck(self):
        write_json(self.root, "experiments/transcript.json", {"data": [{"text": "바뀐 원문"}]})
        with self.assertRaisesRegex(ValueError, "REVIEW_SOURCE_HASH_CHANGED"):
            audit(self.root, self.draft)

    def test_user_approved_development_contrast_is_provenanced_but_not_promoted(self):
        draft = copy.deepcopy(self.draft)
        candidate = draft["normalContrastCandidate"]
        candidate.update({
            "status": "HUMAN_REVIEWED_DEVELOPMENT_NORMAL_CONTRAST",
            "expectedDecision": "PASS",
            "humanReview": {
                "decision": "PASS", "reviewer": "USER_APPROVED_IN_CHAT",
                "reviewedAt": "2026-10-26", "scope": "DEVELOPMENT_NORMAL_CONTRAST_ONLY",
                "sourceBasis": "SAVED_STT_ONLY", "audioVerified": False,
                "recordedResponse": "PASS — 정상 대조로 적합",
            },
        })
        result = audit(self.root, draft)
        self.assertTrue(result["expectedDecisionPresent"])
        self.assertTrue(result["humanDecisionRecorded"])
        self.assertFalse(result["goldEvaluationUseAuthorized"])

    def test_reviewed_contrast_cannot_authorize_gold_evaluation_or_training(self):
        draft = copy.deepcopy(self.draft)
        candidate = draft["normalContrastCandidate"]
        candidate.update({
            "status": "HUMAN_REVIEWED_DEVELOPMENT_NORMAL_CONTRAST",
            "expectedDecision": "PASS",
            "humanReview": {
                "decision": "PASS", "reviewer": "USER_APPROVED_IN_CHAT",
                "reviewedAt": "2026-10-26", "scope": "DEVELOPMENT_NORMAL_CONTRAST_ONLY",
                "sourceBasis": "SAVED_STT_ONLY", "audioVerified": False,
                "recordedResponse": "PASS — 정상 대조로 적합",
            },
        })
        candidate["goldEvaluationUseAuthorized"] = True
        with self.assertRaisesRegex(ValueError, "REVIEW_NORMAL_CANDIDATE_USE_SCOPE_INVALID"):
            audit(self.root, draft)

    def test_cross_video_control_must_match_caption_provenance(self):
        draft = copy.deepcopy(self.draft)
        draft["crossVideoNormalControl"]["cueChecks"][0]["text"] = "조작된 자막"
        with self.assertRaisesRegex(ValueError, "REVIEW_CROSS_VIDEO_CUE_SOURCE_MISMATCH"):
            audit(self.root, draft)

    def test_cross_video_control_must_not_replay_anchor_source(self):
        draft = copy.deepcopy(self.draft)
        draft["crossVideoNormalControl"]["videoId"] = 7
        with self.assertRaisesRegex(ValueError, "REVIEW_CROSS_VIDEO_NOT_INDEPENDENT"):
            audit(self.root, draft)

    def test_normal_candidate_requires_contiguous_source_context(self):
        draft = copy.deepcopy(self.draft)
        expanded_rows = self.rows + [{"startMs": 2000, "endMs": 3000, "text": "합성 대화 세 번째 문장"}]
        new_sha = write_json(self.root, "experiments/transcript.json", {"data": expanded_rows})
        draft["sourceBindings"][1]["sha256"] = new_sha
        draft["normalContrastCandidate"]["segments"][1] = {
            "sourceIndex": 2, "id": "stt-index-2", "startMs": 2000,
            "endMs": 3000, "text": "합성 대화 세 번째 문장"}
        with self.assertRaisesRegex(ValueError, "REVIEW_NORMAL_CONTEXT_HAS_GAPS"):
            audit(self.root, draft)

    def test_case_identifier_mismatch_is_rejected(self):
        draft = copy.deepcopy(self.draft)
        draft["recordChecks"][0]["equals"] = "another-case"
        with self.assertRaisesRegex(ValueError, "REVIEW_RECORD_CHECK_MISMATCH"):
            audit(self.root, draft)


if __name__ == "__main__":
    unittest.main()
