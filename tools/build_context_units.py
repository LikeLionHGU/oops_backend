"""Build an explicit context-unit PREVIEW; never edits incidents or the active runtime.

Original statements remain research records. Authored routing text, source-linked
utterances, interpretation and synthetic controls are separate inputs. Schema v2
also allows explicitly approved development boundaries and separately labelled
developer frame-review drafts without inventing a public controversy incident
or replacing its research record. OCR drafts retain artifact hashes and raw lines.
No network,
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


def validate_control_link(root, control):
    """Optional approved development provenance stays out of the model payload."""
    if "approvalSource" not in control:
        return
    link = control["approvalSource"]
    require(isinstance(link, dict) and set(link) == {"path", "sha256", "caseId"}, "CONTROL_LINK_INVALID")
    raw = read_bytes(inside(root, link["path"]))
    require(digest(raw) == link["sha256"], "CONTROL_SOURCE_HASH_MISMATCH")
    source = parse(raw)
    require(source.get("status") == "USER_APPROVED_DEVELOPMENT_BOUNDARIES"
            and source.get("trainingUseAuthorized") is False, "CONTROL_APPROVAL_REQUIRED")
    matches = [c for c in source.get("normalDrafts", []) if c.get("id") == link["caseId"]]
    require(len(matches) == 1, "CONTROL_CASE_REQUIRED")
    case = matches[0]
    require(case.get("humanApproved") is True and case.get("expectedDecision") == "PASS"
            and case.get("sourceKind") == "SYNTHETIC_DEVELOPMENT_CONTROL", "CONTROL_APPROVAL_REQUIRED")
    require(control["text"] == " ".join(s["text"] for s in case["raw"]), "CONTROL_TEXT_MISMATCH")


def linked_lines(root, unit):
    source = unit["source"]
    raw = read_bytes(inside(root, source["path"]))
    require(digest(raw) == source["sha256"], "UNIT_SOURCE_HASH_MISMATCH")
    doc = parse(raw)
    if source["kind"] == "STORED_TRANSCRIPT_SNAPSHOT":
        require(doc.get("schemaVersion") == "context-transcript-source-1"
                and doc.get("familyId") == unit["familyId"] and doc.get("audioVerified") is False,
                "UNIT_TRANSCRIPT_SOURCE_REQUIRED")
        if unit.get("schemaVersion") == "context-reference-unit-2":
            require(doc.get("developmentReview", {}).get("decision") == unit["developmentDecision"]
                    and doc["developmentReview"].get("basis") == "USER_APPROVED_DEVELOPMENT_BOUNDARY"
                    and text(doc["developmentReview"].get("scope"), 350),
                    "UNIT_DEVELOPMENT_APPROVAL_REQUIRED")
        lines = doc["segments"]
    elif source["kind"] == "STORED_OCR_FRAME_SNAPSHOT":
        require(doc.get("schemaVersion") == "context-ocr-frame-source-1"
                and doc.get("familyId") == unit["familyId"]
                and doc.get("userLabelApproved") is False
                and doc.get("developmentReview", {}).get("decision") == unit["developmentDecision"]
                and doc["developmentReview"].get("basis") == "DEVELOPER_FRAME_REVIEW_NOT_USER_LABEL"
                and text(doc["developmentReview"].get("scope"), 350), "UNIT_OCR_REVIEW_REQUIRED")
        for artifact in ("frame", "ocrSnapshot"):
            item = doc[artifact]
            require(digest(read_bytes(inside(root, item["path"]))) == item["sha256"],
                    "UNIT_OCR_ARTIFACT_HASH_MISMATCH")
        original = parse(read_bytes(inside(root, doc["ocrSnapshot"]["path"])))
        rows = original["data"]
        for line in doc["segments"]:
            require(type(line.get("sourceIndex")) is int
                    and 0 <= line["sourceIndex"] < len(rows), "UNIT_OCR_INDEX_REQUIRED")
            row = rows[line["sourceIndex"]]
            require(all(line.get(k) == row.get(k) for k in ("text", "startMs", "endMs"))
                    and row.get("frameUrl") == doc["frame"].get("url"), "UNIT_OCR_RAW_MISMATCH")
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
    require(isinstance(ids, list) and 1 <= len(ids) <= 12 and len(ids) == len(set(ids))
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


def relation_profiles(profiles, units):
    """Authored retrieval descriptions, never recovered quotes or promoted labels."""
    if profiles is None:
        return {}
    require(isinstance(profiles, list) and 1 <= len(profiles) <= len(units), "RELATION_PROFILES_REQUIRED")
    by_unit = {u["id"]: u for u in units}
    result = {}
    for p in profiles:
        uid = p.get("unitId")
        require(p.get("schemaVersion") == "context-relation-profile-1" and uid in by_unit
                and uid not in result and p.get("status") == "DRAFT" and p.get("humanVerified") is False
                and p.get("trainingUseAuthorized") is False, "RELATION_PROFILE_DRAFT_REQUIRED")
        u = by_unit[uid]
        require(p.get("sourceSha256") == u["source"]["sha256"]
                and p.get("utteranceIds") == u["utteranceIds"], "RELATION_PROFILE_SOURCE_MISMATCH")
        fields = ("situation", "target", "evaluation", "linkage")
        require(all(isinstance(p.get(f), str) and (not p[f] or text(p[f], 350)) for f in fields)
                and text(p["evaluation"], 350) and text(p["linkage"], 350), "RELATION_PROFILE_TEXT_INVALID")
        result[uid] = {"version": "authored-relation-search-1", "basis": "AUTHORED_RETRIEVAL_NOT_SOURCE_EVIDENCE",
                       "sourceSha256": p["sourceSha256"], "sourceIds": copy.deepcopy(p["utteranceIds"]),
                       **{f: p[f] for f in fields}}
    return result


def compile_preview(base, units, catalog, root, profiles=None):
    require(base.get("schemaVersion") == "review-guidelines-4", "KNOWN_BASELINE_REQUIRED")
    require(catalog.get("schemaVersion") == "context-routing-keys-1", "ROUTING_CATALOG_REQUIRED")
    known = {r["id"] for r in catalog["keys"]}
    require(len(known) == len(catalog["keys"]) and all(ID.fullmatch(k) and "#" not in k for k in known), "ROUTING_KEY_INVALID")
    source = {e["id"]: e for e in base["examples"]}
    rules = {r["id"]: r for r in base["guidelines"]}
    out = copy.deepcopy(base)
    relations = relation_profiles(profiles, units)
    replaced, seen, examples, links = set(), set(), [], []
    for u in units:
        standalone = u.get("schemaVersion") == "context-reference-unit-2"
        require(u.get("schemaVersion") in {"context-reference-unit-1", "context-reference-unit-2"} and u.get("status") == "DRAFT"
                and u.get("humanVerified") is False and u.get("trainingUseAuthorized") is False, "UNIT_DRAFT_REQUIRED")
        uid, parent = u.get("id"), u.get("parentCaseId")
        require(isinstance(uid, str) and ID.fullmatch(uid) and uid not in seen and uid not in source,
                "UNIT_IDENTITY_INVALID")
        if standalone:
            require(parent is None and isinstance(u.get("familyId"), str) and ID.fullmatch(u["familyId"])
                    and "#" not in u["familyId"] and u["familyId"] not in {e["familyId"] for e in source.values()}
                    and u.get("referencePurpose") == "DEVELOPMENT_BOUNDARY_NOT_PUBLIC_CONTROVERSY"
                    and u.get("developmentDecision") in {"PASS", "REVIEW_REQUIRED"}
                    and u["source"].get("kind") in {"STORED_TRANSCRIPT_SNAPSHOT", "STORED_OCR_FRAME_SNAPSHOT"},
                    "UNIT_DEVELOPMENT_BOUNDARY_REQUIRED")
            mechanisms = u.get("mechanismIds")
            require(isinstance(mechanisms, list) and mechanisms and len(set(mechanisms)) == len(mechanisms)
                    and all(m in rules for m in mechanisms), "UNIT_MECHANISMS_REQUIRED")
            channels = sorted({c for m in mechanisms for c in rules[m]["channels"]})
            if u["source"]["kind"] == "STORED_OCR_FRAME_SNAPSHOT":
                require("CAPTION" in channels, "UNIT_OCR_CHANNEL_REQUIRED")
                channels = ["CAPTION"]
        else:
            require(parent in source and u.get("familyId") == source[parent]["familyId"], "UNIT_IDENTITY_INVALID")
            mechanisms, channels = source[parent]["mechanismIds"], source[parent]["channels"]
        seen.add(uid)
        routing = u.get("routingKeys")
        require(isinstance(routing, list) and 1 <= len(routing) <= 4 and len(routing) == len(set(routing))
                and set(routing) <= known, "UNIT_ROUTING_INVALID")
        require(text(u.get("retrievalText"), 8000) and text(u.get("interpretation"), 350)
                and text(u.get("boundary"), 350), "UNIT_TEXT_INVALID")
        contrasts = u.get("normalContrasts")
        # A source-linked, approved PASS unit is itself a normal example. Do not
        # require invented synthetic dialogue merely to admit an observed case.
        minimum_contrasts = 0 if standalone and u["developmentDecision"] == "PASS" else 1
        require(isinstance(contrasts, list) and minimum_contrasts <= len(contrasts) <= 4
                and all(c.get("kind") == "SYNTHETIC_DEVELOPMENT_CONTROL" and text(c.get("text"), 300)
                        for c in contrasts), "UNIT_CONTROL_PROVENANCE_REQUIRED")
        for control in contrasts:
            validate_control_link(root, control)
        lines = linked_lines(root, u)
        raw_by_id = {line["id"]: line["text"] for line in lines}
        flow = []
        for line in lines:
            labels = [label for role, label in (("situationIds", "상황"), ("evaluationIds", "평가"))
                      if line["id"] in u["roles"][role]]
            if labels:
                flow.append("·".join(labels) + " 원문: " + raw_by_id[line["id"]])
        require(1 <= len(flow) <= 12 and all(text(s, 350) for s in flow), "UNIT_FLOW_LIMIT")
        context = {"coverage": "SELECTED_EXCERPTS_NOT_FULL_TRANSCRIPT", "flow": flow,
                   "criticismHypotheses": [u["interpretation"]],
                   "missingContext": [u["boundary"], "발췌 STT이며 원음·화자·억양·독립 검수는 확인하지 않았다. 과거 사례를 현재 원문 근거로 복사하지 않는다."],
                   "sourceInterpretations": [],
                   "normalContrasts": ["합성 개발 대조(실제 관측 아님): " + c["text"] for c in contrasts]}
        if u["source"]["kind"] == "STORED_TRANSCRIPT_SNAPSHOT":
            source_doc = parse(read_bytes(inside(root, u["source"]["path"])))
            if source_doc.get("sourceKind") == "USER_SUPPLIED_SCRIPT_NOT_AUDIO_VERIFIED":
                context["missingContext"][-1] = "사용자 제공 자막 스크립트 발췌이며 원음·화자·억양·독립 검수는 확인하지 않았다. 보충 관계 설명은 사용자 문맥이지 전사 사실이 아니다. 과거 사례를 현재 원문 근거로 복사하지 않는다."
        if standalone:
            review_label = "사용자 승인 개발"
            if u["source"]["kind"] == "STORED_OCR_FRAME_SNAPSHOT":
                review_label = "개발자 프레임 검토 초안(사용자 정답 미승인)"
                context["missingContext"][-1] = "선택 OCR 발췌와 개발자 프레임 검토만 사용했다. OCR 원문을 교정하지 않으며 새 입력의 화면·역할은 별도로 확인한다."
            context["missingContext"].insert(0, "공개 논란 사건이 아닌 " + review_label + " 경계. 독립 평가·학습 정답이 아니다.")
            if u["developmentDecision"] == "PASS":
                context["criticismHypotheses"] = []
                context["flow"].append(review_label + " 정상 경계(원문 아님·독립 검수 아님): " + u["interpretation"])
                require(len(context["flow"]) <= 12, "UNIT_FLOW_LIMIT")
            else:
                context["criticismHypotheses"] = [review_label + " 검토 경계(독립 검수 아님): " + u["interpretation"]]
        example = {"id": uid, "familyId": u["familyId"], "mechanismIds": mechanisms,
                   "channels": channels, "context": context, "routingKeys": routing,
                   "retrievalView": {"version": "context-unit-retrieval-1", "basis": "AUTHORED_ROUTING_NOT_SOURCE_EVIDENCE",
                                     "text": u["retrievalText"], "sha256": digest(u["retrievalText"].encode())}}
        require(all(text(s, 350) for s in context["flow"] + context["criticismHypotheses"])
                and all(text(s, 350) for s in context["normalContrasts"]), "UNIT_COMPILED_TEXT_LIMIT")
        examples.append(example)
        if uid in relations:
            example["relationProfile"] = copy.deepcopy(relations[uid])
        links.append({"unitId": uid, "parentCaseId": parent, "source": copy.deepcopy(u["source"]), "utterances": lines})
        if not standalone:
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
    out["schemaVersion"] = "review-guidelines-7" if relations else "review-guidelines-6"
    out["version"] = "2026-10-15-context-relations-v1" if relations else "2026-10-15-context-units-v1"
    if any(u.get("schemaVersion") == "context-reference-unit-2" for u in units):
        out["version"] = "2026-10-22-development-boundaries-v1"
    if any(u.get("source", {}).get("kind") == "STORED_OCR_FRAME_SNAPSHOT" for u in units):
        out["version"] = "2026-10-22-development-ocr-boundaries-v2"
    out["examples"] = examples
    out["sourceCases"] = [{"caseId": e["id"], "familyId": e["familyId"], "status": "UNREVIEWED_DRAFT"} for e in examples]
    for r in out["guidelines"]:
        r["sourceCaseIds"] = [e["id"] for e in examples if r["id"] in e["mechanismIds"]]
    provenance = {"baseRuntimeSha256": digest(json.dumps(base, sort_keys=True, ensure_ascii=False).encode()),
                  "units": units, "routingCatalog": catalog}
    if profiles is not None:
        provenance["authoredRelationProfiles"] = profiles
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
    p.add_argument("--relation-profiles", type=Path, help="Explicit authored profile directory below dataset root; opt-in v7")
    args = p.parse_args()
    root = args.dataset_root.resolve()
    output = args.output.absolute()
    experiments = (root / "experiments").resolve()
    require(not output.exists() and output.parent.resolve().is_relative_to(experiments), "NEW_PRIVATE_PREVIEW_REQUIRED")
    files = sorted((root / "context-units").glob("*.json"))
    require(files, "CONTEXT_UNITS_REQUIRED")
    # Rebuild the v4 base from source even when the active runtime is already v7.
    from build_incident_dataset import generate
    base, _ = generate(root, apply_runtime_selection=False)
    catalog = parse(read_bytes(root / "definitions/context-routing.json"))
    profiles = None
    if args.relation_profiles is not None:
        directory = args.relation_profiles.resolve()
        require(directory.is_relative_to(root), "RELATION_PROFILES_MUST_BE_PRIVATE")
        profiles = [parse(read_bytes(f)) for f in sorted(directory.glob("*.json"))]
    runtime, manifest = compile_preview(base, [parse(read_bytes(f)) for f in files], catalog, root, profiles)
    manifest["relationProfileCount"] = len(profiles or [])
    manifest["compilerSha256"] = digest(Path(__file__).read_bytes())
    manifest["baseRuntimeFileSha256"] = digest((json.dumps(base, ensure_ascii=False, indent=2) + "\n").encode())
    manifest["activeRuntimeFileSha256"] = digest(read_bytes(root / "guidelines/runtime.json"))
    require(len(json.dumps(runtime, ensure_ascii=False).encode()) <= 1_048_576, "PREVIEW_TOO_LARGE")
    output.mkdir(mode=0o700)
    write_private(output / "runtime.json", runtime)
    write_private(output / "manifest.json", manifest)
    print(json.dumps({k: v for k, v in manifest.items() if k != "sourceLinks"}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
