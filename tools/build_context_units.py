"""Build an explicit context-unit PREVIEW; never edits incidents or the active runtime.

Original statements remain research records. Authored routing text, source-linked
utterances, interpretation and synthetic controls are separate inputs. No network,
model, inferred transcript correction, approval promotion or default activation.
"""
import argparse
import copy
import hashlib
import json
import re
from pathlib import Path

from build_review_guidelines import write_private
from review_context_pilot import parse, read_bytes, require, text
from review_dataset_catalog import inside

ROOT = Path(__file__).resolve().parents[1] / "datasets/controversy"
ID = re.compile(r"[A-Za-z0-9_-]{1,64}(?:#[A-Za-z0-9_-]{1,64})?\Z")


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def linked_lines(root, unit):
    source = unit["source"]
    raw = read_bytes(inside(root, source["path"]))
    require(digest(raw) == source["sha256"], "UNIT_SOURCE_HASH_MISMATCH")
    doc = parse(raw)
    if source["kind"] == "STORED_TRANSCRIPT_SNAPSHOT":
        require(doc.get("schemaVersion") == "context-transcript-source-1"
                and doc.get("familyId") == unit["familyId"] and doc.get("audioVerified") is False,
                "UNIT_TRANSCRIPT_SOURCE_REQUIRED")
        lines = doc["segments"]
    elif source["kind"] == "SAVED_VERIFICATION_PLAN":
        tasks = [t for t in doc["tasks"] if t["spec"]["id"] == source["taskId"]]
        require(len(tasks) == 1, "UNIT_SOURCE_TASK_REQUIRED")
        lines = json.loads(tasks[0]["input"])["raw"]
    elif source["kind"] == "CANONICAL_STATEMENT":
        require(doc["incidentId"] == unit["familyId"], "UNIT_SOURCE_FAMILY_MISMATCH")
        statements = [s for s in doc["statements"] if s["id"] == unit["parentCaseId"]]
        require(len(statements) == 1, "UNIT_SOURCE_STATEMENT_REQUIRED")
        lines = statements[0]["legacyCard"]["evidence"]["segments"]
    else:
        require(False, "UNKNOWN_UNIT_SOURCE_KIND")
    index = {line["id"]: line for line in lines}
    require(len(index) == len(lines), "UNIT_SOURCE_DUPLICATE_ID")
    ids = unit["utteranceIds"]
    require(isinstance(ids, list) and 1 <= len(ids) <= 8 and len(ids) == len(set(ids))
            and all(i in index for i in ids), "UNIT_UTTERANCE_IDS_INVALID")
    positions = {line["id"]: i for i, line in enumerate(lines)}
    require([positions[i] for i in ids] == sorted(positions[i] for i in ids), "UNIT_UTTERANCE_ORDER_INVALID")
    selected = [copy.deepcopy(index[i]) for i in ids]
    require(all(text(line.get("text"), 350) for line in selected), "UNIT_RAW_TEXT_INVALID")
    for role in ("situationIds", "evaluationIds"):
        chosen = unit["roles"].get(role)
        require(isinstance(chosen, list) and len(chosen) == len(set(chosen)) and set(chosen) <= set(ids)
                and (role != "evaluationIds" or chosen), "UNIT_ROLE_INVALID")
    require(set(unit["roles"]["situationIds"] + unit["roles"]["evaluationIds"]) == set(ids),
            "UNIT_UNASSIGNED_UTTERANCE")
    return selected


def compile_preview(base, units, catalog, root):
    require(base.get("schemaVersion") == "review-guidelines-4", "KNOWN_BASELINE_REQUIRED")
    require(catalog.get("schemaVersion") == "context-routing-keys-1", "ROUTING_CATALOG_REQUIRED")
    known = {r["id"] for r in catalog["keys"]}
    require(len(known) == len(catalog["keys"]) and all(ID.fullmatch(k) and "#" not in k for k in known), "ROUTING_KEY_INVALID")
    source = {e["id"]: e for e in base["examples"]}
    rules = {r["id"]: r for r in base["guidelines"]}
    out = copy.deepcopy(base)
    replaced, seen, examples, links = set(), set(), [], []
    for u in units:
        require(u.get("schemaVersion") == "context-reference-unit-1" and u.get("status") == "DRAFT"
                and u.get("humanVerified") is False and u.get("trainingUseAuthorized") is False, "UNIT_DRAFT_REQUIRED")
        uid, parent = u.get("id"), u.get("parentCaseId")
        require(isinstance(uid, str) and ID.fullmatch(uid) and uid not in seen and uid not in source
                and parent in source and u.get("familyId") == source[parent]["familyId"], "UNIT_IDENTITY_INVALID")
        seen.add(uid)
        routing = u.get("routingKeys")
        require(isinstance(routing, list) and 1 <= len(routing) <= 4 and len(routing) == len(set(routing))
                and set(routing) <= known, "UNIT_ROUTING_INVALID")
        require(text(u.get("retrievalText"), 8000) and text(u.get("interpretation"), 350)
                and text(u.get("boundary"), 350), "UNIT_TEXT_INVALID")
        contrasts = u.get("normalContrasts")
        require(isinstance(contrasts, list) and 1 <= len(contrasts) <= 4
                and all(c.get("kind") == "SYNTHETIC_DEVELOPMENT_CONTROL" and text(c.get("text"), 300)
                        for c in contrasts), "UNIT_CONTROL_PROVENANCE_REQUIRED")
        lines = linked_lines(root, u)
        raw_by_id = {line["id"]: line["text"] for line in lines}
        flow = []
        for line in lines:
            labels = [label for role, label in (("situationIds", "상황"), ("evaluationIds", "평가"))
                      if line["id"] in u["roles"][role]]
            if labels:
                flow.append("·".join(labels) + " 원문: " + raw_by_id[line["id"]])
        require(1 <= len(flow) <= 8 and all(text(s, 350) for s in flow), "UNIT_FLOW_LIMIT")
        context = {"coverage": "SELECTED_EXCERPTS_NOT_FULL_TRANSCRIPT", "flow": flow,
                   "criticismHypotheses": [u["interpretation"]],
                   "missingContext": [u["boundary"], "발췌 STT이며 원음·화자·억양·독립 검수는 확인하지 않았다. 과거 사례를 현재 원문 근거로 복사하지 않는다."],
                   "sourceInterpretations": [],
                   "normalContrasts": ["합성 개발 대조(실제 관측 아님): " + c["text"] for c in contrasts]}
        example = {"id": uid, "familyId": u["familyId"], "mechanismIds": source[parent]["mechanismIds"],
                   "channels": source[parent]["channels"], "context": context, "routingKeys": routing,
                   "retrievalView": {"version": "context-unit-retrieval-1", "basis": "AUTHORED_ROUTING_NOT_SOURCE_EVIDENCE",
                                     "text": u["retrievalText"], "sha256": digest(u["retrievalText"].encode())}}
        examples.append(example)
        links.append({"unitId": uid, "parentCaseId": parent, "source": copy.deepcopy(u["source"]), "utterances": lines})
        replaced.add(parent)
    # Uncurated examples remain visible, never silently relabeled as curated units.
    for original in base["examples"]:
        if original["id"] in replaced:
            continue
        e = copy.deepcopy(original)
        search = " ".join(e["context"]["flow"] + e["context"]["criticismHypotheses"]
                          + [rules[mid]["condition"] for mid in e["mechanismIds"]])
        e["retrievalView"] = {"version": "frozen-legacy-lexical-1", "basis": "FROZEN_LEGACY_NOT_SOURCE_EVIDENCE",
                              "text": search, "sha256": digest(search.encode())}
        e["routingKeys"] = []
        e["context"]["normalContrasts"] = []
        examples.append(e)
    out["schemaVersion"] = "review-guidelines-6"
    out["version"] = "2026-10-15-context-units-v1"
    out["examples"] = examples
    out["sourceCases"] = [{"caseId": e["id"], "familyId": e["familyId"], "status": "UNREVIEWED_DRAFT"} for e in examples]
    for r in out["guidelines"]:
        r["sourceCaseIds"] = [e["id"] for e in examples if r["id"] in e["mechanismIds"]]
    provenance = {"baseRuntimeSha256": digest(json.dumps(base, sort_keys=True, ensure_ascii=False).encode()),
                  "units": units, "routingCatalog": catalog}
    sha = digest(json.dumps(provenance, sort_keys=True, ensure_ascii=False).encode())
    out["sourceBundleSha256"] = sha
    out["contextDictionarySha256"] = sha
    return out, {"schemaVersion": "context-unit-preview-manifest-1", "activeRuntimeChanged": False,
                 "sourceInputSha256": sha, "unitCount": len(units), "replacedExamples": sorted(replaced),
                 "runtimeCanonicalSha256": digest(json.dumps(out, sort_keys=True, ensure_ascii=False).encode()),
                 "retainedLegacyCount": len(examples) - len(units), "examples": len(examples), "sourceLinks": links}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--dataset-root", type=Path, default=ROOT)
    p.add_argument("--output", type=Path, required=True, help="New directory below experiments; never active runtime")
    args = p.parse_args()
    root = args.dataset_root.resolve()
    output = args.output.absolute()
    experiments = (root / "experiments").resolve()
    require(not output.exists() and output.parent.resolve().is_relative_to(experiments), "NEW_PRIVATE_PREVIEW_REQUIRED")
    files = sorted((root / "context-units").glob("*.json"))
    require(files, "CONTEXT_UNITS_REQUIRED")
    base = parse(read_bytes(root / "guidelines/runtime.json"))
    catalog = parse(read_bytes(root / "definitions/context-routing.json"))
    runtime, manifest = compile_preview(base, [parse(read_bytes(f)) for f in files], catalog, root)
    manifest["compilerSha256"] = digest(Path(__file__).read_bytes())
    manifest["baseRuntimeFileSha256"] = digest(read_bytes(root / "guidelines/runtime.json"))
    require(len(json.dumps(runtime, ensure_ascii=False).encode()) <= 1_048_576, "PREVIEW_TOO_LARGE")
    output.mkdir(mode=0o700)
    write_private(output / "runtime.json", runtime)
    write_private(output / "manifest.json", manifest)
    print(json.dumps({k: v for k, v in manifest.items() if k != "sourceLinks"}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
