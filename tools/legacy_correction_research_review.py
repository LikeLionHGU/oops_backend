#!/usr/bin/env python3
"""Check a human-authored research-only correction review; never apply it."""
import argparse
import datetime
import json
import re
import sys
from pathlib import Path

from incident_intake import local
from legacy_correction_preflight import inspect, sha
from review_context_pilot import PilotError, parse, read_bytes, require, text


HASH = re.compile(r"[0-9a-f]{64}\Z")
SCHEMA = "legacy-correction-research-review-1"


def validate(root, proposal_rel, review_rel):
    root = Path(root).resolve()
    require(review_rel.startswith("research/") and review_rel.endswith(".json"),
            "CORRECTION_REVIEW_RESEARCH_PATH_REQUIRED")
    require(review_rel != proposal_rel, "CORRECTION_REVIEW_MUST_BE_SEPARATE")
    preflight = inspect(root, proposal_rel)
    review_raw = read_bytes(local(root, review_rel))
    review = parse(review_raw)
    fields = {"schemaVersion", "decision", "scope", "proposalPath", "proposalSha256", "incidentId",
              "reviewedBy", "reviewedOn", "rationale", "evidence"}
    require(isinstance(review, dict) and set(review) == fields and review.get("schemaVersion") == SCHEMA,
            "CORRECTION_REVIEW_SCHEMA_INVALID")
    require(review.get("decision") == "RESEARCH_DRAFT_REVIEWED"
            and review.get("scope") == "RESEARCH_ONLY_NO_RUNTIME_NO_MODEL_NO_TRAINING"
            and review.get("proposalPath") == proposal_rel
            and review.get("proposalSha256") == preflight["proposalSha256"]
            and review.get("incidentId") == preflight["incidentId"], "CORRECTION_REVIEW_BINDING_INVALID")
    require(text(review.get("reviewedBy"), 100) and text(review.get("rationale"), 1000)
            and isinstance(review.get("reviewedOn"), str), "CORRECTION_REVIEW_DETAILS_INVALID")
    try:
        datetime.date.fromisoformat(review["reviewedOn"])
    except ValueError:
        raise PilotError("CORRECTION_REVIEW_DATE_INVALID") from None
    evidence = review.get("evidence")
    require(isinstance(evidence, list) and evidence, "CORRECTION_REVIEW_EVIDENCE_REQUIRED")
    seen = set()
    for item in evidence:
        require(isinstance(item, dict) and set(item) == {"path", "sha256", "role"}
                and item.get("role") == "RESEARCH_REFERENCE_NOT_RIGHTS_CLEARANCE"
                and isinstance(item.get("path"), str) and item["path"].startswith("research/")
                and item["path"] not in {proposal_rel, review_rel} and item["path"] not in seen
                and HASH.fullmatch(str(item.get("sha256", ""))), "CORRECTION_REVIEW_EVIDENCE_INVALID")
        data = read_bytes(local(root, item["path"]))
        require(sha(data) == item["sha256"], "CORRECTION_REVIEW_EVIDENCE_STALE")
        seen.add(item["path"])
    return {"schemaVersion": "legacy-correction-research-review-result-1",
            "reviewSha256": sha(review_raw), "proposalSha256": preflight["proposalSha256"],
            "reviewStatus": "STRUCTURALLY_VALID_HUMAN_CLAIM_NOT_IDENTITY_OR_RIGHTS_PROOF",
            "evidenceCount": len(evidence), "sourceStructure": preflight["sourceStructure"],
            "readyToApply": False, "selectorImpactExecuted": False, "modelCalls": 0, "writesPerformed": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-root", type=Path, required=True)
    parser.add_argument("--proposal", required=True)
    parser.add_argument("--review", required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(validate(args.dataset_root, args.proposal, args.review), ensure_ascii=False, indent=2))
    except (OSError, ValueError, TypeError, KeyError) as error:
        print(f"CORRECTION_RESEARCH_REVIEW_FAILED: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
