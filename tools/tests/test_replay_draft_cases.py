"""Synthetic offline fixtures; never read real keys, comments or invoke paid APIs."""
import copy
import json
import sys
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import replay_draft_cases as tool
from test_controversy_cards import fixture


class ReplayTests(unittest.TestCase):
    def test_reference_family_holdout_and_no_promotion(self):
        bundle = fixture()[0]
        original = copy.deepcopy(bundle)
        examples = tool.references(bundle, ["synthetic-family"], "held-out-family")
        self.assertEqual(original, bundle)
        self.assertEqual("UNREVIEWED_RESEARCH_ONLY", examples[0]["status"])
        self.assertEqual(1, len(examples[0]["criticismInterpretations"]))
        self.assertNotIn("가상 반응", json.dumps(examples, ensure_ascii=False))
        for families in (["held-out-family"], ["missing"], ["synthetic-family"] * 2):
            with self.assertRaises(tool.PilotError):
                tool.references(bundle, families, "held-out-family")

    def test_current_java_prompts_are_extracted_not_rewritten(self):
        revision, discovery, verification = tool.prompts(tool.ENGINE.read_text())
        self.assertTrue(revision.startswith("2026-"))
        self.assertIn("# 비판 후보 탐색", discovery)
        self.assertIn("# 후보 근거 검증", verification)
        self.assertIn("TARGET_TREATMENT", discovery)
        with self.assertRaises(tool.PilotError):
            tool.prompts("Unknown Java format")

    def test_transcript_preserves_raw_and_rejects_limits(self):
        snapshot = {"schemaVersion": "saved-transcript-replay-input-1", "sourceFamily": "held-out",
                    "transcript": [{"startMs": 0, "endMs": 1, "text": "잘못 전사된 원문"}]}
        self.assertEqual("잘못 전사된 원문", tool.segments(snapshot)[0]["text"])
        snapshot["transcript"] *= 101
        with self.assertRaises(tool.PilotError):
            tool.segments(snapshot)

    def test_discovery_only_accepts_current_raw_quotes_and_complete_coverage(self):
        raw = [{"id": "s1", "text": "가상 원문"}]
        output = {"reviewedSegmentIds": ["s1"], "truncated": False,
                  "candidates": [{"anchorId": "s1", "axis": "TARGET_TREATMENT", "reason": "가설",
                                  "evidence": [{"segmentId": "s1", "quote": "가상 원문"}]}]}
        self.assertEqual(1, len(tool.candidates(output, raw)))
        output["candidates"][0]["evidence"][0]["quote"] = "다른 사례 원문"
        with self.assertRaises(tool.PilotError):
            tool.candidates(output, raw)
        output["reviewedSegmentIds"] = []
        with self.assertRaises(tool.PilotError):
            tool.candidates(output, raw)

    def test_verification_requires_actual_primary_and_alternative(self):
        raw = [{"id": "s1", "text": "가상 원문"}]
        proposed = [{"candidateId": "c1", "anchorId": "s1"}]
        assessment = {"segmentId": "s1", "decision": "REVIEW_REQUIRED", "reason": "이유",
                      "evidenceText": "가상 원문", "alternativeInterpretation": "다른 해석",
                      "evidence": [{"segmentId": "s1", "quote": "가상 원문", "role": "PRIMARY"}]}
        output = {"verifications": [{"candidateId": "c1", "assessment": assessment}]}
        self.assertEqual(1, len(tool.verify(output, proposed, raw)))
        for field, bad in (("alternativeInterpretation", ""), ("evidenceText", "추측 교정")):
            changed = copy.deepcopy(output)
            changed["verifications"][0]["assessment"][field] = bad
            with self.assertRaises(tool.PilotError):
                tool.verify(changed, proposed, raw)

    def test_allowlisted_literal_key_reader_does_not_execute_other_values(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            path.write_text("YOUTUBE_API_KEY=$(must-not-run)\nOPENAI_API_KEY='synthetic-key'\n")
            path.chmod(0o600)
            self.assertEqual("synthetic-key", tool.read_key(path, key_name="OPENAI_API_KEY"))
            with self.assertRaises(tool.CollectionError):
                tool.read_key(path, key_name="UNRELATED_SECRET")

    def test_target_missing_role_is_not_a_usable_discovery_gain(self):
        proposed = [{"candidateId": "c", "axis": "TARGET_TREATMENT"}]
        a = {"decision": "REVIEW_REQUIRED", "target": "가상 장소", "targetMention": "여기",
             "targetReason": "가상 연결", "targetRelation": "CONTEXTUAL",
             "evidence": [{"segmentId": "s1", "quote": "여기", "role": "CONTEXT"}]}
        rows = [{"candidateId": "c", "assessment": a}]
        audit = tool.target_contract_audit(rows, proposed)[0]
        self.assertEqual("TARGET_EVIDENCE_REQUIRED", audit["errorCode"])
        self.assertFalse(audit["targetGuardPassed"])
        a["evidence"][0]["role"] = "TARGET"
        self.assertEqual("TARGET_CONTEXT_EVIDENCE_REQUIRED", tool.target_contract_audit(rows, proposed)[0]["errorCode"])
        a["evidence"].append({"segmentId": "s2", "quote": "가상 평가", "role": "CONTEXT"})
        self.assertTrue(tool.target_contract_audit(rows, proposed)[0]["targetGuardPassed"])

    def test_target_axis_requires_target_but_expression_does_not(self):
        rows = [{"candidateId": "c", "assessment": {"decision": "REVIEW_REQUIRED", "target": None}}]
        self.assertEqual("TARGET_REQUIRED", tool.target_contract_audit(rows, [{"candidateId": "c", "axis": "TARGET_TREATMENT"}])[0]["errorCode"])
        audit = tool.target_contract_audit(rows, [{"candidateId": "c", "axis": "EXPRESSION_CONTENT"}])[0]
        self.assertTrue(audit["targetGuardPassed"])
        self.assertFalse(audit["fullJavaValidationPerformed"])

    def test_provider_errors_never_echo_keys_or_response(self):
        opener = mock.Mock()
        opener.open.side_effect = urllib.error.HTTPError("https://example.org/synthetic-key", 401,
                                                       "sensitive provider message", None, None)
        with mock.patch.object(tool.urllib.request, "build_opener", return_value=opener):
            with self.assertRaisesRegex(tool.PilotError, "^OPENAI_HTTP_401$"):
                tool.complete("synthetic-key", "system", {})
        body = json.loads(opener.open.call_args[0][0].data)
        self.assertEqual("gpt-6-luna", body["model"])
        self.assertEqual(7000, body["max_completion_tokens"])


if __name__ == "__main__":
    unittest.main()
