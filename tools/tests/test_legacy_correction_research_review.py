import hashlib
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_legacy_correction_preflight import fixture, write
import legacy_correction_research_review as research
from review_context_pilot import PilotError


@pytest.fixture
def review_fixture(fixture):
    root, _ = fixture
    evidence_sha = write(root, "research/reference.json", {"note": "synthetic only"})
    proposal_sha = hashlib.sha256((root / "research/proposal.json").read_bytes()).hexdigest()
    review = {"schemaVersion": research.SCHEMA, "decision": "RESEARCH_DRAFT_REVIEWED",
              "scope": "RESEARCH_ONLY_NO_RUNTIME_NO_MODEL_NO_TRAINING", "proposalPath": "research/proposal.json",
              "proposalSha256": proposal_sha, "incidentId": "example", "reviewedBy": "test reviewer",
              "reviewedOn": "2026-10-29", "rationale": "Synthetic review; not source clearance.",
              "evidence": [{"path": "research/reference.json", "sha256": evidence_sha,
                            "role": "RESEARCH_REFERENCE_NOT_RIGHTS_CLEARANCE"}]}
    write(root, "research/review.json", review)
    return root, review


def test_valid_review_is_read_only_and_never_authorizes_runtime(review_fixture):
    root, _ = review_fixture
    before = {p: p.read_bytes() for p in root.rglob("*") if p.is_file()}
    report = research.validate(root, "research/proposal.json", "research/review.json")
    assert report["evidenceCount"] == 1 and report["sourceStructure"] == "INCIDENT_REVIEW_SOURCE_DOCUMENT_REQUIRED"
    assert report["readyToApply"] is False and report["selectorImpactExecuted"] is False
    assert report["modelCalls"] == 0 and report["writesPerformed"] is False
    assert {p: p.read_bytes() for p in root.rglob("*") if p.is_file()} == before


def test_changed_proposal_and_evidence_are_rejected(review_fixture):
    root, _ = review_fixture
    (root / "research/reference.json").write_text("changed")
    with pytest.raises(PilotError, match="CORRECTION_REVIEW_EVIDENCE_STALE"):
        research.validate(root, "research/proposal.json", "research/review.json")
    write(root, "research/reference.json", {"note": "synthetic only"})
    (root / "research/proposal.json").write_text((root / "research/proposal.json").read_text() + " ")
    with pytest.raises(PilotError, match="CORRECTION_REVIEW_BINDING_INVALID"):
        research.validate(root, "research/proposal.json", "research/review.json")


@pytest.mark.parametrize("field,value", [("scope", "MODEL_INPUT"), ("decision", "ALLOW"),
                                          ("reviewedOn", "not-a-date"), ("incidentId", "other")])
def test_rejects_wider_scope_or_invalid_review(review_fixture, field, value):
    root, review = review_fixture
    review[field] = value
    write(root, "research/review.json", review)
    with pytest.raises(PilotError):
        research.validate(root, "research/proposal.json", "research/review.json")


def test_rejects_self_reference_and_unsafe_path(review_fixture):
    root, review = review_fixture
    review["evidence"][0]["path"] = "research/proposal.json"
    write(root, "research/review.json", review)
    with pytest.raises(PilotError, match="CORRECTION_REVIEW_EVIDENCE_INVALID"):
        research.validate(root, "research/proposal.json", "research/review.json")
    review["evidence"][0]["path"] = "research/../outside.json"
    write(root, "research/review.json", review)
    with pytest.raises(PilotError, match="INCIDENT_INTAKE_UNSAFE_PATH"):
        research.validate(root, "research/proposal.json", "research/review.json")
