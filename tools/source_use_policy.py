"""Fail-closed source-use checks for context-unit previews and runtime builds.

The frozen lock preserves the exact pre-gate context-unit sources. It is not a
rights clearance. Any new source identity needs an explicit, hash-bound review
record authorizing the narrowly declared runtime retrieval use.
"""
import hashlib
import json
import re
from pathlib import Path

from review_context_pilot import parse, read_bytes, require, text
from review_dataset_catalog import inside
from incident_review_policy import validate_review_record


LOCK_PATH = Path(__file__).with_name("context_unit_source_lock.json")
PRODUCTION_ROOT = Path(__file__).resolve().parents[1] / "datasets/controversy"
USE = "RUNTIME_RETRIEVAL"
REQUIRED_USES = {USE, "RETRIEVAL_INDEXING", "MODEL_INPUT"}
ALLOWED_BASES = {"OWNED_OR_CREATED", "LICENSED", "EXPLICIT_PERMISSION", "PUBLIC_DOMAIN", "OTHER_REVIEWED"}
LEGACY_INCIDENT_ENTRIES_SHA256 = "bd9969d9c8a61c68f1edbe307f95677f5c608e4c61dfc37505d53bf5b2af17c1"


def sha256(raw):
    return hashlib.sha256(raw).hexdigest()


def _canonical_sha256(value):
    raw = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    return sha256(raw)


def _source_identity(unit):
    source = unit.get("source")
    require(isinstance(source, dict), "SOURCE_USE_SOURCE_REQUIRED")
    return {key: source.get(key) for key in ("kind", "path", "sha256")}


def _safe_metadata_path(root, relative):
    require(isinstance(relative, str) and relative and not Path(relative).is_absolute()
            and ".." not in Path(relative).parts, "SOURCE_USE_PATH_INVALID")
    cursor = root
    for part in Path(relative).parts:
        cursor = cursor / part
        require(not cursor.is_symlink(), "SOURCE_USE_SYMLINK_REFUSED")
    return inside(root, relative)


def _check_new_source(root, unit):
    link = unit.get("sourceProvenance")
    require(isinstance(link, dict) and set(link) == {"path", "sha256", "sourceId", "intendedUse"}
            and link.get("intendedUse") == USE and text(link.get("sourceId"), 100)
            and re.fullmatch(r"[0-9a-f]{64}", str(link.get("sha256", ""))),
            "SOURCE_USE_REVIEW_LINK_REQUIRED")
    metadata_path = _safe_metadata_path(root, link["path"])
    metadata_raw = read_bytes(metadata_path)
    require(sha256(metadata_raw) == link["sha256"], "SOURCE_USE_METADATA_HASH_MISMATCH")
    metadata = parse(metadata_raw)
    require(metadata.get("schemaVersion") == "source-provenance-metadata-2"
            and metadata.get("sourceId") == link["sourceId"], "SOURCE_USE_METADATA_ID_MISMATCH")
    require(metadata.get("rightsReviewStatus") == "CLEARED_FOR_DECLARED_USES"
            and metadata.get("sourceExcerptsQuarantined") is False,
            "SOURCE_USE_NOT_CLEARED")
    allowed, blocked = metadata.get("authorizedUses"), metadata.get("blockedUses")
    require(isinstance(allowed, list) and all(isinstance(x, str) for x in allowed)
            and REQUIRED_USES <= set(allowed) and isinstance(blocked, list)
            and all(isinstance(x, str) for x in blocked)
            and REQUIRED_USES.isdisjoint(blocked), "SOURCE_USE_SCOPE_NOT_AUTHORIZED")
    source = _source_identity(unit)
    source_doc = parse(read_bytes(inside(root, source["path"])))
    if source["kind"] == "STORED_TRANSCRIPT_SNAPSHOT":
        # New transcript material must use the schema that binds selected segments
        # and the human approval record to the exact original transcript.
        require(unit.get("schemaVersion") == "context-reference-unit-3"
                and source_doc.get("schemaVersion") == "context-transcript-source-1",
                "SOURCE_USE_TRANSCRIPT_SCHEMA_REQUIRED")
        artifact = source_doc.get("origin")
        require(isinstance(artifact, dict) and isinstance(artifact.get("path"), str)
                and re.fullmatch(r"[0-9a-f]{64}", str(artifact.get("sha256", ""))),
                "SOURCE_USE_ORIGIN_BINDING_INVALID")
        origin_path = _safe_metadata_path(root, artifact["path"])
        require(sha256(read_bytes(origin_path)) == artifact["sha256"], "SOURCE_USE_ORIGIN_HASH_MISMATCH")
    else:
        # For source kinds without an explicit original-media origin, bind the exact
        # immutable snapshot consumed by the builder.
        artifact = source
    reviewed_artifact = metadata.get("artifact")
    require(isinstance(reviewed_artifact, dict)
            and reviewed_artifact.get("path") == artifact.get("path")
            and reviewed_artifact.get("sha256") == artifact.get("sha256"),
            "SOURCE_USE_ARTIFACT_BINDING_MISMATCH")
    evidence = metadata.get("permissionEvidence")
    require(isinstance(evidence, dict) and set(evidence) == {"path", "sha256"}
            and re.fullmatch(r"[0-9a-f]{64}", str(evidence.get("sha256", ""))),
            "SOURCE_USE_REVIEW_EVIDENCE_REQUIRED")
    evidence_path = _safe_metadata_path(root, evidence["path"])
    evidence_raw = read_bytes(evidence_path)
    require(sha256(evidence_raw) == evidence["sha256"], "SOURCE_USE_EVIDENCE_HASH_MISMATCH")
    review = parse(evidence_raw)
    evidence_artifact = review.get("artifact")
    require(review.get("schemaVersion") == "source-use-review-2"
            and review.get("sourceId") == link["sourceId"] and review.get("decision") == "ALLOW"
            and review.get("intendedUses") == sorted(REQUIRED_USES)
            and isinstance(evidence_artifact, dict)
            and evidence_artifact.get("path") == artifact.get("path")
            and evidence_artifact.get("sha256") == artifact.get("sha256")
            and review.get("basis") in ALLOWED_BASES
            and text(review.get("reviewedBy"), 100) and text(review.get("reviewedOn"), 40),
            "SOURCE_USE_REVIEW_EVIDENCE_INVALID")
    return {"status": "EXPLICIT_REVIEW_CLEARANCE", "sourceId": link["sourceId"],
            "use": USE, "metadataPath": link["path"], "metadataSha256": link["sha256"],
            "evidencePath": evidence["path"], "evidenceSha256": evidence["sha256"]}


def validate_context_unit_sources(root, units):
    """Return per-unit source-use status; reject every non-legacy source without clearance."""
    root = Path(root).resolve()
    lock_raw = LOCK_PATH.read_bytes()
    lock = parse(lock_raw)
    require(lock.get("schemaVersion") == "context-unit-source-lock-2"
            and isinstance(lock.get("entries"), dict)
            and isinstance(lock.get("unitSchemaVersions"), dict), "SOURCE_USE_LOCK_INVALID")
    result = {}
    for unit in units:
        unit_id = unit.get("id")
        require(isinstance(unit_id, str), "SOURCE_USE_UNIT_ID_REQUIRED")
        source = _source_identity(unit)
        legacy = lock["entries"].get(unit_id)
        if legacy is not None and legacy == source and "sourceProvenance" not in unit:
            require(lock["unitSchemaVersions"].get(unit_id) == unit.get("schemaVersion"),
                    "SOURCE_USE_LEGACY_SCHEMA_MISMATCH")
            # Frozen pre-gate input only. New or changed source artifacts do not inherit this.
            result[unit_id] = {"status": "LEGACY_FROZEN_BASELINE", "sourceId": unit_id,
                               "use": USE, "rightsCleared": False}
            continue
        checked = _check_new_source(root, unit)
        checked["rightsCleared"] = True
        result[unit_id] = checked
    return result, sha256(lock_raw)


def incident_lock_path(root):
    """Incident IDs name real people, so the allowlist lives in the Git-ignored dataset."""
    return Path(root).resolve() / "definitions/incident-source-lock.json"


def reviewed_incident_inputs(root):
    """Review records the lock requires; preview stages must carry them with the incidents."""
    lock_file = incident_lock_path(root)
    if not lock_file.is_file():
        return []
    reviewed = parse(read_bytes(lock_file)).get("reviewedEntries")
    if not isinstance(reviewed, dict):
        return []
    return [entry["reviewPath"] for entry in reviewed.values()
            if isinstance(entry, dict) and isinstance(entry.get("reviewPath"), str)]


def validate_incident_sources(root):
    """Allow frozen legacy incidents and hash-bound, human-reviewed additions."""
    root = Path(root).resolve()
    lock_file = incident_lock_path(root)
    require(lock_file.is_file() and not lock_file.is_symlink(), "INCIDENT_SOURCE_LOCK_REQUIRED")
    lock_raw = lock_file.read_bytes()
    lock = parse(lock_raw)
    directory = root / "incidents"
    files = sorted(directory.glob("*.json")) if directory.is_dir() else []
    actual = {path.stem: (sha256(path.read_bytes()), path.read_bytes()) for path in files}
    if lock.get("schemaVersion") == "incident-source-lock-1":
        require(isinstance(lock.get("entries"), dict)
                and actual.keys() == lock["entries"].keys()
                and all(actual[key][0] == value for key, value in lock["entries"].items()),
                "INCIDENT_SOURCE_REVIEW_REQUIRED")
        if root == PRODUCTION_ROOT.resolve():
            require(_canonical_sha256(lock["entries"]) == LEGACY_INCIDENT_ENTRIES_SHA256,
                    "INCIDENT_SOURCE_LEGACY_LOCK_MUTATED")
    else:
        require(lock.get("schemaVersion") == "incident-source-lock-2"
                and set(lock) == {"schemaVersion", "frozenLegacyEntries", "reviewedEntries"}
                and isinstance(lock.get("frozenLegacyEntries"), dict)
                and isinstance(lock.get("reviewedEntries"), dict), "INCIDENT_SOURCE_LOCK_INVALID")
        legacy, reviewed = lock["frozenLegacyEntries"], lock["reviewedEntries"]
        if root == PRODUCTION_ROOT.resolve():
            require(_canonical_sha256(legacy) == LEGACY_INCIDENT_ENTRIES_SHA256,
                    "INCIDENT_SOURCE_LEGACY_BASELINE_MUTATED")
        require(not (legacy.keys() & reviewed.keys())
                and actual.keys() == legacy.keys() | reviewed.keys(), "INCIDENT_SOURCE_REVIEW_REQUIRED")
        for incident_id, expected_hash in legacy.items():
            require(isinstance(expected_hash, str) and actual[incident_id][0] == expected_hash,
                    "INCIDENT_SOURCE_REVIEW_REQUIRED")
        for incident_id, entry in reviewed.items():
            require(isinstance(entry, dict) and set(entry) == {"incidentSha256", "reviewPath", "reviewSha256"}
                    and entry.get("incidentSha256") == actual[incident_id][0]
                    and entry.get("reviewPath") == f"research/incident-reviews/{incident_id}.json"
                    and isinstance(entry.get("reviewSha256"), str)
                    and re.fullmatch(r"[0-9a-f]{64}", entry["reviewSha256"]),
                    "INCIDENT_SOURCE_REVIEW_REQUIRED")
            review_path = _safe_metadata_path(root, entry["reviewPath"])
            review_raw = read_bytes(review_path)
            require(sha256(review_raw) == entry["reviewSha256"], "INCIDENT_REVIEW_HASH_MISMATCH")
            validate_review_record(root, actual[incident_id][1], parse(review_raw))
    return sha256(lock_raw)
