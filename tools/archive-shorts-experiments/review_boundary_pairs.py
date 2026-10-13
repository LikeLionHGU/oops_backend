"""Offline boundary-pair checks and comparison planning. No API or approvals."""
import argparse
import copy
from pathlib import Path

from fixed_review_suite import digest, read, require, review_packet, write_private


def validate_pairs(cases, document):
    """Variable-size intake contract; not limited to the frozen 30-slot suite."""
    require(document.get("schemaVersion") == "review-boundary-pairs-1", "unsupported boundary schema")
    require(document.get("status") == "DEVELOPMENT_ONLY", "development boundaries only")
    require(all(document.get(k) is False for k in
                ("runtimeUseAuthorized", "trainingUseAuthorized", "curatedPromotionAuthorized")),
            "boundary cannot authorize promotion")
    indexed = {r["id"]: r for r in cases}
    require(len(indexed) == len(cases), "duplicate source case")
    pairs = document.get("pairs")
    require(isinstance(pairs, list) and 1 <= len(pairs) <= 5000, "boundary pair count invalid")
    ids, links = set(), set()
    selected = []
    for pair in pairs:
        pid = pair.get("id")
        require(isinstance(pid, str) and pid.strip() and pid not in ids, "duplicate or missing pair ID")
        ids.add(pid)
        rid, nid = pair.get("reviewCaseId"), pair.get("normalCaseId")
        require(rid in indexed and nid in indexed and rid != nid, "boundary reference missing")
        require((rid, nid) not in links, "duplicate boundary link")
        links.add((rid, nid))
        review, normal = indexed[rid], indexed[nid]
        require(review["bucket"] == "CONTROVERSY_RESEARCH" and normal["bucket"] == "NORMAL_CONTROL",
                "review and normal roles required")
        require(review["split"] == normal["split"] and review["contrastGroupId"] == normal["contrastGroupId"],
                "boundary split/group leakage")
        for role, row in (("review", review), ("normal", normal)):
            require(row["inputHash"] == digest(row["input"])
                    and pair.get(role + "InputHash") == row["inputHash"], "boundary input mismatch")
            require(row["input"]["mode"] != "SOURCE_MISSING", "boundary source missing")
        for field in ("mechanismId", "contextDifference"):
            require(isinstance(pair.get(field), str) and pair[field].strip(), "boundary explanation required")
        for field in ("requiredReviewQuotes", "forbiddenInferences"):
            require(isinstance(pair.get(field), list) and pair[field]
                    and all(isinstance(x, str) and x.strip() for x in pair[field]), "boundary evidence required")
        texts = [s["text"] for s in review["input"]["segments"]]
        require(all(any(q in t for t in texts) for q in pair["requiredReviewQuotes"]),
                "required quote absent from original")
        require(pair.get("reviewStatus") == "OWNER_DEVELOPMENT_ONLY", "no independent approval implied")
        for cid in (rid, nid):
            if cid not in selected:
                selected.append(cid)
    return selected


def comparison_draft(packet, boundaries, repetitions=3):
    require(type(repetitions) is int and 1 <= repetitions <= 10, "invalid repetition count")
    require(packet["manifest.json"]["modelInputsHash"] == digest(packet["model-inputs.json"]),
            "packet input mismatch")
    units = packet["model-inputs.json"]["units"]
    require(len({r["unitId"] for r in units}) == len(units), "duplicate unit")
    # Interleave arms and rotate first arm; no scheduler, API calls or auto judging.
    jobs = []
    for repeat in range(1, repetitions + 1):
        for i, unit in enumerate(units):
            arms = ("BASELINE", "CANDIDATE") if (repeat + i) % 2 else ("CANDIDATE", "BASELINE")
            for arm in arms:
                jobs.append({"jobId": f"{unit['unitId']}-{repeat}-{arm.lower()}",
                             "unitId": unit["unitId"], "repeat": repeat, "arm": arm,
                             "status": "PENDING", "decision": None, "targetIssueFound": None,
                             "originalEvidenceValid": None, "unsupportedInference": None,
                             "duplicateIssue": None, "latencyMs": None, "usage": None, "costUsd": None})
    return {"schemaVersion": "offline-comparison-draft-1", "status": "NOT_EXECUTABLE",
            "apiExecutionEnabled": False, "qualityGateMet": None,
            "scope": "OWNER_DEVELOPMENT_TEXT_BOUNDARIES_NOT_GENERALIZATION_TEST",
            "modelInputsHash": digest(packet["model-inputs.json"]), "boundariesHash": digest(boundaries),
            "manifestHash": digest(packet["manifest.json"]), "repetitions": repetitions,
            "plannedEvaluationJobs": len(jobs), "plannedApiCalls": None,
            "configuration": {"model": None, "modelSettings": None, "baselinePromptHash": None,
                              "candidatePromptHash": None, "referenceSnapshotHash": None,
                              "outputContractHash": None, "maxApiCalls": None, "maxCostUsd": None},
            "blockingReasons": ["EXECUTOR_NOT_IMPLEMENTED", "PROMPTS_AND_SETTINGS_NOT_FROZEN",
                                "REFERENCE_AND_OUTPUT_CONTRACT_NOT_FROZEN", "BUDGET_NOT_SET",
                                "INDEPENDENT_LABELS_PENDING"],
            "jobs": jobs}


def build_bundle(suite, boundaries, owner):
    selected = validate_pairs(suite["cases"], boundaries)
    packet = review_packet(suite, selected, owner)
    labels = {r["assessment"]["caseId"]: r["assessment"]["developmentExpectedDecision"]
              for r in packet["owner-labels.json"]["labels"]}
    for pair in boundaries["pairs"]:
        require(labels.get(pair["reviewCaseId"]) == "REVIEW_REQUIRED"
                and labels.get(pair["normalCaseId"]) == "PASS", "owner boundary labels missing")
    packet["boundary-pairs.json"] = copy.deepcopy(boundaries)
    packet["comparison-draft.json"] = comparison_draft(packet, boundaries)
    return packet


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--suite", required=True)
    parser.add_argument("--pairs", required=True)
    parser.add_argument("--owner-review", required=True)
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args()
    bundle = build_bundle(read(args.suite), read(args.pairs), read(args.owner_review))
    directory = Path(args.output_dir)
    require("datasets" in directory.resolve().parts, "private output must be inside datasets")
    directory.parent.mkdir(parents=True, exist_ok=True)
    directory.mkdir(mode=0o700)
    for name, payload in bundle.items():
        write_private(directory / name, payload)
    print(f"pairs={len(bundle['boundary-pairs.json']['pairs'])}, "
          f"units={len(bundle['model-inputs.json']['units'])}, "
          f"plannedJobs={bundle['comparison-draft.json']['plannedEvaluationJobs']}, API=OFF")


if __name__ == "__main__":
    main()
