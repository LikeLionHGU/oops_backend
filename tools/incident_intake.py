#!/usr/bin/env python3
"""Hash-bound, reviewed intake for incident-2 records.

Preview is read-only. Applying requires the exact preview hash and a review
record bound to the candidate incident and every cited source snapshot. The
workflow only admits reviewed records to the local runtime-reference build; it
does not approve training or claim independent truth.
"""
import argparse
import copy
import hashlib
import json
import os
import re
import tempfile
import subprocess
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
import sys
sys.path.insert(0, str(ROOT))
from review_context_pilot import parse, read_bytes, require, PilotError
from build_review_guidelines import write_private
import build_incident_dataset as builder
from source_use_policy import incident_lock_path
from incident_review_policy import validate_review_record


REVISION = "incident-intake-tool-2"
SHA256 = re.compile(r"[0-9a-f]{64}\Z")


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def local(root, relative):
    require(isinstance(relative, str) and relative and "\\" not in relative
            and "\0" not in relative, "INCIDENT_INTAKE_LOCAL_PATH_REQUIRED")
    path = PurePosixPath(relative)
    require(not path.is_absolute() and ".." not in path.parts and path.as_posix() == relative,
            "INCIDENT_INTAKE_UNSAFE_PATH")
    root = Path(root).resolve()
    current = root
    for part in path.parts:
        current = current / part
        require(not current.is_symlink(), "INCIDENT_INTAKE_SYMLINK_REFUSED")
    resolved = current.resolve()
    require(resolved != root and resolved.is_relative_to(root), "INCIDENT_INTAKE_PATH_OUTSIDE_ROOT")
    return resolved


def implementation_hashes():
    names = (
        "tools/incident_intake.py", "tools/incident_review_policy.py", "tools/source_use_policy.py",
        "tools/build_incident_dataset.py", "tools/incidents_migration.py",
        "tools/incident_build_definition.py", "tools/build_context_dictionary.py",
        "tools/build_review_guidelines.py", "tools/review_context_pilot.py",
        "tools/audit_evaluation_suites.py", "tools/evaluation-suites.json",
        "oops-backend/src/test/resources/evals/context-content-contrasts-v1.json",
        "tools/schemas/incident-2.schema.json", "tools/schemas/incident-review-approval-1.schema.json",
        "oops-backend/src/main/java/com/example/oops/analyzer/ReviewGuidelineLibrary.java",
        "oops-backend/src/main/java/com/example/oops/analyzer/ContextExampleSelector.java",
        "oops-backend/src/main/java/com/example/oops/analyzer/ContextRelationRetrieval.java",
        "oops-backend/src/main/java/com/example/oops/analyzer/ContextBoundarySupplement.java",
        "oops-backend/src/main/java/com/example/oops/analyzer/ContextReferenceBalance.java",
        "oops-backend/src/main/java/com/example/oops/analyzer/StructuredContextSearch.java",
        "oops-backend/src/main/java/com/example/oops/analyzer/CandidateReviewEngine.java",
        "oops-backend/src/test/java/com/example/oops/analyzer/IncidentSelectorImpactAudit.java",
        "oops-backend/src/test/java/com/example/oops/analyzer/CasebookImpactAudit.java",
        "oops-backend/src/main/resources/application.yml",
        "oops-backend/build.gradle",
    )
    return {name: digest(read_bytes(ROOT / name)) for name in names}


def audit_selector_impact(root, baseline_runtime_raw, candidate_runtime, binding_sha,
                          before_source_ids, after_source_ids):
    """Replay the fixed suite through the production selector; no model or network calls."""
    import audit_evaluation_suites as evaluation
    root = Path(root).resolve()
    registry = parse(read_bytes(ROOT / "tools/evaluation-suites.json"))
    bundle = evaluation.prepare(ROOT, registry)
    label_by_id = {row["caseId"]: row for row in bundle["labels.json"]}
    cases = []
    for row in bundle["inputs.json"]:
        label = label_by_id[row["caseId"]]
        cases.append({"caseId": row["caseId"], "sourceGroupId": label.get("sourceGroupId"),
                      "evaluationGroupId": label.get("evaluationGroupId"),
                      "anchorId": row["anchorId"], "raw": row["raw"]})
    evaluation_manifest = bundle["manifest.json"]
    evaluation_fingerprint = digest(encoded({key: evaluation_manifest[key] for key in
        ("suiteId", "registryCanonicalSha256", "inputsCanonicalSha256", "labelsCanonicalSha256", "activeCases")}))
    candidate_raw = encoded(candidate_runtime)
    request = {"schemaVersion": "incident-selector-impact-request-1", "auditBindingSha256": binding_sha,
               "evaluationFingerprint": evaluation_fingerprint,
               "baselineRuntimeSha256": digest(baseline_runtime_raw),
               "candidateRuntimeSha256": digest(candidate_raw),
               "sourceCaseIdsAdded": [row for row in after_source_ids if row not in set(before_source_ids)],
               "sourceCaseIdsRemoved": [row for row in before_source_ids if row not in set(after_source_ids)],
               "evaluation": cases}
    with tempfile.TemporaryDirectory(prefix="oops-incident-selector-audit-") as folder:
        directory = Path(folder)
        request_path, baseline_path, candidate_path = (directory / name for name in
            ("request.json", "baseline-runtime.json", "candidate-runtime.json"))
        _write_bytes(request_path, encoded(request))
        _write_bytes(baseline_path, baseline_runtime_raw)
        _write_bytes(candidate_path, candidate_raw)
        env = {k: v for k, v in os.environ.items() if k in
               {"PATH", "JAVA_HOME", "HOME", "USER", "TMPDIR", "LANG", "GRADLE_USER_HOME"}}
        try:
            process = subprocess.run(["bash", "./gradlew", "--offline", "--console=plain", "-q",
                    "incidentSelectorImpactAudit", "-PincidentImpactRequest=" + str(request_path),
                    "-PincidentImpactBaseline=" + str(baseline_path),
                    "-PincidentImpactCandidate=" + str(candidate_path)],
                    cwd=ROOT / "oops-backend", env=env, text=True, capture_output=True, timeout=120)
        except (OSError, subprocess.TimeoutExpired):
            raise PilotError("INCIDENT_SELECTOR_AUDIT_UNAVAILABLE") from None
        rows = [line.removeprefix("INCIDENT_SELECTOR_IMPACT=") for line in process.stdout.splitlines()
                if line.startswith("INCIDENT_SELECTOR_IMPACT=")]
        require(process.returncode == 0 and len(rows) == 1, "INCIDENT_SELECTOR_AUDIT_FAILED")
        report = parse(rows[0])
    require(report.get("schemaVersion") == "incident-selector-impact-report-1"
            and report.get("auditBindingSha256") == binding_sha
            and report.get("evaluationFingerprint") == evaluation_fingerprint
            and report.get("baselineRuntimeSha256") == request["baselineRuntimeSha256"]
            and report.get("candidateRuntimeSha256") == request["candidateRuntimeSha256"]
            and report.get("sourceCaseIdsAdded") == request["sourceCaseIdsAdded"]
            and report.get("sourceCaseIdsRemoved") == request["sourceCaseIdsRemoved"]
            and report.get("providerCalls") == 0 and report.get("modelJudgmentExecuted") is False
            and report.get("accuracyMeasured") is False and report.get("retrievalImpactOnly") is True
            and report.get("dynamicRouterHintsApplied") is False
            and report.get("relationHintsApplied") is False
            and isinstance(report.get("sharedPatternUnchanged"), bool)
            and isinstance(report.get("changedQueryCount"), int)
            and isinstance(report.get("casesWithoutSourceGroup"), int)
            and isinstance(report.get("casesWithoutEvaluationGroup"), int)
            and isinstance(report.get("casesWithoutAnyHoldoutGroup"), int)
            and isinstance(report.get("cases"), list) and len(report["cases"]) == len(cases),
            "INCIDENT_SELECTOR_REPORT_MISMATCH")
    refreshed_registry = parse(read_bytes(ROOT / "tools/evaluation-suites.json"))
    refreshed_bundle = evaluation.prepare(ROOT, refreshed_registry)
    refreshed_manifest = refreshed_bundle["manifest.json"]
    refreshed_fingerprint = digest(encoded({key: refreshed_manifest[key] for key in
        ("suiteId", "registryCanonicalSha256", "inputsCanonicalSha256", "labelsCanonicalSha256", "activeCases")}))
    require(refreshed_fingerprint == evaluation_fingerprint, "EVALUATION_CHANGED_DURING_SELECTOR_AUDIT")
    return report


def _lock_v2(root):
    lock_path = incident_lock_path(root)
    require(lock_path.is_file() and not lock_path.is_symlink(), "INCIDENT_SOURCE_LOCK_REQUIRED")
    current = parse(read_bytes(lock_path))
    if current.get("schemaVersion") == "incident-source-lock-1":
        require(set(current) == {"schemaVersion", "entries"} and isinstance(current["entries"], dict),
                "INCIDENT_SOURCE_LOCK_INVALID")
        require(all(isinstance(key, str) and SHA256.fullmatch(str(value))
                    for key, value in current["entries"].items()), "INCIDENT_SOURCE_LOCK_INVALID")
        return {"schemaVersion": "incident-source-lock-2",
                "frozenLegacyEntries": copy.deepcopy(current["entries"]), "reviewedEntries": {}}
    require(current.get("schemaVersion") == "incident-source-lock-2"
            and set(current) == {"schemaVersion", "frozenLegacyEntries", "reviewedEntries"}
            and isinstance(current["frozenLegacyEntries"], dict)
            and isinstance(current["reviewedEntries"], dict), "INCIDENT_SOURCE_LOCK_INVALID")
    return copy.deepcopy(current)


def plan(root, candidate_path, approval_path, audit_runner=None):
    root = Path(root).resolve()
    candidate_rel, approval_rel = str(candidate_path), str(approval_path)
    require(candidate_rel.startswith("research/pending-incidents/"),
            "INCIDENT_CANDIDATE_MUST_BE_QUARANTINED")
    require(approval_rel.startswith("research/incident-approvals/"),
            "INCIDENT_APPROVAL_PATH_REQUIRED")
    before = builder.verify(root)
    baseline_hashes = builder.input_hashes(root)
    implementations = implementation_hashes()
    candidate_raw = read_bytes(local(root, candidate_rel))
    incident = parse(candidate_raw)
    require(incident.get("schemaVersion") == "incident-2"
            and incident.get("status") == "UNREVIEWED_DRAFT"
            and incident.get("humanApproved") is False
            and incident.get("runtimeEligible") is False
            and incident.get("trainingUseAuthorized") is False,
            "INCIDENT_DRAFT_CONTRACT_REQUIRED")
    incident_id = incident.get("incidentId")
    require(isinstance(incident_id, str) and re.fullmatch(r"[A-Za-z0-9_-]{1,64}", incident_id)
            and candidate_rel.endswith(".json"), "INCIDENT_ID_REQUIRED")
    require(candidate_rel == f"research/pending-incidents/{incident_id}.json"
            and approval_rel == f"research/incident-approvals/{incident_id}.json",
            "INCIDENT_INTAKE_ID_PATH_MISMATCH")
    target_rel = f"incidents/{incident_id}.json"
    lock = _lock_v2(root)
    if incident_id in lock["frozenLegacyEntries"]:
        require(False, "FROZEN_LEGACY_INCIDENT_UPDATE_REQUIRES_SEPARATE_REVIEW")
    target = local(root, target_rel)
    if target.exists():
        require(incident_id in lock["reviewedEntries"], "UNREVIEWED_EXISTING_INCIDENT")
        action = "UPDATE_REVIEWED"
    else:
        require(incident_id not in lock["reviewedEntries"], "REVIEWED_INCIDENT_FILE_MISSING")
        action = "ADD"

    approval_raw = read_bytes(local(root, approval_rel))
    approval = parse(approval_raw)
    validate_review_record(root, candidate_raw, approval)
    review_rel = f"research/incident-reviews/{incident_id}.json"
    review_raw = approval_raw
    review_entry = {"incidentSha256": digest(candidate_raw), "reviewPath": review_rel,
                    "reviewSha256": digest(review_raw)}
    lock["reviewedEntries"][incident_id] = review_entry
    lock_raw = encoded(lock)

    # Stage only canonical builder inputs plus the candidate, reviewed source
    # snapshots, and the two new provenance files; experiments stay out of stage.
    from incident_review_policy import source_artifacts
    artifacts, _ = source_artifacts(root, incident)
    with tempfile.TemporaryDirectory(prefix="oops-incident-intake-") as folder:
        stage = Path(folder)
        for name in baseline_hashes:
            src = local(root, name)
            dst = stage / name
            dst.parent.mkdir(parents=True, exist_ok=True)
            dst.write_bytes(read_bytes(src))
        for artifact in artifacts:
            src = local(root, artifact["path"])
            dst = stage / artifact["path"]
            dst.parent.mkdir(parents=True, exist_ok=True)
            if not dst.exists():
                dst.write_bytes(read_bytes(src))
        staged_lock_path = stage / "definitions/incident-source-lock.json"
        staged_lock_path.parent.mkdir(parents=True, exist_ok=True)
        staged_lock_path.write_bytes(lock_raw)
        staged_target = stage / target_rel
        staged_target.parent.mkdir(parents=True, exist_ok=True)
        staged_target.write_bytes(candidate_raw)
        staged_review = stage / review_rel
        staged_review.parent.mkdir(parents=True, exist_ok=True)
        staged_review.write_bytes(review_raw)
        candidate_runtime, candidate_manifest = builder.prepare(stage)

    require(before == builder.verify(root) and baseline_hashes == builder.input_hashes(root)
            and implementations == implementation_hashes(), "DATASET_OR_TOOL_CHANGED_DURING_PREVIEW")
    previous_runtime_raw = read_bytes(local(root, "guidelines/runtime.json"))
    previous_runtime = parse(previous_runtime_raw)
    before_source_ids = {row["caseId"] for row in previous_runtime.get("sourceCases", [])}
    after_source_ids = {row["caseId"] for row in candidate_runtime.get("sourceCases", [])}
    before_example_ids = {row["id"] for row in previous_runtime.get("examples", [])}
    after_example_ids = {row["id"] for row in candidate_runtime.get("examples", [])}
    def case_mechanisms(runtime):
        return {(case_id, guideline["id"])
                for guideline in runtime.get("guidelines", [])
                for case_id in guideline.get("sourceCaseIds", [])}
    before_mappings = case_mechanisms(previous_runtime)
    after_mappings = case_mechanisms(candidate_runtime)
    policy_inputs = [name for name in baseline_hashes
        if name.startswith("definitions/") or name.startswith("context-units/")
        or name.startswith("context-relations/")]
    unchanged_policy_inputs = all(baseline_hashes[name] == candidate_manifest["inputHashes"].get(name)
                                 for name in policy_inputs)
    require(digest(encoded(candidate_runtime)) == candidate_manifest["runtimeSha256"],
            "CANDIDATE_RUNTIME_CANONICAL_HASH_MISMATCH")
    audit_binding = {"incidentId": incident_id, "candidateSha256": digest(candidate_raw),
                     "approvalSha256": digest(approval_raw), "baselineRuntimeSha256": before["runtimeSha256"],
                     "candidateRuntimeSha256": candidate_manifest["runtimeSha256"],
                     "implementationHashes": implementations}
    audit_binding_sha = digest(json.dumps(audit_binding, ensure_ascii=False, sort_keys=True,
                                          separators=(",", ":")).encode("utf-8"))
    impact_audit = (audit_runner or audit_selector_impact)(root, previous_runtime_raw, candidate_runtime,
                    audit_binding_sha, [row["caseId"] for row in previous_runtime.get("sourceCases", [])],
                    [row["caseId"] for row in candidate_runtime.get("sourceCases", [])])
    require(before == builder.verify(root) and baseline_hashes == builder.input_hashes(root)
            and implementations == implementation_hashes()
            and previous_runtime_raw == read_bytes(local(root, "guidelines/runtime.json")),
            "DATASET_OR_TOOL_CHANGED_DURING_IMPACT_AUDIT")
    impact_sha = digest(json.dumps(impact_audit, ensure_ascii=False, sort_keys=True,
                                  separators=(",", ":")).encode("utf-8"))
    payload = {"toolRevision": REVISION, "datasetRoot": str(root), "action": action,
               "candidatePath": candidate_rel, "candidateSha256": digest(candidate_raw),
               "approvalPath": approval_rel, "approvalSha256": digest(approval_raw),
               "targetPath": target_rel, "reviewPath": review_rel, "reviewSha256": digest(review_raw),
               "lockPath": str(incident_lock_path(root)), "lockSha256": digest(lock_raw),
               "baselineManifest": before, "baselineInputHashes": baseline_hashes,
               "implementationHashes": implementations, "candidateManifest": candidate_manifest,
               "candidateRuntime": candidate_runtime, "selectorImpactAudit": impact_audit,
               "selectorImpactAuditSha256": impact_sha}
    plan_hash = digest(json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode())
    receipt_rel = f"review-intakes/{plan_hash}.json"
    require(not local(root, receipt_rel).exists(), "INCIDENT_INTAKE_ALREADY_APPLIED")
    summary = {"schemaVersion": "incident-intake-plan-1", "planSha256": plan_hash,
               "action": action, "incidentId": incident_id,
               "candidateSha256": digest(candidate_raw), "approvalSha256": digest(approval_raw),
               "changedFiles": [target_rel, review_rel, str(incident_lock_path(root).resolve())],
               "sourceArtifacts": artifacts,
               "runtimeSha256": candidate_manifest["runtimeSha256"],
               "previousRuntimeSha256": before["runtimeSha256"],
               "incidentsBefore": before["incidents"], "incidentsAfter": candidate_manifest["incidents"],
               "examplesBefore": before["examples"], "examplesAfter": candidate_manifest["examples"],
               "sourceCaseIdsAdded": sorted(after_source_ids - before_source_ids),
               "sourceCaseIdsRemoved": sorted(before_source_ids - after_source_ids),
               "exampleIdsAdded": sorted(after_example_ids - before_example_ids),
               "exampleIdsRemoved": sorted(before_example_ids - after_example_ids),
               "caseMechanismMappingsAdded": [list(pair) for pair in sorted(after_mappings - before_mappings)],
               "caseMechanismMappingsRemoved": [list(pair) for pair in sorted(before_mappings - after_mappings)],
               "sharedPolicyInputsUnchanged": unchanged_policy_inputs,
               "sharedPolicyInputPaths": policy_inputs,
               "selectorImpactAudit": "COMPLETE_OFFLINE_PRODUCTION_SELECTOR",
               "selectorImpactAuditSha256": impact_sha,
               "selectorAffectedEvaluationCases": impact_audit["changedQueryCount"],
               "evaluationQueriesSelectingNewIncidentReference": impact_audit["queriesSelectingNewIncidentReference"],
               "sharedPatternUnchangedInSelectorPrompt": impact_audit["sharedPatternUnchanged"],
               "selectorImpactAuditReport": impact_audit,
               "trainingUseAuthorized": False, "independentTruthClaim": False,
               "receiptPath": receipt_rel, "applied": False}
    return {"sha": plan_hash, "payload": payload, "summary": summary, "receipt": receipt_rel,
            "candidateRaw": candidate_raw, "reviewRaw": review_raw, "lockRaw": lock_raw}


def _write_bytes(path, raw):
    path = Path(path)
    require(not path.is_symlink(), "INCIDENT_INTAKE_OUTPUT_SYMLINK_REFUSED")
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=".incident-intake-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def apply(root, candidate_path, approval_path, expected_plan_sha, audit_runner=None):
    root = Path(root).resolve()
    guard_path = local(root, ".incident-intake.lock")
    descriptor = os.open(guard_path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    try:
        with os.fdopen(descriptor, "w") as stream:
            stream.write(str(os.getpid()) + "\n")
        planned = plan(root, candidate_path, approval_path, audit_runner=audit_runner)
        require(expected_plan_sha == planned["sha"], "PLAN_HASH_MISMATCH_REVIEW_NEW_PREVIEW")
        payload = planned["payload"]
        targets = {
            payload["targetPath"]: (root / payload["targetPath"], planned["candidateRaw"]),
            payload["reviewPath"]: (root / payload["reviewPath"], planned["reviewRaw"]),
            "incident-source-lock": (Path(payload["lockPath"]), planned["lockRaw"]),
            "guidelines/runtime.json": (root / "guidelines/runtime.json", encoded(payload["candidateRuntime"])),
            "guidelines/build-manifest.json": (root / "guidelines/build-manifest.json", encoded(payload["candidateManifest"])),
            planned["receipt"]: (root / planned["receipt"], b""),
        }
        backups = {}
        for key, (path, _) in targets.items():
            require(not path.is_symlink(), "INCIDENT_INTAKE_OUTPUT_SYMLINK_REFUSED")
            backups[key] = path.read_bytes() if path.exists() else None
        require(backups[planned["receipt"]] is None, "INCIDENT_INTAKE_RECEIPT_EXISTS")

        receipt = {"schemaVersion": "incident-intake-receipt-1", "planSha256": planned["sha"],
                   "incidentId": planned["summary"]["incidentId"], "action": planned["summary"]["action"],
                   "candidateSha256": digest(planned["candidateRaw"]),
                   "approvalSha256": digest(planned["reviewRaw"]),
                   "previousRuntimeSha256": payload["baselineManifest"]["runtimeSha256"],
                   "publishedRuntimeSha256": payload["candidateManifest"]["runtimeSha256"],
                   "previousFiles": {key: backups[key].decode("utf-8") if backups[key] is not None else None
                                     for key in (payload["targetPath"], payload["reviewPath"], "incident-source-lock")},
                   "previousFileSha256": {key: digest(backups[key]) if backups[key] is not None else None
                                           for key in (payload["targetPath"], payload["reviewPath"], "incident-source-lock")},
                   "selectorImpactAudit": payload["selectorImpactAudit"],
                   "selectorImpactAuditSha256": payload["selectorImpactAuditSha256"],
                   "trainingUseAuthorized": False, "independentTruthClaim": False}
        targets[planned["receipt"]] = (targets[planned["receipt"]][0], encoded(receipt))
        expected_current = {key: raw for key, (_, raw) in targets.items()}

        try:
            require(payload["baselineManifest"] == builder.verify(root)
                    and payload["baselineInputHashes"] == builder.input_hashes(root)
                    and payload["implementationHashes"] == implementation_hashes(),
                    "DATASET_OR_TOOL_CHANGED_BEFORE_APPLY")
            for key in (payload["targetPath"], payload["reviewPath"], "incident-source-lock"):
                _write_bytes(targets[key][0], targets[key][1])
            published = builder.publish(root)
            require(published == payload["candidateManifest"], "PUBLISHED_INCIDENT_PLAN_MISMATCH")
            _write_bytes(targets[planned["receipt"]][0], targets[planned["receipt"]][1])
            builder.verify(root)
        except BaseException:
            conflicts = []
            for key, (path, _) in targets.items():
                previous = backups[key]
                current = path.read_bytes() if path.exists() else None
                if current == previous:
                    continue
                if current != expected_current[key]:
                    conflicts.append(key)
                    continue
                if previous is None:
                    path.unlink()
                else:
                    _write_bytes(path, previous)
            require(not conflicts, "ROLLBACK_CONFLICT_MANUAL_REVIEW_REQUIRED")
            raise
        return {**planned["summary"], "applied": True}
    finally:
        guard_path.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-root", type=Path, default=builder.DEFAULT_ROOT)
    parser.add_argument("--candidate", required=True, help="research/pending-incidents/{id}.json")
    parser.add_argument("--approval", required=True, help="research/incident-approvals/{id}.json")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--expected-plan-sha")
    args = parser.parse_args()
    if args.apply != bool(args.expected_plan_sha):
        parser.error("APPLY_REQUIRES_EXPECTED_PLAN_SHA")
    try:
        result = apply(args.dataset_root, args.candidate, args.approval, args.expected_plan_sha) if args.apply \
            else plan(args.dataset_root, args.candidate, args.approval)["summary"]
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except Exception as error:  # noqa: BLE001 — CLI boundary
        parser.exit(2, f"{type(error).__name__}: {error}\n")


if __name__ == "__main__":
    main()
