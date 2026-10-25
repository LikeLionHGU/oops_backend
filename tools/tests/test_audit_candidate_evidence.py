"""Synthetic source/roles only. No semantic labels are created or promoted."""
import hashlib
import json
import sys
import unittest
import tempfile
from unittest.mock import patch
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from audit_candidate_evidence import audit, main


class CandidateEvidenceAuditTests(unittest.TestCase):
    def setUp(self):
        self.source = json.dumps({"segments": [
            {"startMs": 0, "endMs": 1000, "text": "상황"},
            {"startMs": 1000, "endMs": 2000, "text": "대응"},
            {"startMs": 2000, "endMs": 3000, "text": "뒤의 설명"}]}).encode()
        raw = [{"id": "stt-index-" + str(i), **r} for i, r in enumerate(json.loads(self.source)["segments"])]
        self.roles = {"schemaVersion": "candidate-evidence-role-map-1", "caseId": "synthetic",
                      "basis": "AUTHORED_STRUCTURAL_ANNOTATION_NOT_SEMANTIC_GOLD",
                      "sourceSha256": hashlib.sha256(self.source).hexdigest(), "roles": [
                          {"id": "situation", "label": "상황", "segmentIds": ["stt-index-0"]},
                          {"id": "response", "label": "대응", "segmentIds": ["stt-index-1"]},
                          {"id": "explanation", "label": "설명", "segmentIds": ["stt-index-2"]}]}
        e = [{"segmentId": "stt-index-1"}]
        self.saved = {"state": "COMPLETE", "results": [{"caseId": "synthetic", "mode": "AUTO", "discoveryInjected": False,
                      "calls": [{"kind": "Discovery", "output": {"candidates": [
                          {"anchorId": "stt-index-1", "axis": "EXPRESSION_CONTENT", "evidence": e}]}},
                          {"kind": "VerificationResult", "input": {"raw": raw, "candidates": [
                              {"candidateId": "c", "anchorId": "stt-index-1", "axis": "EXPRESSION_CONTENT",
                               "segmentIds": list(r["id"] for r in raw), "proposedEvidence": e}]},
                           "output": {"verifications": [{"candidateId": "c", "assessment": {
                               "decision": "PASS", "evidence": e}}]}}]}]}

    def run_audit(self):
        return audit(self.saved, self.roles, self.source)

    def test_distinguishes_available_but_unselected_from_unavailable_without_judging(self):
        report = self.run_audit()
        row = report["requests"][0]
        self.assertTrue(row["discoveryToVerificationEvidenceIdsPreserved"])
        self.assertEqual(row["proposedRoles"][0]["availableButUnselectedIds"], ["stt-index-0"])
        self.assertEqual(row["proposedRoles"][2]["outsidePermittedWindowIds"], [])
        self.assertFalse(report["roleNecessityValidated"])
        self.assertFalse(report["runtimeDecisionModified"])
        self.saved["results"][0]["calls"][1]["output"]["verifications"][0]["assessment"]["decision"] = "REVIEW_REQUIRED"
        self.assertEqual(self.run_audit()["requests"][0]["proposedRoles"], row["proposedRoles"])

    def test_detects_window_omission_separately(self):
        self.saved["results"][0]["calls"][1]["input"]["candidates"][0]["segmentIds"] = ["stt-index-1"]
        rows = self.run_audit()["requests"][0]["proposedRoles"]
        self.assertEqual(rows[0]["outsidePermittedWindowIds"], ["stt-index-0"])
        self.assertEqual(rows[0]["availableButUnselectedIds"], [])

    def test_rejects_wrong_source_hash_or_raw_text(self):
        self.roles["sourceSha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "SOURCE_HASH_MISMATCH"):
            self.run_audit()
        self.roles["sourceSha256"] = hashlib.sha256(self.source).hexdigest()
        self.saved["results"][0]["calls"][1]["input"]["raw"][0]["text"] = "임의 교정"
        with self.assertRaisesRegex(ValueError, "RAW_SOURCE_MISMATCH"):
            self.run_audit()

    def test_rejects_evidence_outside_window_and_unknown_role_ids(self):
        self.roles["roles"][0]["segmentIds"] = ["unknown"]
        with self.assertRaisesRegex(ValueError, "ROLE_OUTSIDE_SOURCE"):
            self.run_audit()
        self.roles["roles"][0]["segmentIds"] = ["stt-index-0"]
        self.saved["results"][0]["calls"][1]["input"]["candidates"][0]["segmentIds"] = ["stt-index-0"]
        with self.assertRaises(ValueError):
            self.run_audit()

    def test_deduplicates_returned_segment_roles_without_inflating_coverage(self):
        a = self.saved["results"][0]["calls"][1]["output"]["verifications"][0]["assessment"]
        a["evidence"] *= 2
        self.assertEqual(self.run_audit()["requests"][0]["returned"][0]["roles"][1]["selectedIds"], ["stt-index-1"])

    def test_discloses_developer_injection_and_never_claims_discovery(self):
        row = self.saved["results"][0]
        row["mode"] = "SEEDED_DIAGNOSTIC"
        row["discoveryInjected"] = True
        row["calls"] = row["calls"][1:]
        result = self.run_audit()["requests"][0]
        self.assertIsNone(result["discoveryEvidenceIds"])
        self.assertIsNone(result["discoveryToVerificationEvidenceIdsPreserved"])

    def test_reports_changed_upstream_evidence_without_silently_repairing_it(self):
        self.saved["results"][0]["calls"][0]["output"]["candidates"][0]["evidence"] = [{"segmentId": "stt-index-0"}]
        self.assertFalse(self.run_audit()["requests"][0]["discoveryToVerificationEvidenceIdsPreserved"])
        self.assertEqual(self.saved["results"][0]["calls"][1]["input"]["candidates"][0]["proposedEvidence"], [{"segmentId": "stt-index-1"}])

    def test_missing_upstream_is_not_treated_as_an_autonomous_proposal(self):
        self.saved["results"][0]["calls"] = self.saved["results"][0]["calls"][1:]
        with self.assertRaisesRegex(ValueError, "UPSTREAM_PROPOSAL_NOT_FOUND"):
            self.run_audit()

    def test_duplicate_roles_are_rejected(self):
        self.roles["roles"].append(dict(self.roles["roles"][0]))
        with self.assertRaisesRegex(ValueError, "DUPLICATE_ROLE_ID"):
            self.run_audit()

    def test_cli_never_overwrites_a_saved_audit(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for name, value in (("results.json", self.saved), ("roles.json", self.roles)):
                (root / name).write_text(json.dumps(value), encoding="utf-8")
            (root / "source.json").write_bytes(self.source)
            (root / "output.json").write_text("KEEP_PREVIOUS", encoding="utf-8")
            with patch.object(sys, "argv", ["audit", "--results", str(root / "results.json"),
                                           "--role-map", str(root / "roles.json"), "--source-request", str(root / "source.json"),
                                           "--output", str(root / "output.json")]):
                with self.assertRaises(FileExistsError):
                    main()
            self.assertEqual((root / "output.json").read_text(), "KEEP_PREVIOUS")
