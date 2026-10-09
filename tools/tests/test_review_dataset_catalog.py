"""Layout and inventory checks; private fixtures contain synthetic text only."""
import copy
import datetime
import hashlib
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import review_dataset_catalog as tool


class CatalogTests(unittest.TestCase):
    def setUp(self):
        # Never depend on the real local dataset; it is intentionally Git-ignored.
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.dataset_root = Path(directory.name)
        self.catalog = {
            "schemaVersion": "controversy-catalog-1", "status": "CURATION_IN_PROGRESS",
            "workflow": "KNOWN_POINT_THEN_REACTION_MAPPING",
            "counterPolicy": "REFERENCE_ONLY_NO_CANCELLATION",
            "runtimeEligible": False, "trainingUseAuthorized": False, "humanApproved": False,
            "curatedDataset": "curated/dataset.json", "sourceResearchSnapshot": "research/source-intake.json",
            "patternResearch": "research/pattern-hypotheses.json",
            "templates": ["templates/example.json"], "benchmarks": ["benchmarks/example.json"],
            "incidents": [], "activeCollections": [],
            "excludedCollections": [{"snapshot": "duplicate.json", "duplicateOf": "collection_a",
                                     "reason": "IDENTICAL_COMMENT_ROWS", "records": 2,
                                     "action": "PRESERVED_NOT_COUNTED"}]
        }
        for suffix, records in (("a", 4), ("b", 3), ("c", 2)):
            self.catalog["activeCollections"].append({
                "id": "collection_" + suffix, "snapshot": suffix + ".json", "sha256": "0" * 64,
                "records": records, "refreshOrDeleteBy": "2099-01-01T00:00:00+00:00"})
            self.catalog["incidents"].append({
                "familyId": "synthetic_" + suffix, "split": "DEVELOPMENT",
                "mappingStatus": "UNREVIEWED_DRAFT", "humanApprovedCases": 0,
                "evidenceLevel": "REPORTED_CONTEXT_ONLY", "collectionId": "collection_" + suffix,
                "privateDraft": suffix + "-draft.json", "selectedReactions": 2,
                "caseIds": ["case_" + suffix], "publicMetadata": "research/metadata.json"})
        refs = [self.catalog[k] for k in ("curatedDataset", "sourceResearchSnapshot", "patternResearch")]
        refs += self.catalog["templates"] + self.catalog["benchmarks"] + ["research/metadata.json"]
        for relative in refs:
            path = self.dataset_root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            data = {"schemaVersion": "1", "sources": [], "cases": [], "reactions": [],
                    "annotations": [], "adjudications": []} if relative == "curated/dataset.json" else {}
            path.write_text(json.dumps(data))

    def test_layout_and_counts(self):
        report = tool.validate(self.catalog, self.dataset_root)
        self.assertEqual(9, report["activeCommentRecords"])
        self.assertEqual(2, report["excludedSnapshotRecords"])
        self.assertEqual(3, report["draftCards"])
        self.assertEqual(6, report["selectedReactionDrafts"])
        self.assertFalse(report["runtimeEligible"])

    def test_approval_rejected(self):
        for field in ("runtimeEligible", "trainingUseAuthorized", "humanApproved"):
            catalog = copy.deepcopy(self.catalog)
            catalog[field] = True
            with self.assertRaises(tool.PilotError):
                tool.validate(catalog, self.dataset_root)

    def test_outside_path_rejected(self):
        for relative in ("../outside.json", "/etc/passwd"):
            with self.assertRaises(tool.PilotError):
                tool.inside(tool.DEFAULT_ROOT, relative)

    def test_duplicate_and_unknown_references_rejected(self):
        catalog = copy.deepcopy(self.catalog)
        catalog["incidents"][1]["caseIds"].append("case_a")
        with self.assertRaises(tool.PilotError):
            tool.validate(catalog, self.dataset_root)
        catalog = copy.deepcopy(self.catalog)
        catalog["excludedCollections"][0]["duplicateOf"] = "unknown"
        with self.assertRaises(tool.PilotError):
            tool.validate(catalog, self.dataset_root)

    def test_private_integrity_and_cross_file_dedup(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rows = [{"videoId": "synthetic-video", "commentId": "synthetic-id", "text": "synthetic"}]
            data = {"schemaVersion": "youtube-temporary-review-1", "comments": rows,
                    "refreshOrDeleteBy": "2099-01-01T00:00:00+00:00"}
            raw = json.dumps(data).encode()
            (root / "a.json").write_bytes(raw)
            (root / "b.json").write_bytes(raw)
            collection = {"id": "a", "snapshot": "a.json", "records": 1,
                          "sha256": hashlib.sha256(raw).hexdigest(),
                          "refreshOrDeleteBy": data["refreshOrDeleteBy"]}
            catalog = {"activeCollections": [collection], "incidents": [],
                       "excludedCollections": [{"snapshot": "b.json", "duplicateOf": "a", "records": 1}]}
            self.assertEqual(1, tool.validate_private(catalog, root)["uniqueActiveComments"])
            catalog["activeCollections"].append(dict(collection, id="b", snapshot="b.json"))
            with self.assertRaisesRegex(tool.PilotError, "DUPLICATE_ACTIVE_COMMENT"):
                tool.validate_private(catalog, root)
            catalog["activeCollections"].pop()
            with self.assertRaisesRegex(tool.PilotError, "REFRESH_OR_DELETE_REQUIRED"):
                tool.validate_private(catalog, root, datetime.datetime(2100, 1, 1, tzinfo=datetime.timezone.utc))
            catalog["activeCollections"][0]["sha256"] = "0" * 64
            with self.assertRaisesRegex(tool.PilotError, "COLLECTION_HASH_MISMATCH"):
                tool.validate_private(catalog, root)


if __name__ == "__main__":
    unittest.main()
