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
                              "evidenceIdsUserApproved": False})
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
    manifest = {"schemaVersion": "active-evaluation-bundle-1", "suiteId": registry["activeSuite"],
                "purpose": registry["purpose"], "activeCases": list(active), "activeCount": len(inputs),
                "sourcePacketSha256": registry["sourcePacket"]["sha256"], "humanReviewSha256": registry["humanReview"]["sha256"],
                "registryCanonicalSha256": sha(canonical(registry)), "inputsCanonicalSha256": sha(canonical(inputs)),
                "labelsCanonicalSha256": sha(canonical(decisions)), "independentGold": False,
                "realVideoFamiliesInActiveSuite": 0, "providerCalls": 0, "runtimePromotion": False,
                "focusCase": focus_id, "excludedFromMainScore": excluded}
    return {"inputs.json": inputs, "labels.json": decisions, "manifest.json": manifest,
            "focus-R01.json": focus, "inventory.json": {"materials": materials, "history": history}}


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
