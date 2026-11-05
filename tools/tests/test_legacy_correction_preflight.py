import hashlib
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import legacy_correction_preflight as preflight
from review_context_pilot import PilotError


def write(root, path, data):
    target = root / path
    target.parent.mkdir(parents=True, exist_ok=True)
    raw = json.dumps(data).encode()
    target.write_bytes(raw)
    return hashlib.sha256(raw).hexdigest()


@pytest.fixture
def fixture(tmp_path):
    incident = {"incidentId": "example", "statements": [{"id": "example#s1",
        "legacyDictionaryCard": {"documents": []}}]}
    incident_hash = write(tmp_path, "incidents/example.json", incident)
    runtime_hash = write(tmp_path, "guidelines/runtime.json", {"examples": []})
    write(tmp_path, "definitions/incident-source-lock.json",
          {"schemaVersion": "incident-source-lock-1", "entries": {"example": incident_hash}})
    proposal = {"schema": "incident-correction-research-proposal-1", "status": "RESEARCH_ONLY_NOT_APPLIED",
        "incidentId": "example", "statementId": "example#s1", "baselineIncidentSha256": incident_hash,
        "baselineRuntimeSha256": runtime_hash, "applied": False, "runtimeEligible": False,
        "trainingUseAuthorized": False, "externalModelAuthorized": False}
    write(tmp_path, "research/proposal.json", proposal)
    return tmp_path, proposal


def test_read_only_diagnostic_blocks_unreviewed_source(fixture):
    root, _ = fixture
    before = {p: p.read_bytes() for p in root.rglob("*") if p.is_file()}
    report = preflight.inspect(root, "research/proposal.json")
    assert report["sourceStructure"] == "INCIDENT_REVIEW_SOURCE_DOCUMENT_REQUIRED"
    assert not report["readyToApply"] and not report["writesPerformed"] and report["modelCalls"] == 0
    assert {p: p.read_bytes() for p in root.rglob("*") if p.is_file()} == before


@pytest.mark.parametrize("path, expected", [("incidents/example.json", "CORRECTION_INCIDENT_STALE"),
                                             ("guidelines/runtime.json", "CORRECTION_RUNTIME_STALE")])
def test_refuses_stale_baseline(fixture, path, expected):
    root, _ = fixture
    (root / path).write_text("{}")
    with pytest.raises(PilotError, match=expected):
        preflight.inspect(root, "research/proposal.json")


def test_refuses_permission_claim_and_path_traversal(fixture):
    root, proposal = fixture
    proposal["externalModelAuthorized"] = True
    write(root, "research/proposal.json", proposal)
    with pytest.raises(PilotError, match="CORRECTION_PROPOSAL_INVALID"):
        preflight.inspect(root, "research/proposal.json")
    with pytest.raises(PilotError, match="INCIDENT_INTAKE_UNSAFE_PATH"):
        preflight.inspect(root, "research/../proposal.json")


def test_non_object_proposal_is_a_contract_error_not_a_traceback(fixture):
    root, _ = fixture
    (root / "research/proposal.json").write_text("[]", encoding="utf-8")
    with pytest.raises(PilotError, match="CORRECTION_PROPOSAL_INVALID"):
        preflight.inspect(root, "research/proposal.json")
