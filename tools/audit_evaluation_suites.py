"""Build one explicitly allowlisted development suite; no models, relabeling or source mutation."""
import argparse
import hashlib
import json
import os
from pathlib import Path


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()


def sha(value):
    return hashlib.sha256(value).hexdigest()


def require(condition, code):
    if not condition:
        raise ValueError(code)


def read(root, relative):
    path = (root / relative).resolve()
    require(path.is_relative_to(root.resolve()) and path.is_file(), "SOURCE_PATH_INVALID")
    require(path.stat().st_size <= 8_388_608, "SOURCE_TOO_LARGE")
    return path.read_bytes()


def index(cases, key):
    require(isinstance(cases, list) and all(isinstance(c, dict) and c.get(key) for c in cases), "CASE_SHAPE_INVALID")
    result = {c[key]: c for c in cases}
    require(len(result) == len(cases), "DUPLICATE_CASE_ID")
    return result


def verified_media_source_group(review, source):
    """Only treat an explicitly verified, source-matched media group as physical provenance."""
    group = review.get("sourceGroupId")
    if (review.get("sourceGroupVerified") is True and isinstance(group, str) and group
            and source.get("sourceGroupId") == group and source.get("sourceKind") != "SYNTHETIC_DRAFT"):
        return group
    return None


def focused_units(root, registry, sources):
    spec = registry.get("approvedFocusedReview")
    if not spec:
        return [], [], None
    raw = read(root, spec["path"])
    require(sha(raw) == spec["sha256"], "FOCUSED_REVIEW_HASH_MISMATCH")
    review = json.loads(raw)
    source_id = spec["sourceReviewId"]
    require(review.get("status") == "USER_APPROVED_FULL_WINDOW_UNITS_DEVELOPMENT_ONLY"
            and review.get("sourceReviewId") == source_id and source_id in sources
            and review.get("sourcePacketSha256") == registry["sourcePacket"]["sha256"], "FOCUSED_REVIEW_NOT_APPROVED")
    require(review.get("independentGold") is False and review.get("runtimePromotionApproved") is False
            and review.get("trainingUseApproved") is False and review.get("familyId"), "FOCUSED_APPROVAL_SCOPE_INVALID")
    source = sources[source_id]
    source_bytes = read(root, review["sourceFile"]["path"])
    require(sha(source_bytes) == review["sourceFile"]["sha256"]
            and json.loads(source_bytes)["source"] == source, "FOCUSED_SOURCE_MISMATCH")
    rows = source["raw"]
    require(review.get("reviewedSegmentIds") == [r["id"] for r in rows], "FOCUSED_WINDOW_SCOPE_MISMATCH")
    units = index(review["units"], "reviewUnitId")
    require(set(units) == set(spec["activeDecisions"]), "FOCUSED_ALLOWLIST_MISMATCH")
    require(len({u["anchorId"] for u in units.values()}) == len(units), "FOCUSED_DUPLICATE_ANCHOR")
    inputs, labels = [], []
    for unit_id, decision in spec["activeDecisions"].items():
        unit = units[unit_id]
        require(unit["anchorId"] in review["reviewedSegmentIds"] and unit.get("userAnswer")
                and decision in {"PASS", "REVIEW_REQUIRED", "UNCERTAIN"}
                and unit.get("expectedDecision") == decision, "FOCUSED_LABEL_INVALID")
        inputs.append({"caseId": unit_id, "anchorId": unit["anchorId"], "raw": rows,
                       "timeBasis": "STORED_STT_TIMING_NOT_NEW_AUDIO_VERIFICATION"})
        labels.append({"caseId": unit_id, "expectedDecision": decision, "userAnswer": unit["userAnswer"],
                       "labelScope": "USER_APPROVED_ANCHOR_ISSUE_WITH_FULL_SOURCE_WINDOW_DEVELOPMENT_ONLY",
                       "recordedAt": review.get("recordedAt"), "evidenceIdsUserApproved": False,
                       "sourceGroupId": verified_media_source_group(review, source),
                       "evaluationGroupId": review.get("familyId")})
    return inputs, labels, {"reviewSha256": spec["sha256"], "sourceReviewId": source_id,
                            "sourceGroupId": verified_media_source_group(review, source),
                            "evaluationGroupId": review.get("familyId"),
                            "approvedUnitIds": list(spec["activeDecisions"])}


def supplemental_units(root, registry):
    spec = registry.get("supplementalContrasts")
    if not spec:
        return [], []
    raw = read(root, spec["path"])
    require(sha(raw) == spec["sha256"], "CONTRAST_HASH_MISMATCH")
    fixture = json.loads(raw)
    require(fixture.get("sourceType") == "SYNTHETIC" and fixture.get("runtimeEnabled") is False
            and fixture.get("independentGold") is False
            and fixture.get("approval", {}).get("status") == "USER_APPROVED"
            and fixture["approval"].get("scope") == "CONTEXT_DECISION_ONLY", "CONTRAST_APPROVAL_SCOPE")
    cases = index(fixture["cases"], "id")
    require(set(cases) == set(spec["activeDecisions"]), "CONTRAST_ALLOWLIST_MISMATCH")
    inputs, labels = [], []
    for case_id, decision in spec["activeDecisions"].items():
        c = cases[case_id]
        require(c.get("context", {}).get("status") == "USER_APPROVED"
                and decision in {"PASS", "REVIEW_REQUIRED", "UNCERTAIN"}
                and c["context"].get("expectedDecision") == decision, "CONTRAST_LABEL_NOT_APPROVED")
        rows = c["dialogue"]
        index(rows, "id")
        require(len(rows) == 2 and all(isinstance(r.get("text"), str) and r["text"].strip() for r in rows), "CONTRAST_RAW_INVALID")
        source = [{"id": r["id"], "text": r["text"], "startMs": i * 1000, "endMs": (i + 1) * 1000}
                  for i, r in enumerate(rows)]
        inputs.append({"caseId": case_id, "anchorId": rows[-1]["id"], "raw": source,
                       "timeBasis": "SYNTHETIC_ORDER_ONLY_NOT_MEDIA"})
        labels.append({"caseId": case_id, "expectedDecision": decision,
                       "labelScope": "USER_APPROVED_CONTEXT_ONLY_DEVELOPMENT",
                       "sourceGroupId": None, "evaluationGroupId": "synthetic-food-contrasts-20261018",
                       "evidenceIdsUserApproved": False,
                       "advisoryLabelApproved": False, "reviewedAt": fixture["approval"].get("date")})
    return inputs, labels


def prepare(root, registry):
    require(registry["schemaVersion"] == "evaluation-suite-registry-1", "REGISTRY_VERSION")
    loaded = []
    for name in ("sourcePacket", "humanReview"):
        spec = registry[name]
        raw = read(root, spec["path"])
        require(sha(raw) == spec["sha256"], "SOURCE_HASH_MISMATCH")
        loaded.append(json.loads(raw))
    packet, review = loaded
    require(review["blindPacketCanonicalSha256"] == sha(canonical(packet)), "REVIEW_PACKET_MISMATCH")
    require(review["reviewStatus"] == "USER_APPROVED_DEVELOPMENT_LABELS", "REVIEW_NOT_APPROVED")
    sources, labels = index(packet["cases"], "reviewId"), index(review["cases"], "reviewId")
    active, diagnostic = registry["activeDecisions"], registry["scopedDiagnostics"]
    require(set(active).isdisjoint(diagnostic) and set(active) | set(diagnostic) == set(sources) == set(labels), "CASE_ALLOWLIST_MISMATCH")
    inputs, decisions, excluded = [], [], []
    for case_id, source in sources.items():
        label = labels[case_id]
        rows = source["raw"]
        by_id = index(rows, "id")
        require(source["anchorId"] in by_id and all(isinstance(r.get("text"), str) and r["text"].strip() for r in rows), "RAW_INVALID")
        require(all(isinstance(r.get("startMs"), int) and isinstance(r.get("endMs"), int)
                    and 0 <= r["startMs"] <= r["endMs"] for r in rows), "TIME_INVALID")
        require([r["startMs"] for r in rows] == sorted(r["startMs"] for r in rows), "RAW_ORDER_INVALID")
        presented = label["presentedExcerptIds"]
        require(len(set(presented)) == len(presented) and set(presented) <= set(by_id), "APPROVAL_IDS_INVALID")
        require(label["reviewStatus"] == "USER_APPROVED_DEVELOPMENT_LABEL" and label.get("userAnswer")
                and label["expectedDecision"] in {"PASS", "REVIEW_REQUIRED", "UNCERTAIN"}, "LABEL_NOT_APPROVED")
        if case_id in active:
            require(source["sourceKind"] == "SYNTHETIC_DRAFT" and set(presented) == set(by_id), "ACTIVE_APPROVAL_SCOPE_MISMATCH")
            require(active[case_id] == label["expectedDecision"], "ACTIVE_LABEL_MISMATCH")
            inputs.append({"caseId": case_id, "anchorId": source["anchorId"], "raw": rows,
                           "timeBasis": "SYNTHETIC_RELATIVE_NOT_MEDIA"})
            decisions.append({"caseId": case_id, "expectedDecision": label["expectedDecision"],
                              "labelScope": "USER_APPROVED_FULL_SYNTHETIC_DIALOGUE_DEVELOPMENT_ONLY",
                              "userAnswer": label["userAnswer"], "reviewedAt": label.get("reviewedAt"),
                              "evidenceIdsUserApproved": False, "sourceGroupId": label.get("sourceGroupId"),
                              "evaluationGroupId": label.get("evaluationGroupId")})
        else:
            excluded.append({"caseId": case_id, "status": "SCOPED_APPROVAL_DIAGNOSTIC_ONLY",
                             "previousApprovedDecision": label["expectedDecision"],
                             "presentedExcerptIds": presented, "fullWindowLineCount": len(rows),
                             "approvedPresentedLineCount": len(presented),
                             "fullWindowCoverageExplicitlyApproved": set(presented) == set(by_id),
                             "approvalRevoked": False, "includeInMainScore": False})
    require(registry["focusCase"] in diagnostic, "FOCUS_MUST_BE_DIAGNOSTIC")
    focus_id = registry["focusCase"]
    focus = {"caseId": focus_id, "status": "FULL_WINDOW_LABEL_REVIEW_PENDING_NOT_NEW_GOLD",
             "expectedDecision": None, "source": sources[focus_id], "priorApproval": labels[focus_id],
             "prohibitedInferences": ["fixed speaker identity", "actual exposure", "coercion", "automatic PASS for any term definition"]}
    added_inputs, added_labels, focused = focused_units(root, registry, sources)
    require(set(c["caseId"] for c in inputs).isdisjoint(c["caseId"] for c in added_inputs), "ACTIVE_UNIT_ID_COLLISION")
    inputs.extend(added_inputs)
    decisions.extend(added_labels)
    supplements, supplemental_labels = supplemental_units(root, registry)
    require(set(c["caseId"] for c in inputs).isdisjoint(c["caseId"] for c in supplements), "SUPPLEMENTAL_ID_COLLISION")
    inputs.extend(supplements)
    decisions.extend(supplemental_labels)
    if focused:
        if focused["sourceReviewId"] == focus_id:
            focus["status"] = "ANCHOR_UNITS_APPROVED_NOT_WHOLE_WINDOW_OR_VIDEO_GOLD"
            focus["approvedUnitIds"] = focused["approvedUnitIds"]
        for case in excluded:
            if case["caseId"] == focused["sourceReviewId"]:
                case["supersededByApprovedUnits"] = focused["approvedUnitIds"]
    materials = []
    for item in registry["otherMaterials"]:
        data = read(root, item["path"])
        value = json.loads(data)
        if "cases" in item:
            require(len(value["cases"]) == item["cases"], "FIXTURE_COUNT_CHANGED")
        materials.append({**item, "sha256": sha(data), "includeInMainScore": False})
    history = []
    for relative in registry["historyRoots"]:
        path = (root / relative).resolve()
        require(path.is_relative_to(root.resolve()) and path.is_dir(), "HISTORY_ROOT_INVALID")
        history.append({"path": relative, "jsonFiles": sum(1 for _ in path.rglob("*.json")),
                        "role": "IMMUTABLE_REPRODUCTION_HISTORY_NOT_ACTIVE_SUITE", "relocated": False})
    verified_source_groups = {row["sourceGroupId"] for row in decisions if row.get("sourceGroupId")}
    evaluation_groups = {row["evaluationGroupId"] for row in decisions if row.get("evaluationGroupId")}
    manifest = {"schemaVersion": "active-evaluation-bundle-2", "suiteId": registry["activeSuite"],
                "purpose": registry["purpose"], "activeCases": [c["caseId"] for c in inputs], "activeCount": len(inputs),
                "sourcePacketSha256": registry["sourcePacket"]["sha256"], "humanReviewSha256": registry["humanReview"]["sha256"],
                "registryCanonicalSha256": sha(canonical(registry)), "inputsCanonicalSha256": sha(canonical(inputs)),
                "labelsCanonicalSha256": sha(canonical(decisions)), "independentGold": False,
                "verifiedMediaSourceGroupCount": len(verified_source_groups),
                "evaluationGroupCount": len(evaluation_groups),
                "providerCalls": 0, "runtimePromotion": False,
                "focusCase": focus_id, "excludedFromMainScore": excluded}
    if focused:
        manifest["approvedFocusedReview"] = focused
        manifest["syntheticConditionCount"] = len(active) + len(supplements)
        manifest["storedSttIssueCount"] = len(added_inputs)
    if supplements:
        manifest["supplementalContrastSha256"] = registry["supplementalContrasts"]["sha256"]
    payloads = {"inputs.json": inputs, "labels.json": decisions, "manifest.json": manifest,
                "focus-R01.json": focus, "inventory.json": {"materials": materials, "history": history}}
    if registry.get("baselineSources"):
        payloads["baseline-snapshot.json"] = {
            "status": "CURRENT_LOCAL_SOURCE_CONFIGURATION_NOT_MEASURED_BASELINE",
            "files": [{"path": p, "sha256": sha(read(root, p))} for p in registry["baselineSources"]],
            "environmentOverridesCaptured": False, "deploymentVerified": False,
            "measuredSuiteResults": None, "workflow": registry.get("workflow"), "providerCalls": 0}
    return payloads


def write_new(root, output, payloads):
    output = output.absolute()
    allowed = (root / "datasets/controversy/evaluation").resolve()
    require(output.parent.resolve() == allowed and not output.exists(), "NEW_PRIVATE_OUTPUT_REQUIRED")
    output.mkdir(mode=0o700)
    for name, value in payloads.items():
        with (output / name).open("x", encoding="utf-8") as stream:
            os.chmod(output / name, 0o600)
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write("\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    registry = json.loads(read(args.root, "tools/evaluation-suites.json"))
    payloads = prepare(args.root, registry)
    write_new(args.root, args.output, payloads)
    print(f"EVALUATION_PREPARED active={len(payloads['inputs.json'])} "
          f"scopedDiagnostics={len(payloads['manifest.json']['excludedFromMainScore'])} "
          "providerCalls=0; no sources deleted or relabeled")


if __name__ == "__main__":
    main()
