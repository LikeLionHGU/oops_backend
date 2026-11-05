"""Validation for human-reviewed incident intake records.

These records bind an incident draft, its cited local source snapshots, and a
per-statement review to a declared internal runtime use. They are provenance
attestations, not proof of identity, source authenticity, legal rights, or truth.
"""
import datetime
import hashlib
import re
from pathlib import Path

from review_context_pilot import parse, read_bytes, require, text


REVIEW_SCHEMA = "incident-review-approval-1"
USES = ["MODEL_INPUT", "RUNTIME_RETRIEVAL"]
SOURCE_BASES = {"OWNED_OR_CREATED", "LICENSED", "EXPLICIT_PERMISSION", "PUBLIC_DOMAIN", "OTHER_REVIEWED"}
LABELS = {"PASS", "REVIEW_REQUIRED"}
SHA256 = re.compile(r"[0-9a-f]{64}\Z")


def _safe_file(root, relative):
    require(isinstance(relative, str) and relative and "\\" not in relative
            and "\0" not in relative and not Path(relative).is_absolute()
            and ".." not in Path(relative).parts, "INCIDENT_REVIEW_PATH_INVALID")
    root = Path(root).resolve()
    path = root
    for part in Path(relative).parts:
        path = path / part
        require(not path.is_symlink(), "INCIDENT_REVIEW_SYMLINK_REFUSED")
    resolved = path.resolve()
    require(resolved.is_relative_to(root) and resolved != root and resolved.is_file(),
            "INCIDENT_REVIEW_FILE_REQUIRED")
    return resolved


def source_artifacts(root, incident):
    """Validate all per-statement source snapshots and return their exact hash set."""
    artifacts = {}
    statement_documents = {}
    statements = incident.get("statements")
    require(isinstance(statements, list) and statements, "INCIDENT_REVIEW_STATEMENTS_REQUIRED")
    for statement in statements:
        statement_id = statement.get("id")
        require(text(statement_id, 100) and statement_id not in statement_documents,
                "INCIDENT_REVIEW_STATEMENT_ID_INVALID")
        dictionary_card = statement.get("legacyDictionaryCard")
        documents = dictionary_card.get("documents") if isinstance(dictionary_card, dict) else None
        require(isinstance(documents, list) and documents,
                "INCIDENT_REVIEW_SOURCE_DOCUMENT_REQUIRED")
        evidence = statement.get("legacyCard", {}).get("evidence", {})
        source_ids = {source.get("id") for source in evidence.get("sources", [])
                      if isinstance(source, dict) and isinstance(source.get("id"), str)}
        document_ids = []
        for document in documents:
            require(isinstance(document, dict), "INCIDENT_REVIEW_SOURCE_DOCUMENT_INVALID")
            doc_id, source_id = document.get("id"), document.get("sourceId")
            snapshot, expected_hash = document.get("snapshot"), document.get("sha256")
            require(text(doc_id, 120) and doc_id not in document_ids and text(source_id, 120)
                    and source_id in source_ids and isinstance(snapshot, str)
                    and SHA256.fullmatch(str(expected_hash or "")),
                    "INCIDENT_REVIEW_SOURCE_DOCUMENT_BINDING_INVALID")
            path = _safe_file(root, snapshot)
            raw = read_bytes(path)
            require(hashlib.sha256(raw).hexdigest() == expected_hash,
                    "INCIDENT_REVIEW_SOURCE_SNAPSHOT_HASH_MISMATCH")
            body = raw.decode("utf-8")
            start, end = document.get("startCodePoint"), document.get("endCodePoint")
            require(type(start) is int and type(end) is int and 0 <= start < end <= len(body)
                    and document.get("excerpt") == body[start:end],
                    "INCIDENT_REVIEW_SOURCE_EXCERPT_MISMATCH")
            previous = artifacts.get(snapshot)
            require(previous is None or previous == expected_hash,
                    "INCIDENT_REVIEW_SOURCE_HASH_CONFLICT")
            artifacts[snapshot] = expected_hash
            document_ids.append(doc_id)
        statement_documents[statement_id] = sorted(document_ids)
    return [{"path": path, "sha256": artifacts[path]} for path in sorted(artifacts)], statement_documents


def validate_review_record(root, incident_raw, review):
    """Fail closed unless approval covers the exact incident, statements and source files."""
    import hashlib
    incident = parse(incident_raw)
    required = {"schemaVersion", "incidentId", "incidentSha256", "decision", "scope", "reviewedBy",
                "reviewedOn", "statementReviews", "sourceUseReview", "sourceArtifacts"}
    require(isinstance(review, dict) and set(review) == required
            and review.get("schemaVersion") == REVIEW_SCHEMA, "INCIDENT_REVIEW_SCHEMA_INVALID")
    incident_id = incident.get("incidentId")
    require(text(incident_id, 100) and review.get("incidentId") == incident_id
            and SHA256.fullmatch(str(review.get("incidentSha256", "")))
            and review["incidentSha256"] == hashlib.sha256(incident_raw).hexdigest(),
            "INCIDENT_REVIEW_DRAFT_HASH_MISMATCH")
    require(review.get("decision") == "APPROVE_REFERENCE" and text(review.get("scope"), 1000)
            and text(review.get("reviewedBy"), 100)
            and isinstance(review.get("reviewedOn"), str), "INCIDENT_REVIEW_APPROVAL_REQUIRED")
    try:
        datetime.date.fromisoformat(review["reviewedOn"])
    except ValueError:
        require(False, "INCIDENT_REVIEW_DATE_INVALID")

    statement_documents = {statement["id"]: None for statement in incident["statements"]}
    artifacts, expected_documents = source_artifacts(root, incident)
    reviews = review.get("statementReviews")
    require(isinstance(reviews, list) and len(reviews) == len(statement_documents),
            "INCIDENT_REVIEW_STATEMENT_COVERAGE_REQUIRED")
    seen = set()
    for row in reviews:
        require(isinstance(row, dict) and set(row) == {"statementId", "decision", "rationale", "sourceDocumentIds"}
                and row.get("statementId") in statement_documents
                and row["statementId"] not in seen and row.get("decision") in LABELS
                and text(row.get("rationale"), 1000), "INCIDENT_REVIEW_STATEMENT_INVALID")
        source_document_ids = row.get("sourceDocumentIds")
        require(isinstance(source_document_ids, list)
                and source_document_ids == expected_documents[row["statementId"]],
                "INCIDENT_REVIEW_STATEMENT_SOURCES_MISMATCH")
        seen.add(row["statementId"])
    require(seen == set(statement_documents), "INCIDENT_REVIEW_STATEMENT_COVERAGE_REQUIRED")
    require(review.get("sourceArtifacts") == artifacts, "INCIDENT_REVIEW_SOURCE_SET_MISMATCH")

    source_use = review.get("sourceUseReview")
    require(isinstance(source_use, dict)
            and set(source_use) == {"decision", "basis", "authorizedUses", "rationale"}
            and source_use.get("decision") == "ALLOW_DECLARED_INTERNAL_USE"
            and source_use.get("basis") in SOURCE_BASES
            and source_use.get("authorizedUses") == USES
            and text(source_use.get("rationale"), 1000),
            "INCIDENT_REVIEW_SOURCE_USE_NOT_APPROVED")
    return review
