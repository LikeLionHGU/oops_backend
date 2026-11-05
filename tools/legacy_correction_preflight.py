#!/usr/bin/env python3
"""Read-only diagnostics for a research proposal; never publishes or grants rights."""
import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

from incident_intake import local
from incident_review_policy import source_artifacts
from review_context_pilot import PilotError, parse, read_bytes, require
from source_use_policy import incident_lock_path


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def inspect(root, proposal_rel):
    root = Path(root).resolve()
    require(proposal_rel.startswith("research/") and proposal_rel.endswith(".json"),
            "CORRECTION_RESEARCH_PATH_REQUIRED")
    raw = read_bytes(local(root, proposal_rel))
    proposal = parse(raw)
    require(isinstance(proposal, dict), "CORRECTION_PROPOSAL_INVALID")
    require(proposal.get("schema") == "incident-correction-research-proposal-1"
            and proposal.get("status") == "RESEARCH_ONLY_NOT_APPLIED"
            and proposal.get("applied") is False
            and proposal.get("runtimeEligible") is False
            and proposal.get("trainingUseAuthorized") is False
            and proposal.get("externalModelAuthorized") is False,
            "CORRECTION_PROPOSAL_INVALID")
    incident_id = proposal.get("incidentId")
    require(isinstance(incident_id, str) and re.fullmatch(r"[A-Za-z0-9_-]{1,64}", incident_id),
            "CORRECTION_ID_INVALID")
    incident_raw = read_bytes(local(root, f"incidents/{incident_id}.json"))
    runtime_raw = read_bytes(local(root, "guidelines/runtime.json"))
    require(sha(incident_raw) == proposal.get("baselineIncidentSha256"), "CORRECTION_INCIDENT_STALE")
    require(sha(runtime_raw) == proposal.get("baselineRuntimeSha256"), "CORRECTION_RUNTIME_STALE")
    incident = parse(incident_raw)
    require(incident.get("incidentId") == incident_id, "CORRECTION_INCIDENT_ID_MISMATCH")
    statements = incident.get("statements")
    require(isinstance(statements, list)
            and sum(1 for row in statements if isinstance(row, dict)
                    and row.get("id") == proposal.get("statementId")) == 1,
            "CORRECTION_STATEMENT_INVALID")
    lock = parse(read_bytes(incident_lock_path(root)))
    if lock.get("schemaVersion") == "incident-source-lock-1":
        frozen = lock.get("entries")
    elif lock.get("schemaVersion") == "incident-source-lock-2":
        frozen = lock.get("frozenLegacyEntries")
    else:
        raise PilotError("CORRECTION_LOCK_INVALID")
    require(isinstance(frozen, dict) and frozen.get(incident_id) == sha(incident_raw),
            "CORRECTION_NOT_MATCHING_FROZEN_BASELINE")
    try:
        source_artifacts(root, incident)
        source_result = "STRUCTURALLY_LINKED_NOT_RIGHTS_CLEARED"
    except PilotError as error:
        source_result = str(error)
    return {"schemaVersion": "legacy-correction-preflight-1", "proposalSha256": sha(raw),
            "baselineIncidentSha256": sha(incident_raw), "baselineRuntimeSha256": sha(runtime_raw),
            "incidentId": incident_id, "sourceStructure": source_result,
            "blockers": ["FROZEN_LEGACY_REQUIRES_SEPARATE_REVIEW_PATH",
                         "SOURCE_USE_AND_INCIDENT_REVIEW_NOT_ESTABLISHED",
                         "SELECTOR_IMPACT_NOT_EXECUTED"],
            "readyToApply": False, "writesPerformed": False, "modelCalls": 0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-root", type=Path, required=True)
    parser.add_argument("--proposal", required=True, help="dataset-relative research JSON")
    args = parser.parse_args()
    try:
        print(json.dumps(inspect(args.dataset_root, args.proposal), ensure_ascii=False, indent=2))
    except (OSError, ValueError, TypeError, KeyError) as error:
        print(f"CORRECTION_PREFLIGHT_FAILED: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
