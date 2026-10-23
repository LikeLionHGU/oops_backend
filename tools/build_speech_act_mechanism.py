"""Compile a development-only reported/enacted speech-act mechanism preview.

Labels and raw inputs are isolated from authored model references. Existing
approved evaluation items reused here become development material, not holdout.
"""
import argparse
import copy
import json
from pathlib import Path

from build_context_assets import canonical, compile_assets
from build_context_units import ROOT, digest
from build_review_guidelines import write_private
from review_context_pilot import parse, read_bytes, require, text
from review_dataset_catalog import inside


def checked(root, ref):
    raw = read_bytes(inside(root, ref["path"]))
    require(digest(raw) == ref["sha256"], "MECHANISM_SOURCE_CHANGED")
    return parse(raw)


def compile_mechanism(plan, root, runtime):
    require(plan.get("schemaVersion") == "speech-act-mechanism-plan-1"
            and plan.get("status") == "AUTHOR_DRAFT_WITH_EXISTING_DEVELOPMENT_LABELS"
            and plan.get("independentGold") is False, "MECHANISM_DEVELOPMENT_ONLY")
    inputs = checked(root, plan["evaluationInputs"])
    labels = checked(root, plan["evaluationLabels"])
    input_index, label_index = ({x["caseId"]: x for x in rows} for rows in (inputs, labels))
    require(len(input_index) == len(inputs) and len(label_index) == len(labels), "MECHANISM_DUPLICATE_SOURCE_CASE")
    cases, evidence, queries, seen = [], [], [], set()
    for row in plan["cases"]:
        cid = row["id"]
        require(cid not in seen, "MECHANISM_DUPLICATE_CASE")
        seen.add(cid)
        require(row["caseId"] in input_index and row["caseId"] in label_index, "MECHANISM_CASE_MISSING")
        item, label = input_index[row["caseId"]], label_index[row["caseId"]]
        require(label.get("expectedDecision") in {"PASS", "REVIEW_REQUIRED"}
                and label.get("labelScope", "").startswith("USER_APPROVED_")
                and label.get("userAnswer"), "MECHANISM_EXISTING_APPROVAL_REQUIRED")
        raw = item["raw"]
        ids = {s["id"] for s in raw}
        require(len(ids) == len(raw) and item["anchorId"] in ids, "MECHANISM_RAW_IDS_INVALID")
        require(isinstance(row["evidenceIds"], list) and row["evidenceIds"]
                and len(set(row["evidenceIds"])) == len(row["evidenceIds"])
                and set(row["evidenceIds"]) <= ids, "MECHANISM_EVIDENCE_OUTSIDE_SOURCE")
        context = row["context"]
        require(set(context) == {"situation", "target", "relation", "requiredCondition", "insufficientCondition", "normalBoundary", "unknown"}
                and all(text(v, 300) for v in context.values()), "MECHANISM_CONTEXT_REQUIRED")
        cases.append({"id": cid, "familyId": row["familyId"], "context": copy.deepcopy(context),
                      "basis": "AUTHORED_CONTEXT_NOT_CURRENT_EVIDENCE", "mechanismIds": ["sexual-objectification"]})
        evidence.append({"id": cid, "sourceCaseId": row["caseId"], "raw": copy.deepcopy(raw),
                         "evidenceIds": row["evidenceIds"], "sourceLabel": copy.deepcopy(label),
                         "materialKind": "SYNTHETIC" if item.get("timeBasis") == "SYNTHETIC_RELATIVE_NOT_MEDIA" else "STORED_STT",
                         "independentGold": False, "trainingUseAuthorized": False,
                         "sourceGroupId": label.get("sourceGroupId", row["familyId"])})
        queries.append({"caseId": cid, "anchorId": item["anchorId"], "raw": copy.deepcopy(raw)})
    profile = checked(root, plan["victimProfile"])
    unit = checked(root, plan["victimUnit"])
    victim = compile_assets(profile, unit, root)
    require(unit.get("developmentDecision") == "PASS", "MECHANISM_VICTIM_APPROVAL_REQUIRED")
    known = {c["id"] for c in cases} | {unit["id"]}
    decisions = {e["id"]: e["sourceLabel"]["expectedDecision"] for e in evidence}
    decisions[unit["id"]] = unit["developmentDecision"]
    for link in plan["boundaryLinks"]:
        require(link["normal"] in known and link["review"] in known and link["normal"] != link["review"]
                and text(link["difference"], 350), "MECHANISM_BOUNDARY_LINK_INVALID")
        require(decisions[link["normal"]] == "PASS" and decisions[link["review"]] == "REVIEW_REQUIRED",
                "MECHANISM_BOUNDARY_LABEL_MISMATCH")
    require(len(plan["boundaryLinks"]) >= 2
            and {x for b in plan["boundaryLinks"] for x in (b["normal"], b["review"])} == known,
            "MECHANISM_BOUNDARY_COVERAGE_REQUIRED")
    # Create an adapter for the EXISTING Java archive contract, only in a new preview directory.
    preview = copy.deepcopy(runtime)
    target = next(e for e in preview["examples"] if e["id"] == unit["id"])
    vc = profile["context"]
    target["context"] = {"coverage": "REPORTED_CONTEXT_ONLY_NOT_VIDEO_TRANSCRIPT",
                         "flow": ["작성 맥락(원문 아님): " + vc["situation"], "작성 관계: " + vc["observedRelations"][0]["description"]],
                         "criticismHypotheses": [], "sourceInterpretations": [], "normalContrasts": [vc["normalBoundary"]],
                         "missingContext": vc["unknowns"] + vc["requiredConditions"] + vc["insufficientConditions"]}
    target["retrievalView"]["text"] = victim["search"]["text"]
    target["retrievalView"]["sha256"] = digest(victim["search"]["text"].encode())
    packet = plan["mechanismReference"]
    require(set(packet) == {"id", "familyId", "mechanismIds", "searchText", "situation", "relations", "boundaries", "unknowns"}
            and text(packet["searchText"], 1500) and text(packet["situation"], 300), "MECHANISM_PACKET_REQUIRED")
    require(packet["mechanismIds"] and set(packet["mechanismIds"]) <= {r["id"] for r in runtime["guidelines"]},
            "MECHANISM_PACKET_TAXONOMY_INVALID")
    for field in ("relations", "boundaries", "unknowns"):
        require(isinstance(packet[field], list) and 1 <= len(packet[field]) <= 4
                and all(text(s, 300) for s in packet[field]), "MECHANISM_PACKET_BOUNDARY_REQUIRED")
    support = plan["packetSupport"]
    expected = {(field, index) for field in ("relations", "boundaries") for index in range(len(packet[field]))}
    seen_support = set()
    for link in support:
        key = (link.get("field"), link.get("index"))
        require(type(link.get("index")) is int and key in expected and key not in seen_support
                and isinstance(link.get("caseIds"), list) and link["caseIds"]
                and len(set(link["caseIds"])) == len(link["caseIds"])
                and set(link["caseIds"]) <= known, "MECHANISM_PACKET_SUPPORT_INVALID")
        seen_support.add(key)
    require(seen_support == expected, "MECHANISM_PACKET_SUPPORT_MISSING")
    require(packet["id"] not in {e["id"] for e in preview["examples"]}, "MECHANISM_PACKET_ID_COLLISION")
    # One bounded contrast packet, not five competing one-sided references.
    preview["examples"].append({"id": packet["id"], "familyId": packet["familyId"], "mechanismIds": packet["mechanismIds"],
        "channels": ["SPEECH"], "routingKeys": [],
        "context": {"coverage": "REPORTED_CONTEXT_ONLY_NOT_VIDEO_TRANSCRIPT",
            "flow": ["작성 맥락(원문 아님): " + packet["situation"]] + packet["relations"],
            "criticismHypotheses": [], "sourceInterpretations": [], "normalContrasts": packet["boundaries"],
            "missingContext": packet["unknowns"]},
        "retrievalView": {"version": "context-unit-retrieval-1", "basis": "AUTHORED_ROUTING_NOT_SOURCE_EVIDENCE",
                          "text": packet["searchText"], "sha256": digest(packet["searchText"].encode())}})
    preview["sourceCases"].append({"caseId": packet["id"], "familyId": packet["familyId"], "status": "UNREVIEWED_DRAFT"})
    for rule in preview["guidelines"]:
        if rule["id"] in packet["mechanismIds"]:
            rule["sourceCaseIds"].append(packet["id"])
    preview["version"] = "2026-10-23-speech-act-development-preview"
    preview["sourceBundleSha256"] = digest(canonical(plan))
    preview["contextDictionarySha256"] = preview["sourceBundleSha256"]
    queries.append({"caseId": unit["id"], "anchorId": unit["utteranceIds"][0], "raw": victim["evidence"]["segments"]})
    return {"references": {"schemaVersion": "speech-act-context-references-1", "status": plan["status"],
                           "cases": cases, "victimReference": victim["reference"],
                           "boundaryLinks": plan["boundaryLinks"], "mechanismReference": packet, "independentGold": False},
            "evidence": {"cases": evidence, "victim": victim["evidence"], "packetSupport": support},
            "queries": queries, "preview-runtime": preview,
            "development-use-registry": {
                "schemaVersion": "context-development-reuse-1", "independentHoldoutEligible": False,
                "sourceCaseIds": [e["sourceCaseId"] for e in evidence],
                "excludedSourceGroupsForFutureIndependentEvaluation": sorted(
                    {e["sourceGroupId"] for e in evidence} | {unit["familyId"]}),
                "enforcement": "MANIFEST_ONLY_NOT_CONNECTED_TO_EVALUATION_RUNNERS"}}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--plan", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--dataset-root", type=Path, default=ROOT)
    args = p.parse_args()
    root, output = args.dataset_root.resolve(), args.output.absolute()
    require(not output.exists() and output.parent.resolve().is_relative_to((root / "experiments").resolve()), "NEW_MECHANISM_PREVIEW_REQUIRED")
    plan = parse(read_bytes(inside(root, str(args.plan))))
    runtime_bytes = read_bytes(root / "guidelines/runtime.json")
    result = compile_mechanism(plan, root, parse(runtime_bytes))
    repeat = compile_mechanism(plan, root, parse(runtime_bytes))
    require(canonical(result) == canonical(repeat), "MECHANISM_NONDETERMINISTIC")
    output.mkdir(mode=0o700)
    for key, value in result.items():
        write_private(output / (key + ".json"), value)
    write_private(output / "manifest.json", {"providerCalls": 0, "activeRuntimeChanged": False,
        "compilerSha256": digest(Path(__file__).read_bytes()), "planCanonicalSha256": digest(canonical(plan)),
        "activeRuntimeSha256": digest(runtime_bytes), "repeatBuildEqual": True,
        "canonicalOutputSha256": {k: digest(canonical(v)) for k, v in result.items()},
        "caseCount": len(result["queries"]), "labelLeakageToReference": False,
        "note": "Reference excludes label records; authored normal/review boundaries remain intentional guidance."})
    require(read_bytes(root / "guidelines/runtime.json") == runtime_bytes, "ACTIVE_RUNTIME_CHANGED")
    print(json.dumps({"output": str(output), "cases": len(result["queries"]), "repeatBuildEqual": True}))


if __name__ == "__main__":
    main()
