"""Offline ID-coverage audit of saved candidate evidence; never judges or repairs content.

Roles are authored annotations tied to one exact source snapshot, not inferred
facts, mandatory evidence, a risk score, or a runtime filtering rule.
"""
import argparse
import hashlib
import json
from pathlib import Path


def require(value, code):
    if not value:
        raise ValueError(code)


def unique_ids(values, code):
    require(isinstance(values, list) and all(isinstance(x, str) and x for x in values), code)
    require(len(values) == len(set(values)), code)
    return values


def coverage(selected, permitted, roles):
    require(set(selected) <= set(permitted), "EVIDENCE_OUTSIDE_PERMITTED_WINDOW")
    selected_set, allowed = set(selected), set(permitted)
    return [{"roleId": role["id"], "label": role["label"],
             "selectedIds": [i for i in role["segmentIds"] if i in selected_set],
             "availableButUnselectedIds": [i for i in role["segmentIds"] if i in allowed - selected_set],
             "outsidePermittedWindowIds": [i for i in role["segmentIds"] if i not in allowed]}
            for role in roles]


def evidence_ids(evidence, allowed):
    require(isinstance(evidence, list) and all(isinstance(e, dict) and e.get("segmentId") in allowed for e in evidence),
            "INVALID_EVIDENCE_ID")
    # Returned evidence may repeat an ID for different roles; count it once.
    return list(dict.fromkeys(e["segmentId"] for e in evidence))


def audit(saved, role_map, source_bytes):
    require(saved.get("state") == "COMPLETE", "COMPLETE_SAVED_RESULTS_REQUIRED")
    require(role_map.get("schemaVersion") == "candidate-evidence-role-map-1"
            and role_map.get("basis") == "AUTHORED_STRUCTURAL_ANNOTATION_NOT_SEMANTIC_GOLD",
            "AUTHORED_ROLE_MAP_REQUIRED")
    require(isinstance(role_map.get("caseId"), str) and role_map["caseId"], "CASE_ID_REQUIRED")
    require(hashlib.sha256(source_bytes).hexdigest() == role_map.get("sourceSha256"), "SOURCE_HASH_MISMATCH")
    source = json.loads(source_bytes)
    require(isinstance(source.get("segments"), list) and source["segments"], "SOURCE_SEGMENTS_REQUIRED")
    raw = [{"id": f"stt-index-{i}", "startMs": s["startMs"], "endMs": s["endMs"], "text": s["text"]}
           for i, s in enumerate(source["segments"])]
    raw_by_id = {s["id"]: s for s in raw}
    roles = role_map.get("roles")
    require(isinstance(roles, list) and 1 <= len(roles) <= 32 and all(isinstance(r, dict) for r in roles),
            "BOUNDED_ROLES_REQUIRED")
    require(len({r.get("id") for r in roles}) == len(roles), "DUPLICATE_ROLE_ID")
    for r in roles:
        require(isinstance(r.get("id"), str) and r["id"] and isinstance(r.get("label"), str), "ROLE_ID_LABEL_REQUIRED")
        unique_ids(r.get("segmentIds"), "ROLE_IDS_REQUIRED")
        require(r["segmentIds"] and set(r["segmentIds"]) <= set(raw_by_id), "ROLE_OUTSIDE_SOURCE")
    rows = [r for r in saved.get("results", []) if r.get("caseId") == role_map.get("caseId")]
    require(rows, "CASE_NOT_FOUND")
    output = []
    for row in rows:
        discovered = {}
        for call in row.get("calls", []):
            if call.get("kind") == "Discovery":
                for p in (call.get("output") or {}).get("candidates", []):
                    discovered[(p["anchorId"], p["axis"])] = evidence_ids(p["evidence"], raw_by_id)
        for number, call in enumerate(row.get("calls", []), 1):
            if call.get("kind") != "VerificationResult":
                continue
            data = call.get("input", {})
            require(data.get("raw") == raw, "VERIFICATION_RAW_SOURCE_MISMATCH")
            candidates = data.get("candidates") or ([data["candidate"]] if "candidate" in data else [])
            require(candidates, "VERIFICATION_CANDIDATES_REQUIRED")
            for candidate in candidates:
                permitted = unique_ids(candidate.get("segmentIds"), "PERMITTED_IDS_REQUIRED")
                require(set(permitted) <= set(raw_by_id) and candidate.get("anchorId") in permitted,
                        "WINDOW_OUTSIDE_SOURCE")
                selected = evidence_ids(candidate.get("proposedEvidence"), set(permitted))
                upstream = discovered.get((candidate["anchorId"], candidate["axis"]))
                require(row.get("discoveryInjected") is True or upstream is not None, "UPSTREAM_PROPOSAL_NOT_FOUND")
                returned = []
                for v in (call.get("output") or {}).get("verifications", []):
                    if v.get("candidateId") == candidate["candidateId"]:
                        a = v["assessment"]
                        returned.append({"decision": a.get("decision"),
                                         "roles": coverage(evidence_ids(a.get("evidence"), set(permitted)), permitted, roles)})
                output.append({"mode": row["mode"], "callIndex": number, "candidateId": candidate["candidateId"],
                               "anchorId": candidate["anchorId"], "axis": candidate["axis"],
                               "permittedCount": len(permitted), "selectedEvidenceIds": selected,
                               "discoveryEvidenceIds": upstream,
                               "discoveryToVerificationEvidenceIdsPreserved": None if upstream is None else set(upstream) == set(selected),
                               "proposedRoles": coverage(selected, permitted, roles), "returned": returned})
    require(output, "NO_VERIFICATION_REQUESTS")
    return {"schemaVersion": "candidate-evidence-role-audit-1", "providerCalls": 0,
            "basis": role_map["basis"], "semanticCorrectnessValidated": False,
            "roleNecessityValidated": False, "runtimeDecisionModified": False,
            "caseId": role_map["caseId"], "sourceSha256": role_map["sourceSha256"], "requests": output}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--results", type=Path, required=True)
    p.add_argument("--role-map", type=Path, required=True)
    p.add_argument("--source-request", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    args = p.parse_args()
    result = audit(json.loads(args.results.read_bytes()), json.loads(args.role_map.read_bytes()), args.source_request.read_bytes())
    with args.output.open("x", encoding="utf-8") as out:
        json.dump(result, out, ensure_ascii=False, indent=2)
        out.write("\n")
    print(json.dumps({"providerCalls": 0, "requests": len(result["requests"]), "output": str(args.output)}))


if __name__ == "__main__":
    main()
