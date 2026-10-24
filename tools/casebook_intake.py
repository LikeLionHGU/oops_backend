#!/usr/bin/env python3
"""Offline approved development-boundary intake. Preview first; never calls AI.

ADD/UPDATE project into existing context source/unit/relation contracts; HOLD is
review-only. --apply requires the current preview hash and publishes via the
existing builder. Source text, identity, approval and label are immutable on UPDATE.
"""
import argparse
import copy
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
from pathlib import Path, PurePosixPath

import build_incident_dataset as builder
from build_review_guidelines import write_private
from review_context_pilot import parse, read_bytes, require, text, PilotError

REVISION = "casebook-intake-tool-1"
SLUG = re.compile(r"[a-z][a-z0-9-]{0,63}\Z")
IDENTITY = re.compile(r"[A-Za-z0-9_-]{1,64}(?:#[A-Za-z0-9_-]{1,64})?\Z")


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode()


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def implementation_hashes():
    repo = Path(__file__).resolve().parents[1]
    names = ["tools/" + name + ".py" for name in ("casebook_intake", "build_incident_dataset", "build_context_units",
             "build_context_dictionary", "build_review_guidelines", "incidents_migration", "review_context_pilot")]
    names += ["oops-backend/src/main/java/com/example/oops/analyzer/" + name + ".java" for name in
              ("ReviewGuidelineLibrary", "ContextExampleSelector", "ContextRelationRetrieval", "ContextBoundarySupplement",
               "ContextReferenceBalance", "StructuredContextSearch")]
    names += ["oops-backend/src/test/java/com/example/oops/analyzer/CasebookIntakeAudit.java", "oops-backend/build.gradle"]
    return {name: digest(read_bytes(repo / name)) for name in names}


def exact(value, required, optional=()):
    require(isinstance(value, dict) and set(required) <= set(value)
            and set(value) <= set(required) | set(optional), "INVALID_FIELDS")


def local(root, relative):
    require(isinstance(relative, str) and relative and "\\" not in relative
            and "\0" not in relative, "LOCAL_PATH_REQUIRED")
    parts = PurePosixPath(relative)
    require(not parts.is_absolute() and ".." not in parts.parts
            and parts.as_posix() == relative, "UNSAFE_LOCAL_PATH")
    current = root
    for part in parts.parts:
        current = current / part
        require(not current.is_symlink(), "SYMLINK_REFUSED")
    require(current.resolve() != root.resolve() and current.resolve().is_relative_to(root.resolve()),
            "PATH_OUTSIDE_ROOT")
    return current


def reference(root, ref):
    exact(ref, ("path", "sha256"))
    require(isinstance(ref["sha256"], str) and re.fullmatch(r"[0-9a-f]{64}", ref["sha256"]), "HASH_REQUIRED")
    raw = read_bytes(local(root, ref["path"]))
    require(digest(raw) == ref["sha256"], "REFERENCE_HASH_MISMATCH")
    return parse(raw)


def provenance_refs(spec):
    refs = [{k: spec[name][k] for k in ("path", "sha256")} for name in ("source", "approval")]
    controls = spec.get("normalContrasts", [])
    require(isinstance(controls, list), "EXPLICIT_CONTROLS_REQUIRED")
    for control in controls:
        exact(control, ("kind", "text"), ("approvalSource",))
        require(control["kind"] == "SYNTHETIC_DEVELOPMENT_CONTROL" and text(control["text"], 300),
                "LABELLED_SYNTHETIC_CONTROL_REQUIRED")
        if "approvalSource" in control:
            exact(control["approvalSource"], ("path", "sha256", "caseId"))
            refs.append({k: control["approvalSource"][k] for k in ("path", "sha256")})
    return refs


def baseline(root):
    manifest = builder.verify(root)
    hashes = manifest["inputHashes"]
    # Follow the builder inventory, but reject symlinks/aliases before any write.
    for name in hashes:
        local(root, name)
    require(builder.runtime_target(root) == "review-guidelines-7", "V7_DATASET_REQUIRED")
    return {"inputs": hashes, "runtime": digest(read_bytes(local(root, "guidelines/runtime.json"))),
            "manifest": digest(read_bytes(local(root, "guidelines/build-manifest.json")))}


def approval(root, spec, selected):
    ref = spec["approval"]
    exact(ref, ("path", "sha256", "eventIds"))
    document = reference(root, {k: ref[k] for k in ("path", "sha256")})
    ids = ref["eventIds"]
    require(isinstance(ids, list) and ids and all(text(i, 100) for i in ids)
            and len(ids) == len(set(ids)), "APPROVAL_EVENT_IDS_REQUIRED")
    require(isinstance(document.get("reviews"), list), "APPROVAL_REVIEWS_REQUIRED")
    for event in ids:
        matches = [r for r in document["reviews"] if r.get("eventId") == event or event in r.get("eventIds", [])]
        require(len(matches) == 1, "UNIQUE_APPROVAL_REQUIRED")
        r = matches[0]
        require(r.get("decision") == spec["decision"] and text(r.get("scope"), 2000)
                and (text(r.get("userReply"), 2000) or text(r.get("userConfirmation"), 2000)),
                "EXPLICIT_MATCHING_USER_REVIEW_REQUIRED")
        anchors = [r["anchorId"]] if r.get("anchorId") else r.get("anchorIds", [])
        require(anchors and set(anchors) <= set(selected), "APPROVAL_ANCHOR_OUTSIDE_SELECTION")
    return ref


def source_lines(root, spec):
    src = spec["source"]
    exact(src, ("path", "sha256", "format", "kind", "videoId"), ("url",))
    require(src["kind"] in {"USER_SUPPLIED_SCRIPT_NOT_AUDIO_VERIFIED", "STT_NOT_AUDIO_VERIFIED"},
            "UNVERIFIED_TIMED_SOURCE_KIND_REQUIRED")
    require(type(src["videoId"]) is int and src["videoId"] > 0, "SOURCE_VIDEO_ID_REQUIRED")
    require("url" not in src or isinstance(src["url"], str) and src["url"].startswith("https://"), "HTTPS_OR_NO_URL")
    original = reference(root, {k: src[k] for k in ("path", "sha256")})
    keys = {"RESULT_TRANSCRIPT": "transcript", "API_TRANSCRIPT": "data", "TIMED_SEGMENTS": "segments"}
    require(src["format"] in keys, "TIMED_SNAPSHOT_FORMAT_REQUIRED")
    rows = original.get(keys[src["format"]])
    require(isinstance(rows, list), "SOURCE_SEGMENTS_REQUIRED")
    entries = spec["segments"]
    require(isinstance(entries, list) and 1 <= len(entries) <= (11 if spec["decision"] == "PASS" else 12),
            "COMPLETE_CONTEXT_MUST_FIT_UNIT_CONTRACT")
    lines, situations, evaluations = [], [], []
    indexes, ids = [], []
    for entry in entries:
        exact(entry, ("index", "id", "role"))
        i, sid, role = entry["index"], entry["id"], entry["role"]
        require(type(i) is int and 0 <= i < len(rows) and text(sid, 100)
                and role in {"SITUATION", "EVALUATION", "BOTH"}, "SOURCE_SELECTION_INVALID")
        row = rows[i]
        require(isinstance(row, dict) and sid == row.get("id", "stt-index-" + str(i)), "SOURCE_ID_MISMATCH")
        require(all(type(row.get(k)) is int for k in ("startMs", "endMs"))
                and 0 <= row["startMs"] <= row["endMs"] and text(row.get("text"), 350), "RAW_TIMING_OR_TEXT_INVALID")
        require(not lines or lines[-1]["endMs"] <= row["startMs"], "SOURCE_TIME_ORDER_INVALID")
        lines.append({"id": sid, **{k: row[k] for k in ("startMs", "endMs", "text")}})
        indexes.append(i); ids.append(sid)
        if role in {"SITUATION", "BOTH"}: situations.append(sid)
        if role in {"EVALUATION", "BOTH"}: evaluations.append(sid)
    require(indexes == sorted(set(indexes)) and len(set(ids)) == len(ids) and evaluations, "ORDERED_UNIQUE_ROLES_REQUIRED")
    return lines, {"situationIds": situations, "evaluationIds": evaluations}


def suggestions(root, query, excluded):
    def grams(value):
        value = re.sub(r"[^\w]", "", value.lower())
        return {value[i:i + 3] for i in range(max(0, len(value) - 2))}
    q = grams(query); ranked = []
    for file in sorted((root / "context-units").glob("*.json")):
        unit = parse(read_bytes(local(root, file.relative_to(root).as_posix())))
        if unit["id"] == excluded: continue
        g = grams(unit["retrievalText"]); shared = len(q & g)
        if shared: ranked.append({"unitId": unit["id"], "surfaceScore": round(shared / max(1, len(q | g)), 6)})
    return sorted(ranked, key=lambda x: (-x["surfaceScore"], x["unitId"]))[:5]


def plan(root, spec):
    root = Path(root).resolve()
    exact(spec, ("schemaVersion", "action", "slug", "id", "familyId", "sourceGroupId", "decision", "source",
                 "approval", "segments", "interpretation", "boundary"),
          ("mechanismIds", "routingKeys", "retrievalText", "relation", "normalContrasts", "relatedUnitIds",
           "expectedUnitSha256", "missingInformation"))
    require(spec["schemaVersion"] == "casebook-intake-1" and spec["action"] in {"ADD", "UPDATE", "HOLD"}, "INTAKE_ACTION_REQUIRED")
    require(isinstance(spec["slug"], str) and SLUG.fullmatch(spec["slug"]), "SAFE_SLUG_REQUIRED")
    require(all(isinstance(spec[k], str) and IDENTITY.fullmatch(spec[k]) for k in ("id", "familyId", "sourceGroupId"))
            and "#" not in spec["familyId"] and "#" not in spec["sourceGroupId"], "IDENTITY_REQUIRED")
    require(spec["decision"] in {"PASS", "REVIEW_REQUIRED", "UNCERTAIN"}, "DECISION_REQUIRED")
    require((spec["action"] == "HOLD") == (spec["decision"] == "UNCERTAIN"), "UNCERTAIN_IS_HOLD_ONLY")
    require(all(text(spec[k], 350) for k in ("interpretation", "boundary")), "AUTHORED_BOUNDARY_REQUIRED")
    implementation = implementation_hashes()
    before = baseline(root)
    lines, roles = source_lines(root, spec)
    ids = [s["id"] for s in lines]
    approved = approval(root, spec, ids)
    refs = provenance_refs(spec)
    for ref in refs: reference(root, ref)
    files = {}
    slug = spec["slug"]; unit_path = f"context-units/{slug}.json"; relation_path = f"context-relations/{slug}.json"
    runtime = parse(read_bytes(root / "guidelines/runtime.json"))
    related = spec.get("relatedUnitIds", [])
    require(isinstance(related, list) and len(related) == len(set(related))
            and all(i != spec["id"] and i in {e["id"] for e in runtime["examples"]} for i in related), "RELATED_UNITS_MUST_EXIST")
    if spec["action"] == "HOLD":
        require(isinstance(spec.get("missingInformation"), list) and spec["missingInformation"]
                and all(text(s, 350) for s in spec["missingInformation"]), "HOLD_MISSING_INFORMATION_REQUIRED")
    else:
        require(all(k in spec for k in ("mechanismIds", "routingKeys", "retrievalText", "relation")), "RUNTIME_FIELDS_REQUIRED")
        exact(spec["relation"], ("situation", "target", "linkage"))
        require(all(text(spec["relation"][k], 350) for k in spec["relation"]), "RELATION_TEXT_REQUIRED")
        source_path = f"context-sources/{slug}.json"
        source = {"schemaVersion": "context-transcript-source-1", "familyId": spec["familyId"],
                  "sourceKind": spec["source"]["kind"], "sourceGroupId": spec["sourceGroupId"],
                  "origin": {k: spec["source"][k] for k in ("path", "sha256", "videoId", "url") if k in spec["source"]},
                  "audioVerified": False, "developmentReview": {"decision": spec["decision"],
                      "basis": "USER_APPROVED_DEVELOPMENT_BOUNDARY", "scope": spec["interpretation"],
                      "approvalRecord": approved}, "segments": lines}
        unit = {"schemaVersion": "context-reference-unit-2", "id": spec["id"], "familyId": spec["familyId"],
                "status": "DRAFT", "humanVerified": False, "trainingUseAuthorized": False,
                "referencePurpose": "DEVELOPMENT_BOUNDARY_NOT_PUBLIC_CONTROVERSY", "developmentDecision": spec["decision"],
                "mechanismIds": spec["mechanismIds"], "routingKeys": spec["routingKeys"],
                "source": {"kind": "STORED_TRANSCRIPT_SNAPSHOT", "path": source_path, "sha256": digest(encoded(source))},
                "utteranceIds": ids, "roles": roles, "retrievalText": spec["retrievalText"],
                "interpretation": spec["interpretation"], "boundary": spec["boundary"], "normalContrasts": spec.get("normalContrasts", [])}
        if spec["action"] == "UPDATE":
            old_raw = read_bytes(local(root, unit_path)); old = parse(old_raw)
            require(digest(old_raw) == spec.get("expectedUnitSha256"), "EXPECTED_UNIT_HASH_REQUIRED_OR_STALE")
            require(old.get("schemaVersion") == "context-reference-unit-2"
                    and old.get("source", {}).get("kind") == "STORED_TRANSCRIPT_SNAPSHOT", "DEVELOPMENT_UNIT_UPDATE_ONLY")
            old_source = reference(root, {k: old["source"][k] for k in ("path", "sha256")})
            require(all(old.get(k) == unit[k] for k in ("id", "familyId", "developmentDecision", "utteranceIds", "roles", "mechanismIds", "routingKeys"))
                    and all(old_source.get(k) == source[k] for k in ("familyId", "sourceKind", "sourceGroupId", "origin", "segments"))
                    and old_source.get("developmentReview", {}).get("approvalRecord") == approved,
                    "UPDATE_CANNOT_CHANGE_SOURCE_LABEL_OR_IDENTITY")
            unit = copy.deepcopy(old)
            for key in ("retrievalText", "interpretation", "boundary"): unit[key] = spec[key]
            if "normalContrasts" in spec: unit["normalContrasts"] = spec["normalContrasts"]
            source_path = old["source"]["path"]
        else:
            require(spec["id"] not in {e["id"] for e in runtime["examples"]}, "UNIT_ID_EXISTS")
            for name in (unit_path, relation_path, source_path): require(not local(root, name).exists(), "ADD_TARGET_EXISTS")
            files[source_path] = source
        relation = {"schemaVersion": "context-relation-profile-1", "unitId": spec["id"], "status": "DRAFT",
                    "humanVerified": False, "trainingUseAuthorized": False, "sourceSha256": unit["source"]["sha256"],
                    "utteranceIds": ids, **{k: spec["relation"][k] for k in ("situation", "target", "linkage")},
                    "evaluation": spec["interpretation"]}
        if spec["action"] == "UPDATE":
            previous_relation = parse(read_bytes(local(root, relation_path)))
            require(all(previous_relation.get(k) == relation[k] for k in
                        ("schemaVersion", "unitId", "status", "humanVerified", "trainingUseAuthorized", "sourceSha256", "utteranceIds")),
                    "UPDATE_RELATION_IDENTITY_MISMATCH")
            relation = {**previous_relation, **{k: relation[k] for k in ("situation", "target", "evaluation", "linkage")}}
        for name, value in ((unit_path, unit), (relation_path, relation)):
            previous = read_bytes(local(root, name)) if local(root, name).exists() else None
            if previous is None or parse(previous) != value: files[name] = value
    # Preview in an isolated minimal canonical snapshot, not a copy of all experiments.
    with tempfile.TemporaryDirectory(prefix="oops-casebook-preview-") as folder:
        stage = Path(folder)
        for name in set(before["inputs"]) | {r["path"] for r in refs}:
            dst = stage / name; dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(local(root, name), dst)
        for name, value in files.items(): write_private(stage / name, value)
        candidate_runtime, candidate = builder.prepare(stage)
    require(before == baseline(root) and implementation == implementation_hashes(), "DATASET_OR_TOOL_CHANGED_DURING_PREVIEW")
    # Pin non-canonical provenance too; the builder does not consume original full transcripts.
    for ref in refs: reference(root, ref)
    previous_files = {name: read_bytes(local(root, name)).decode("utf-8") if local(root, name).exists() else None for name in files}
    payload = {"toolRevision": REVISION, "datasetRoot": str(root), "spec": spec, "baseline": before,
               "implementationHashes": implementation,
               "files": files, "previousFiles": previous_files, "candidateManifest": candidate, "candidateRuntime": candidate_runtime,
               "queryRaw": lines, "queryFocusIds": roles["evaluationIds"]}
    sha = digest(json.dumps(payload, ensure_ascii=False, sort_keys=True).encode())
    receipt = f"review-intakes/{sha}.json"
    require(not local(root, receipt).exists(), "INTAKE_ALREADY_APPLIED")
    summary = {"schemaVersion": "casebook-intake-plan-1", "planSha256": sha, "action": spec["action"],
               "unitId": spec["id"], "changedFiles": sorted(files), "relatedUnitIds": related,
               "fileChanges": [{"path": name, "operation": "UPDATE" if previous_files[name] is not None else "CREATE",
                   "beforeSha256": digest(previous_files[name].encode()) if previous_files[name] is not None else None,
                   "afterSha256": digest(encoded(files[name])),
                   "changedTopLevelFields": sorted(k for k in files[name]
                       if previous_files[name] is None or parse(previous_files[name]).get(k) != files[name][k])}
                   for name in sorted(files)],
               "examplesBefore": len(runtime["examples"]), "examplesAfter": candidate["examples"],
               "candidateRuntimeSha256": candidate["runtimeSha256"], "receiptPath": receipt,
               "suggestedExistingUnits": suggestions(root, spec.get("retrievalText", spec["interpretation"]), spec["id"]),
               "suggestionBasis": "SURFACE_ONLY_NOT_SEMANTIC_OR_APPROVAL", "runtimeChanged": candidate["runtimeSha256"] != before["runtime"],
               "networkCalls": 0, "semanticSearchOrModelValidation": "NOT_RUN", "applied": False}
    return {"sha": sha, "payload": payload, "summary": summary, "receipt": receipt}


def query_document(planned):
    return {"schemaVersion": "casebook-intake-query-1", "planSha256": planned["sha"],
            "unitId": planned["summary"]["unitId"], "runtimeSha256": planned["summary"]["candidateRuntimeSha256"],
            "raw": planned["payload"]["queryRaw"], "focusIds": planned["payload"]["queryFocusIds"]}


def audit_retrieval(planned):
    """Run only the production local selector, using a pinned offline Gradle task."""
    require(planned["summary"]["action"] != "HOLD", "HOLD_HAS_NO_RETRIEVAL_TARGET")
    with tempfile.TemporaryDirectory(prefix="oops-intake-audit-") as folder:
        directory = Path(folder)
        write_private(directory / "runtime.json", planned["payload"]["candidateRuntime"])
        write_private(directory / "query.json", query_document(planned))
        repo = Path(__file__).resolve().parents[1]
        env = {k: v for k, v in os.environ.items() if k in
               {"PATH", "JAVA_HOME", "HOME", "USER", "TMPDIR", "LANG", "GRADLE_USER_HOME"}}
        try:
            process = subprocess.run(["bash", "./gradlew", "--offline", "--console=plain", "-q", "casebookIntakeAudit",
                    "-PintakeQuery=" + str(directory / "query.json"), "-PintakeRuntime=" + str(directory / "runtime.json")],
                    cwd=repo / "oops-backend", env=env, text=True, capture_output=True, timeout=60)
        except (OSError, subprocess.TimeoutExpired):
            raise PilotError("LOCAL_RETRIEVAL_AUDIT_UNAVAILABLE") from None
        rows = [s.removeprefix("CASEBOOK_INTAKE_AUDIT=") for s in process.stdout.splitlines()
                if s.startswith("CASEBOOK_INTAKE_AUDIT=")]
        require(process.returncode == 0 and len(rows) == 1, "LOCAL_RETRIEVAL_AUDIT_FAILED")
        result = parse(rows[0])
        require(result.get("planSha256") == planned["sha"]
                and result.get("runtimeSha256") == planned["summary"]["candidateRuntimeSha256"]
                and result.get("providerCalls") == 0 and result.get("retrieved") is True,
                "RETRIEVAL_REPORT_MISMATCH")
        return result


def restore_bytes(path, raw):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=".intake-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as stream: stream.write(raw)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary): os.unlink(temporary)


def apply_plan(root, spec, expected_sha, audit_runner=None):
    root = Path(root).resolve()
    lock = local(root, ".casebook-intake.lock")
    fd = os.open(lock, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    try:
        with os.fdopen(fd, "w") as owner:
            owner.write(str(os.getpid()) + "\n")
        planned = plan(root, spec)
        require(expected_sha == planned["sha"], "PLAN_HASH_MISMATCH_REVIEW_NEW_PREVIEW")
        files = planned["payload"]["files"]
        require(files or spec["action"] == "HOLD", "NO_CHANGE")
        audit = (audit_runner or audit_retrieval)(planned) if spec["action"] != "HOLD" else None
        if audit is not None:
            require(audit.get("planSha256") == planned["sha"]
                    and audit.get("runtimeSha256") == planned["summary"]["candidateRuntimeSha256"]
                    and audit.get("providerCalls") == 0 and audit.get("retrieved") is True,
                    "RETRIEVAL_REPORT_MISMATCH")
        names = list(files) + [planned["receipt"]]
        if spec["action"] != "HOLD": names += ["guidelines/runtime.json", "guidelines/build-manifest.json"]
        backups = {n: read_bytes(local(root, n)) if local(root, n).exists() else None for n in names}
        require(backups[planned["receipt"]] is None, "RECEIPT_ALREADY_EXISTS")
        require(all(backups[name] == (old.encode("utf-8") if old is not None else None)
                    for name, old in planned["payload"]["previousFiles"].items()), "TARGET_CHANGED_BEFORE_WRITE")
        receipt_value = {"schemaVersion": "casebook-intake-receipt-1", "planSha256": planned["sha"], "intake": spec,
                         "state": "HOLD_NOT_RUNTIME" if spec["action"] == "HOLD" else "DEVELOPMENT_REFERENCE_NOT_INDEPENDENT_GOLD",
                         "trainingUseAuthorized": False, "semanticValidation": "NOT_PERFORMED", "retrievalAudit": audit,
                         "previousFiles": planned["payload"]["previousFiles"],
                         "previousRuntimeSha256": planned["payload"]["baseline"]["runtime"],
                         "publishedRuntimeSha256": planned["summary"]["candidateRuntimeSha256"]}
        expected_hashes = {n: digest(encoded(v)) for n, v in files.items()}
        expected_hashes[planned["receipt"]] = digest(encoded(receipt_value))
        if spec["action"] != "HOLD":
            expected_hashes["guidelines/runtime.json"] = planned["payload"]["candidateManifest"]["runtimeSha256"]
            expected_hashes["guidelines/build-manifest.json"] = digest(encoded(planned["payload"]["candidateManifest"]))
        try:
            require(planned["payload"]["baseline"] == baseline(root), "DATASET_CHANGED_BEFORE_WRITE")
            require(planned["payload"]["implementationHashes"] == implementation_hashes(), "TOOL_CHANGED_BEFORE_WRITE")
            for ref in provenance_refs(spec): reference(root, ref)
            for name, value in files.items(): write_private(local(root, name), value)
            if spec["action"] != "HOLD":
                published = builder.publish(root)
                require(published == planned["payload"]["candidateManifest"], "PUBLISHED_PLAN_MISMATCH")
            write_private(local(root, planned["receipt"]), receipt_value)
            builder.verify(root)
        except BaseException:
            conflicts = []
            for name, raw in backups.items():
                path = local(root, name)
                current = read_bytes(path) if path.exists() else None
                if current == raw: continue
                # Never undo bytes written by a non-cooperating editor/build process.
                if current is None or digest(current) != expected_hashes[name]:
                    conflicts.append(name); continue
                if raw is None:
                    path.unlink()
                else: restore_bytes(path, raw)
            require(not conflicts, "ROLLBACK_CONFLICT_MANUAL_REVIEW_REQUIRED")
            raise
        return {**planned["summary"], "applied": True, "retrievalAudit": audit,
                "serverRestartRequired": spec["action"] != "HOLD"}
    finally:
        lock.unlink()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("intake", type=Path)
    parser.add_argument("--dataset-root", type=Path, default=builder.DEFAULT_ROOT)
    parser.add_argument("--output", type=Path, help="Optional new preview directory inside dataset root")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--check-retrieval", action="store_true", help="Offline Java selector audit; no provider calls")
    parser.add_argument("--expected-plan-sha")
    args = parser.parse_args()
    if args.apply != bool(args.expected_plan_sha) or args.apply and args.output:
        parser.error("APPLY_REQUIRES_PLAN_SHA_AND_NO_OUTPUT")
    try:
        spec = parse(read_bytes(args.intake))
        if args.apply: result = apply_plan(args.dataset_root, spec, args.expected_plan_sha)
        else:
            preview = plan(args.dataset_root, spec); result = preview["summary"]
            if args.check_retrieval:
                result = {**result, "retrievalAudit": audit_retrieval(preview)}
            if args.output:
                root = args.dataset_root.resolve(); output = args.output.absolute()
                relative = output.relative_to(root).as_posix()
                require(relative.startswith("experiments/"), "PREVIEW_OUTPUT_MUST_BE_PRIVATE_EXPERIMENT")
                output = local(root, relative); require(not output.exists(), "PREVIEW_OUTPUT_EXISTS")
                output.mkdir(parents=True, mode=0o700)
                write_private(output / "plan.json", preview["payload"])
                write_private(output / "summary.json", result)
                write_private(output / "runtime.json", preview["payload"]["candidateRuntime"])
                write_private(output / "query.json", query_document(preview))
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except PilotError as error:
        parser.exit(2, "INTAKE_REJECTED: " + str(error) + "\n")
    except (ValueError, KeyError, TypeError, AttributeError, OSError):
        parser.exit(2, "INTAKE_REJECTED: invalid/stale input, approval, paths or build; no successful apply.\n")


if __name__ == "__main__":
    main()
