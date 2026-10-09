"""Offline dataset inventory checks; no approval, export, network or file writes."""
import argparse
import datetime
import hashlib
import json
import re
from pathlib import Path

from review_context_pilot import PilotError, parse, read_bytes, require, validate as validate_pilot
from review_context_intake import validate_mapping

DEFAULT_ROOT = Path(__file__).resolve().parents[1] / "datasets/controversy"


def inside(root, relative):
    require(isinstance(relative, str) and relative, "PATH_REQUIRED")
    path = (root / relative).resolve()
    require(path != root.resolve() and path.is_relative_to(root.resolve()), "PATH_OUTSIDE_ROOT")
    return path


def validate(catalog, root):
    require(catalog.get("schemaVersion") == "controversy-catalog-1", "CATALOG_SCHEMA_REQUIRED")
    require(catalog.get("status") == "CURATION_IN_PROGRESS"
            and catalog.get("workflow") == "KNOWN_POINT_THEN_REACTION_MAPPING"
            and catalog.get("counterPolicy") == "REFERENCE_ONLY_NO_CANCELLATION", "CURATION_POLICY_REQUIRED")
    require(all(catalog.get(key) is False for key in
                ("runtimeEligible", "trainingUseAuthorized", "humanApproved")), "NO_AUTOMATIC_PROMOTION")
    refs = [catalog["curatedDataset"], catalog["sourceResearchSnapshot"], catalog["patternResearch"]]
    refs += catalog["templates"] + catalog["benchmarks"]
    incidents = catalog["incidents"]
    require(isinstance(incidents, list) and 0 < len(incidents) <= 1000, "INCIDENT_LIMIT")
    refs += [item["publicMetadata"] for item in incidents]
    for relative in refs:
        parse(read_bytes(inside(root, relative)))
    curated = parse(read_bytes(inside(root, catalog["curatedDataset"])))
    require(curated.get("schemaVersion") == "1" and all(curated.get(key) == [] for key in
            ("sources", "cases", "reactions", "annotations", "adjudications")), "CURRENT_CURATED_LEDGER_MUST_BE_EMPTY")
    active = catalog["activeCollections"]
    require(isinstance(active, list) and 0 < len(active) <= 1000, "COLLECTION_LIMIT")
    ids, snapshots = set(), set()
    for collection in active:
        require(isinstance(collection["id"], str) and collection["id"] not in ids, "DUPLICATE_COLLECTION_ID")
        ids.add(collection["id"])
        require(isinstance(collection["snapshot"], str)
                and Path(collection["snapshot"]).name == collection["snapshot"]
                and collection["snapshot"].endswith(".json")
                and collection["snapshot"] not in snapshots, "INVALID_OR_DUPLICATE_SNAPSHOT")
        snapshots.add(collection["snapshot"])
        require(re.fullmatch(r"[0-9a-f]{64}", collection["sha256"]) is not None, "HASH_REQUIRED")
        require(type(collection["records"]) is int and collection["records"] > 0, "RECORD_COUNT_REQUIRED")
        require(datetime.datetime.fromisoformat(collection["refreshOrDeleteBy"]).tzinfo is not None,
                "TIMEZONE_REQUIRED")
    families, cases = set(), set()
    for incident in incidents:
        require(incident["familyId"] not in families, "DUPLICATE_INCIDENT")
        families.add(incident["familyId"])
        require(incident["split"] == "DEVELOPMENT" and incident["mappingStatus"] == "UNREVIEWED_DRAFT"
                and incident["humanApprovedCases"] == 0, "DRAFT_STATE_REQUIRED")
        require(incident["collectionId"] in ids, "UNKNOWN_COLLECTION")
        require(incident["evidenceLevel"] in {"SELECTED_STT_UNVERIFIED_AUDIO", "REPORTED_CONTEXT_ONLY"},
                "EVIDENCE_LEVEL_REQUIRED")
        require(Path(incident["privateDraft"]).name == incident["privateDraft"]
                and incident["privateDraft"].endswith(".json"), "PRIVATE_DRAFT_FILENAME_REQUIRED")
        require(type(incident["selectedReactions"]) is int and incident["selectedReactions"] >= 0,
                "REACTION_COUNT_REQUIRED")
        require(incident["caseIds"] and len(set(incident["caseIds"])) == len(incident["caseIds"])
                and not cases.intersection(incident["caseIds"]), "DUPLICATE_CASE")
        cases.update(incident["caseIds"])
    for excluded in catalog["excludedCollections"]:
        require(excluded["duplicateOf"] in ids and excluded["snapshot"] not in snapshots
                and Path(excluded["snapshot"]).name == excluded["snapshot"], "EXCLUDED_COLLECTION_REFERENCE_REQUIRED")
        require(excluded["reason"] == "IDENTICAL_COMMENT_ROWS"
                and excluded["action"] == "PRESERVED_NOT_COUNTED", "EXCLUSION_POLICY_REQUIRED")
        snapshots.add(excluded["snapshot"])
    return {"status": "STRUCTURALLY_VALID_CATALOG", "incidentFamilies": len(families),
            "draftCards": len(cases), "selectedReactionDrafts": sum(i["selectedReactions"] for i in incidents),
            "activeCommentRecords": sum(c["records"] for c in active),
            "excludedSnapshotRecords": sum(c["records"] for c in catalog["excludedCollections"]),
            "approvedCases": 0, "semanticMappingVerified": False, "runtimeEligible": False}


def validate_private(catalog, root, now=None, dataset_root=DEFAULT_ROOT):
    now = now or datetime.datetime.now(datetime.timezone.utc)
    identities, source_rows = set(), {}
    for collection in catalog["activeCollections"]:
        raw = read_bytes(inside(root, collection["snapshot"]))
        require(hashlib.sha256(raw).hexdigest() == collection["sha256"], "COLLECTION_HASH_MISMATCH")
        data = parse(raw)
        require(data.get("schemaVersion") == "youtube-temporary-review-1", "RAW_SCHEMA_REQUIRED")
        require(data.get("refreshOrDeleteBy") == collection["refreshOrDeleteBy"]
                and datetime.datetime.fromisoformat(collection["refreshOrDeleteBy"]) > now,
                "REFRESH_OR_DELETE_REQUIRED")
        require(len(data["comments"]) == collection["records"], "COLLECTION_COUNT_MISMATCH")
        for comment in data["comments"]:
            identity = (comment["videoId"], comment["commentId"])
            require(all(isinstance(v, str) and v for v in identity), "COMMENT_IDENTITY_REQUIRED")
            require(identity not in identities, "DUPLICATE_ACTIVE_COMMENT")
            identities.add(identity)
        source_rows[collection["id"]] = data["comments"]
    for excluded in catalog["excludedCollections"]:
        data = parse(read_bytes(inside(root, excluded["snapshot"])))
        require(len(data["comments"]) == excluded["records"]
                and data["comments"] == source_rows[excluded["duplicateOf"]], "EXCLUDED_DUPLICATE_MISMATCH")
    for incident in catalog["incidents"]:
        draft = parse(read_bytes(inside(root, incident["privateDraft"])))
        collection = next(c for c in catalog["activeCollections"] if c["id"] == incident["collectionId"])
        require(draft["collection"]["sha256"] == collection["sha256"], "DRAFT_COLLECTION_MISMATCH")
        require(all(draft.get(key) is False for key in
                    ("humanApproved", "datasetUseAuthorized", "trainingUseAuthorized")),
                "DRAFT_NOT_APPROVED")
        raw = read_bytes(inside(root, collection["snapshot"]))
        if draft.get("schemaVersion") == "context-pilot-2":
            validate_pilot(draft, raw, now=now)
            case_key = "id"
        elif draft.get("schemaVersion") == "reception-mapping-1":
            intake = parse(read_bytes(inside(dataset_root, catalog["sourceResearchSnapshot"])))
            validate_mapping(intake, draft, raw, now=now)
            case_key = "caseId"
        else:
            require(False, "UNKNOWN_DRAFT_SCHEMA")
        require({c[case_key] for c in draft["cases"]} == set(incident["caseIds"]), "DRAFT_CASE_MISMATCH")
        require(sum(len(c["reactions"]) for c in draft["cases"]) == incident["selectedReactions"],
                "DRAFT_REACTION_COUNT_MISMATCH")
    return {"uniqueActiveComments": len(identities), "sourceIntegrityVerified": True,
            "semanticMappingVerified": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--private-root", type=Path)
    args = parser.parse_args()
    try:
        catalog = parse(read_bytes(args.dataset_root / "catalog.json"))
        report = validate(catalog, args.dataset_root)
        if args.private_root:
            report["privateChecks"] = validate_private(catalog, args.private_root, dataset_root=args.dataset_root)
    except PilotError as error:
        parser.exit(2, str(error) + "\n")
    except (ValueError, KeyError, TypeError, AttributeError, OSError, StopIteration):
        parser.exit(2, "INVALID_OR_UNAVAILABLE_INPUT\n")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
