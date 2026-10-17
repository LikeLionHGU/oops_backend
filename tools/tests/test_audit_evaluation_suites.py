import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import audit_evaluation_suites as audit


class EvaluationSuiteAuditTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "datasets/controversy/evaluation").mkdir(parents=True)
        cases = []
        labels = []
        for name in ["R01", "R03", "R04"]:
            rows = [{"id": name + str(i), "text": "synthetic text " + str(i), "startMs": i * 2, "endMs": i * 2 + 1} for i in range(2)]
            cases.append({"reviewId": name, "sourceKind": "STORED_STT" if name == "R01" else "SYNTHETIC_DRAFT",
                          "anchorId": rows[1]["id"], "raw": rows})
            labels.append({"reviewId": name, "reviewStatus": "USER_APPROVED_DEVELOPMENT_LABEL",
                           "userAnswer": "approved test fixture", "expectedDecision": "REVIEW_REQUIRED" if name == "R04" else "PASS",
                           "presentedExcerptIds": [r["id"] for r in rows][1:] if name == "R01" else [r["id"] for r in rows]})
        self.packet = {"cases": cases}
        self.review = {"reviewStatus": "USER_APPROVED_DEVELOPMENT_LABELS",
                       "blindPacketCanonicalSha256": audit.sha(audit.canonical(self.packet)), "cases": labels}
        self.registry = {"schemaVersion": "evaluation-suite-registry-1", "activeSuite": "test", "purpose": "DEVELOPMENT",
                         "activeDecisions": {"R03": "PASS", "R04": "REVIEW_REQUIRED"}, "scopedDiagnostics": ["R01"],
                         "focusCase": "R01", "otherMaterials": [], "historyRoots": ["datasets/controversy/evaluation"]}
        self.sync()

    def sync(self):
        self.review["blindPacketCanonicalSha256"] = audit.sha(audit.canonical(self.packet))
        for key, name, value in [("sourcePacket", "source.json", self.packet), ("humanReview", "review.json", self.review)]:
            raw = audit.canonical(value)
            (self.root / name).write_bytes(raw)
            self.registry[key] = {"path": name, "sha256": audit.sha(raw)}

    def test_only_full_approved_dialogues_are_active_with_separate_labels(self):
        before = audit.canonical(self.packet)
        result = audit.prepare(self.root, self.registry)
        self.assertEqual(["R03", "R04"], [c["caseId"] for c in result["inputs.json"]])
        self.assertNotIn("expectedDecision", json.dumps(result["inputs.json"]))
        self.assertIsNone(result["focus-R01.json"]["expectedDecision"])
        self.assertEqual("PASS", result["focus-R01.json"]["priorApproval"]["expectedDecision"])
        self.assertEqual(before, audit.canonical(self.packet))
        self.assertFalse(result["manifest.json"]["independentGold"])

    def test_mismatch_of_approval_and_active_window_is_rejected(self):
        self.review["cases"][1]["presentedExcerptIds"] = ["R030"]
        self.sync()
        with self.assertRaisesRegex(ValueError, "ACTIVE_APPROVAL_SCOPE_MISMATCH"):
            audit.prepare(self.root, self.registry)

    def test_changed_source_is_not_silently_accepted(self):
        (self.root / "source.json").write_text("{}")
        with self.assertRaisesRegex(ValueError, "SOURCE_HASH_MISMATCH"):
            audit.prepare(self.root, self.registry)

    def test_unapproved_label_and_unknown_case_cannot_be_promoted(self):
        self.review["cases"][1]["reviewStatus"] = "DRAFT"
        self.sync()
        with self.assertRaisesRegex(ValueError, "LABEL_NOT_APPROVED"):
            audit.prepare(self.root, self.registry)
        other = copy.deepcopy(self.registry)
        other["activeDecisions"]["unapproved"] = "PASS"
        with self.assertRaisesRegex(ValueError, "CASE_ALLOWLIST_MISMATCH"):
            audit.prepare(self.root, other)

    def test_duplicate_ids_and_changed_decision_are_rejected(self):
        self.packet["cases"][0]["raw"][0]["id"] = "R011"
        self.sync()
        with self.assertRaisesRegex(ValueError, "DUPLICATE_CASE_ID"):
            audit.prepare(self.root, self.registry)
        self.packet["cases"][0]["raw"][0]["id"] = "R010"
        self.sync()
        self.registry["activeDecisions"]["R03"] = "REVIEW_REQUIRED"
        with self.assertRaisesRegex(ValueError, "ACTIVE_LABEL_MISMATCH"):
            audit.prepare(self.root, self.registry)

    def test_private_output_never_overwrites_sources(self):
        result = audit.prepare(self.root, self.registry)
        output = self.root / "datasets/controversy/evaluation/active-test"
        audit.write_new(self.root, output, result)
        self.assertEqual(0o700, output.stat().st_mode & 0o777)
        self.assertEqual(0o600, (output / "inputs.json").stat().st_mode & 0o777)
        with self.assertRaisesRegex(ValueError, "NEW_PRIVATE_OUTPUT_REQUIRED"):
            audit.write_new(self.root, output, result)
        with self.assertRaisesRegex(ValueError, "NEW_PRIVATE_OUTPUT_REQUIRED"):
            audit.write_new(self.root, self.root / "outside", result)


if __name__ == "__main__":
    unittest.main()
