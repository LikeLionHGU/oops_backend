"""Fail-closed offline evaluation and rollback rehearsal for analyzer releases.

This tool never calls a model, edits the runtime dictionary, deploys, or changes
the supplied bundle directories. A passing comparison is only eligible for a
separate human approval; it is not an automatic release command.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from decimal import Decimal, InvalidOperation
from datetime import datetime
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import tempfile


DECISIONS = {"PASS", "REVIEW_REQUIRED", "UNCERTAIN", "NOT_EVALUATED"}
SEARCH_VARIANTS = {"LEXICAL", "STRUCTURED", "RELATION", "SEMANTIC", "COMBINED"}
QUALITY_METRICS = (
    "missedReviews", "falseWarnings", "uncertain", "notEvaluated",
    "contractFailures", "evidenceErrors", "unreviewedEvidence",
    "duplicateFindings", "missedRelevantReferences", "irrelevantReferences", "displacedRelevantReferences",
    "budgetViolations",
)
THRESHOLD_METRICS = QUALITY_METRICS + ("maxCostUsd",)


def raw_sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def canonical_sha(value) -> str:
    raw = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(raw).hexdigest()


def require(condition: bool, code: str):
    if not condition:
        raise ValueError(code)


def money(value) -> Decimal:
    require(isinstance(value, (str, int, float)) and not isinstance(value, bool), "COST_INVALID")
    try:
        result = Decimal(str(value))
    except InvalidOperation as exc:
        raise ValueError("COST_INVALID") from exc
    require(result.is_finite() and result >= 0, "COST_INVALID")
    return result


def timestamp(value, code):
    require(isinstance(value, str), code)
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ValueError(code) from exc
    require(parsed.tzinfo is not None, code)
    return parsed


def validate_suite(suite: dict, criteria: dict) -> dict:
    require(suite.get("schemaVersion") == "independent-video-suite-1", "SUITE_SCHEMA_INVALID")
    require(suite.get("status") == "FROZEN_FOR_EVALUATION", "SUITE_NOT_FROZEN")
    require(suite.get("independentGold") is True, "SUITE_NOT_INDEPENDENT_GOLD")
    suite_frozen_at = timestamp(suite.get("frozenAt"), "SUITE_FREEZE_TIME_INVALID")
    protocol = suite.get("reviewProtocol", {})
    require(protocol.get("status") == "USER_APPROVED_INDEPENDENT_HOLDOUT"
            and protocol.get("preparedWithoutModelOutputs") is True
            and protocol.get("reviewedAfterCandidateRun") is False
            and protocol.get("trainingUseAuthorized") is False, "SUITE_REVIEW_PROTOCOL_INVALID")
    cases = suite.get("cases")
    require(isinstance(cases, list) and cases, "SUITE_CASES_REQUIRED")
    require(criteria.get("schemaVersion") == "release-criteria-1", "CRITERIA_SCHEMA_INVALID")
    require(criteria.get("status") == "LOCKED_BEFORE_RUN", "CRITERIA_NOT_PRELOCKED")
    criteria_locked_at = timestamp(criteria.get("lockedAt"), "CRITERIA_LOCK_TIME_INVALID")
    require(criteria_locked_at >= suite_frozen_at, "CRITERIA_LOCK_PRECEDES_SUITE_FREEZE")
    require(criteria.get("suiteId") == suite.get("suiteId"), "CRITERIA_SUITE_MISMATCH")
    require(isinstance(criteria.get("minimumIndependentSourceGroups"), int)
            and not isinstance(criteria.get("minimumIndependentSourceGroups"), bool)
            and criteria["minimumIndependentSourceGroups"] > 0, "SOURCE_GROUP_THRESHOLD_REQUIRED")
    require(isinstance(criteria.get("minimumCasesPerCategory"), int)
            and not isinstance(criteria.get("minimumCasesPerCategory"), bool)
            and criteria["minimumCasesPerCategory"] > 0, "CATEGORY_COVERAGE_THRESHOLD_REQUIRED")
    require(isinstance(criteria.get("requireNoQualityRegression"), bool), "REGRESSION_POLICY_REQUIRED")
    require(isinstance(criteria.get("requireImprovement"), bool), "IMPROVEMENT_POLICY_REQUIRED")
    require("maximumCostRatio" in criteria and money(criteria["maximumCostRatio"]) > 0,
            "COST_RATIO_THRESHOLD_REQUIRED")
    thresholds = criteria.get("overallThresholds")
    require(isinstance(thresholds, dict) and set(thresholds) == set(THRESHOLD_METRICS),
            "OVERALL_THRESHOLDS_INCOMPLETE")
    categories = criteria.get("categoryThresholds")
    require(isinstance(categories, dict) and categories, "CATEGORY_THRESHOLDS_REQUIRED")
    require(all(isinstance(v, dict) and set(v) == set(THRESHOLD_METRICS) for v in categories.values()),
            "CATEGORY_THRESHOLDS_INCOMPLETE")

    ids, source_groups, category_counts = set(), set(), Counter()
    development_groups = set(suite.get("developmentSourceGroups", []))
    for case in cases:
        require(isinstance(case, dict) and isinstance(case.get("caseId"), str) and case["caseId"], "CASE_INVALID")
        require(case["caseId"] not in ids, "DUPLICATE_CASE_ID")
        ids.add(case["caseId"])
        require(case.get("labelStatus") == "USER_APPROVED" and case.get("expectedDecision") in {"PASS", "REVIEW_REQUIRED"},
                "CASE_LABEL_NOT_APPROVED")
        group = case.get("sourceGroupId")
        require(case.get("sourceGroupVerified") is True and isinstance(group, str) and group
                and group not in development_groups, "CASE_SOURCE_NOT_INDEPENDENT")
        require(is_sha(case.get("sourceSha256")) and is_sha(case.get("inputSha256")), "CASE_HASH_INVALID")
        categories_for_case = case.get("categoryIds")
        require(isinstance(categories_for_case, list) and categories_for_case
                and len(categories_for_case) == len(set(categories_for_case)), "CASE_CATEGORIES_INVALID")
        require(all(category in categories for category in categories_for_case), "CATEGORY_THRESHOLD_MISSING")
        require(case.get("referenceReviewStatus") == "USER_APPROVED"
                and isinstance(case.get("expectedRelevantReferenceIds"), list)
                and all(isinstance(x, str) and x for x in case["expectedRelevantReferenceIds"])
                and len(case["expectedRelevantReferenceIds"]) == len(set(case["expectedRelevantReferenceIds"])),
                "REFERENCE_RELEVANCE_NOT_REVIEWED")
        source_groups.add(group)
        for category in categories_for_case:
            category_counts[category] += 1
    require(len(source_groups) >= criteria["minimumIndependentSourceGroups"], "INDEPENDENT_SOURCE_COVERAGE_LOW")
    require(set(category_counts) == set(categories), "UNUSED_CATEGORY_THRESHOLD")
    require(all(category_counts[name] >= criteria["minimumCasesPerCategory"] for name in categories),
            "CATEGORY_CASE_COVERAGE_LOW")
    return {"caseIds": ids, "sourceGroups": source_groups, "categoryCounts": category_counts,
            "suiteFrozenAt": suite_frozen_at, "criteriaLockedAt": criteria_locked_at}


def is_sha(value) -> bool:
    return isinstance(value, str) and len(value) == 64 and all(c in "0123456789abcdef" for c in value)


def validate_run(run: dict, suite: dict, suite_sha: str, criteria_sha: str, expected_ids: set[str]) -> dict:
    require(run.get("schemaVersion") == "release-evaluation-run-1", "RUN_SCHEMA_INVALID")
    require(run.get("suiteId") == suite.get("suiteId") and run.get("suiteSha256") == suite_sha,
            "RUN_SUITE_MISMATCH")
    require(run.get("criteriaSha256") == criteria_sha, "RUN_CRITERIA_MISMATCH")
    require(is_sha(run.get("artifactBundleSha256")), "RUN_ARTIFACT_BUNDLE_HASH_INVALID")
    artifacts = run.get("artifactManifest")
    require(isinstance(artifacts, list) and artifacts, "RUN_ARTIFACT_MANIFEST_REQUIRED")
    artifact_paths = set()
    for artifact in artifacts:
        require(isinstance(artifact, dict) and isinstance(artifact.get("path"), str), "RUN_ARTIFACT_ENTRY_INVALID")
        path = str(_safe_relative(artifact["path"]))
        require(path not in artifact_paths and is_sha(artifact.get("sha256")), "RUN_ARTIFACT_ENTRY_INVALID")
        artifact_paths.add(path)
    normalized_artifacts = sorted(({"path": str(_safe_relative(row["path"])), "sha256": row["sha256"]}
                                   for row in artifacts), key=lambda row: row["path"])
    require(canonical_sha(normalized_artifacts) == run["artifactBundleSha256"], "RUN_ARTIFACT_BUNDLE_HASH_MISMATCH")
    fingerprints = run.get("fingerprints")
    require(isinstance(fingerprints, dict) and set(fingerprints) ==
            {"code", "model", "prompt", "policy", "runtime", "settings", "retrieval"}
            and all(is_sha(v) for v in fingerprints.values()), "RUN_FINGERPRINTS_INVALID")
    rows = run.get("cases")
    require(isinstance(rows, list), "RUN_CASES_REQUIRED")
    by_id = {}
    for row in rows:
        require(isinstance(row, dict) and row.get("caseId") in expected_ids, "RUN_CASE_UNKNOWN")
        require(row["caseId"] not in by_id, "RUN_CASE_DUPLICATE")
        require("expectedDecision" not in row and "reviewerNotes" not in row, "RUN_LABEL_LEAKAGE")
        require(row.get("decision") in DECISIONS, "RUN_DECISION_INVALID")
        require(row.get("status") in {"COMPLETED", "NOT_EVALUATED", "CONTRACT_FAILURE"}, "RUN_STATUS_INVALID")
        require(row.get("evidenceStatus") in {"REVIEWED_VALID", "REVIEWED_INVALID", "NOT_APPLICABLE", "NOT_REVIEWED"},
                "EVIDENCE_STATUS_INVALID")
        refs = row.get("selectedReferenceIds")
        require(isinstance(refs, list) and len(refs) == len(set(refs)) and all(isinstance(x, str) for x in refs),
                "SELECTED_REFERENCES_INVALID")
        require(isinstance(row.get("duplicateFindings"), int) and not isinstance(row["duplicateFindings"], bool)
                and row["duplicateFindings"] >= 0, "DUPLICATE_COUNT_INVALID")
        require(isinstance(row.get("payloadCodePoints"), int) and row["payloadCodePoints"] >= 0
                and isinstance(row.get("payloadBudgetCodePoints"), int) and row["payloadBudgetCodePoints"] > 0,
                "PAYLOAD_SIZE_INVALID")
        row_cost = money(row.get("costUsd"))
        require(row.get("costBasis") in {"PROVIDER_REPORTED", "PREDECLARED_ESTIMATE"}, "COST_BASIS_INVALID")
        by_id[row["caseId"]] = {**row, "_cost": row_cost}
    require(set(by_id) == expected_ids, "RUN_CASE_COVERAGE_MISMATCH")
    timestamp(run.get("startedAt"), "RUN_TIME_INVALID")
    return by_id


def _metric_template():
    return {key: 0 for key in QUALITY_METRICS} | {"totalCostUsd": Decimal(0), "cases": 0}


def _accumulate(metrics: dict, case: dict, observed: dict):
    metrics["cases"] += 1
    expected, actual = case["expectedDecision"], observed["decision"]
    if expected == "REVIEW_REQUIRED" and actual != "REVIEW_REQUIRED":
        metrics["missedReviews"] += 1
    if expected == "PASS" and actual == "REVIEW_REQUIRED":
        metrics["falseWarnings"] += 1
    if actual == "UNCERTAIN": metrics["uncertain"] += 1
    if observed["status"] == "NOT_EVALUATED" or actual == "NOT_EVALUATED": metrics["notEvaluated"] += 1
    if observed["status"] == "CONTRACT_FAILURE": metrics["contractFailures"] += 1
    if observed["evidenceStatus"] == "REVIEWED_INVALID": metrics["evidenceErrors"] += 1
    if observed["evidenceStatus"] == "NOT_REVIEWED": metrics["unreviewedEvidence"] += 1
    metrics["duplicateFindings"] += observed["duplicateFindings"]
    relevant = set(case["expectedRelevantReferenceIds"])
    selected = set(observed["selectedReferenceIds"])
    metrics["irrelevantReferences"] += len(selected - relevant)
    if observed["payloadCodePoints"] > observed["payloadBudgetCodePoints"]:
        metrics["budgetViolations"] += 1
    metrics["totalCostUsd"] += observed["_cost"]


def compare(suite: dict, baseline: dict, candidate: dict, criteria: dict,
            suite_sha: str, criteria_sha: str, approval: dict | None = None,
            baseline_bundle_root: Path | None = None, candidate_bundle_root: Path | None = None,
            run_rollback_rehearsal: bool = False) -> dict:
    index = validate_suite(suite, criteria)
    before = validate_run(baseline, suite, suite_sha, criteria_sha, index["caseIds"])
    after = validate_run(candidate, suite, suite_sha, criteria_sha, index["caseIds"])
    baseline_started = timestamp(baseline.get("startedAt"), "RUN_TIME_INVALID")
    candidate_started = timestamp(candidate.get("startedAt"), "RUN_TIME_INVALID")
    require(index["criteriaLockedAt"] < baseline_started and index["criteriaLockedAt"] < candidate_started
            and index["suiteFrozenAt"] < baseline_started and index["suiteFrozenAt"] < candidate_started,
            "PLAN_NOT_FROZEN_BEFORE_RUNS")
    require(all(before[k]["costBasis"] == after[k]["costBasis"] for k in before), "COST_BASIS_CHANGED")
    plan_kind = criteria.get("comparisonKind")
    require(plan_kind in {"RELEASE_CANDIDATE", "SEARCH_ABLATION"}, "COMPARISON_KIND_INVALID")
    if plan_kind == "SEARCH_ABLATION":
        before_fp, after_fp = baseline["fingerprints"], candidate["fingerprints"]
        differing = [key for key in before_fp if before_fp[key] != after_fp[key]]
        require(differing == ["retrieval"], "ABLATION_MUST_CHANGE_RETRIEVAL_ONLY")
        require(baseline.get("searchVariant") in SEARCH_VARIANTS and candidate.get("searchVariant") in SEARCH_VARIANTS
                and baseline["searchVariant"] != candidate["searchVariant"], "SEARCH_VARIANT_NOT_CHANGED")

    categories = sorted(criteria["categoryThresholds"])
    metric_sets = {"baseline": {"overall": _metric_template()},
                   "candidate": {"overall": _metric_template()}}
    for category in categories:
        metric_sets["baseline"][category] = _metric_template()
        metric_sets["candidate"][category] = _metric_template()
    rows, displaced_by_scope = [], defaultdict(int)
    for case in suite["cases"]:
        cid = case["caseId"]
        b, c = before[cid], after[cid]
        _accumulate(metric_sets["baseline"]["overall"], case, b)
        _accumulate(metric_sets["candidate"]["overall"], case, c)
        for category in case["categoryIds"]:
            _accumulate(metric_sets["baseline"][category], case, b)
            _accumulate(metric_sets["candidate"][category], case, c)
        b_selected, c_selected = set(b["selectedReferenceIds"]), set(c["selectedReferenceIds"])
        relevant = set(case["expectedRelevantReferenceIds"])
        missed_relevant = len(relevant - c_selected)
        metric_sets["baseline"]["overall"]["missedRelevantReferences"] += len(relevant - b_selected)
        metric_sets["candidate"]["overall"]["missedRelevantReferences"] += missed_relevant
        for category in case["categoryIds"]:
            metric_sets["baseline"][category]["missedRelevantReferences"] += len(relevant - b_selected)
            metric_sets["candidate"][category]["missedRelevantReferences"] += missed_relevant
        moved_out = sorted((b_selected & relevant) - c_selected)
        for scope in ("overall", *case["categoryIds"]):
            displaced_by_scope[scope] += len(moved_out)
        rows.append({"caseId": cid, "categoryIds": case["categoryIds"],
                     "baselineDecision": b["decision"], "candidateDecision": c["decision"],
                     "expectedDecision": case["expectedDecision"],
                     "selectedBefore": sorted(b_selected), "selectedAfter": sorted(c_selected),
                     "relevantReferences": sorted(relevant), "displacedRelevant": moved_out,
                     "payloadBefore": b["payloadCodePoints"], "payloadAfter": c["payloadCodePoints"]})
    for metrics in metric_sets.values():
        for scope, group in metrics.items():
            group["displacedRelevantReferences"] = displaced_by_scope[scope]

    failures, improvements = [], []
    require_no_regression = criteria["requireNoQualityRegression"]
    for scope, limits in [("overall", criteria["overallThresholds"]), *criteria["categoryThresholds"].items()]:
        b, c = metric_sets["baseline"][scope], metric_sets["candidate"][scope]
        for key in QUALITY_METRICS:
            if c[key] > limits[key]: failures.append({"scope": scope, "metric": key, "actual": c[key], "maximum": limits[key]})
            if require_no_regression and c[key] > b[key]: failures.append({"scope": scope, "metric": key,
                "actual": c[key], "maximum": b[key], "reason": "WORSE_THAN_BASELINE"})
            if c[key] < b[key]: improvements.append(f"{scope}:{key}")
        max_cost = money(limits["maxCostUsd"])
        if c["totalCostUsd"] > max_cost:
            failures.append({"scope": scope, "metric": "totalCostUsd", "actual": str(c["totalCostUsd"]), "maximum": str(max_cost)})
        if b["totalCostUsd"] and c["totalCostUsd"] / b["totalCostUsd"] > Decimal(str(criteria.get("maximumCostRatio", 1))):
            failures.append({"scope": scope, "metric": "costRatio", "actual": str(c["totalCostUsd"] / b["totalCostUsd"]),
                             "maximum": str(criteria.get("maximumCostRatio", 1))})
    if criteria["requireImprovement"] and not improvements:
        failures.append({"scope": "overall", "metric": "improvement", "reason": "NO_QUALITY_DIMENSION_IMPROVED"})

    require((baseline_bundle_root is None) == (candidate_bundle_root is None), "BUNDLE_PAIR_REQUIRED")
    bundle_checks = None
    rollback_status = "NOT_RUN"
    if baseline_bundle_root is not None and candidate_bundle_root is not None:
        bundle_checks = {
            "baseline": verify_bundle(baseline_bundle_root, baseline["artifactManifest"]),
            "candidate": verify_bundle(candidate_bundle_root, candidate["artifactManifest"]),
        }
        require(bundle_checks["baseline"]["artifactBundleSha256"] == baseline["artifactBundleSha256"]
                and bundle_checks["candidate"]["artifactBundleSha256"] == candidate["artifactBundleSha256"],
                "BUNDLE_DECLARED_HASH_MISMATCH")
        if run_rollback_rehearsal:
            rollback_status = rollback_rehearsal(
                baseline_bundle_root, candidate_bundle_root,
                [row["path"] for row in baseline["artifactManifest"]],
                [row["path"] for row in candidate["artifactManifest"]],
            )["status"]
    else:
        require(not run_rollback_rehearsal, "ROLLBACK_REQUIRES_VERIFIED_BUNDLES")

    require(approval is None or isinstance(approval, dict), "PROMOTION_APPROVAL_INVALID")
    approval = approval or {}
    approval_matches = (approval.get("status") == "USER_APPROVED_FOR_DEPLOYMENT"
                        and approval.get("candidateArtifactBundleSha256") == candidate.get("artifactBundleSha256")
                        and approval.get("criteriaSha256") == criteria_sha
                        and approval.get("rollbackRehearsal") == "PASS"
                        and bundle_checks is not None and rollback_status == "PASS")
    return {
        "schemaVersion": "release-gate-report-1", "suiteId": suite["suiteId"],
        "suiteSha256": suite_sha, "criteriaSha256": criteria_sha,
        "comparisonKind": plan_kind, "baselineArtifactBundleSha256": baseline["artifactBundleSha256"],
        "candidateArtifactBundleSha256": candidate["artifactBundleSha256"],
        "baselineMetrics": metric_sets["baseline"], "candidateMetrics": metric_sets["candidate"],
        "improvedDimensions": sorted(set(improvements)), "failures": failures, "cases": rows,
        "decision": "READY_FOR_HUMAN_REVIEW" if not failures else "REJECT_CANDIDATE",
        "deploymentEligible": bool(not failures and approval_matches),
        "promotionApprovalMatched": approval_matches,
        "artifactBundleVerification": bundle_checks,
        "rollbackRehearsal": rollback_status,
        "automaticPromotion": False, "providerCalls": 0,
    }


def _safe_relative(raw: str) -> PurePosixPath:
    path = PurePosixPath(raw)
    require(not path.is_absolute() and path.parts and all(p not in {"", ".", ".."} for p in path.parts),
            "BUNDLE_PATH_INVALID")
    low = raw.lower()
    require(not any(token in low for token in (".env", "secret", "credential", "private-key", "id_rsa")),
            "SENSITIVE_BUNDLE_PATH")
    return path


def _copy_manifest(source_root: Path, destination: Path, relpaths: list[str]):
    for raw in relpaths:
        rel = _safe_relative(raw)
        src = source_root.joinpath(*rel.parts)
        require(src.is_file() and not src.is_symlink(), "BUNDLE_FILE_MISSING_OR_LINKED")
        parent = source_root
        for part in rel.parts[:-1]:
            parent = parent / part
            require(not parent.is_symlink(), "BUNDLE_FILE_MISSING_OR_LINKED")
        resolved = src.resolve()
        require(resolved.is_relative_to(source_root.resolve()), "BUNDLE_PATH_ESCAPE")
        target = destination.joinpath(*rel.parts)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, target)


def verify_bundle(root: Path, artifacts: list[dict]) -> dict:
    require(root.is_dir() and not root.is_symlink(), "BUNDLE_ROOT_INVALID")
    require(isinstance(artifacts, list) and artifacts, "BUNDLE_MANIFEST_INVALID")
    expected = {}
    for artifact in artifacts:
        path = str(_safe_relative(artifact["path"]))
        require(path not in expected and is_sha(artifact.get("sha256")), "BUNDLE_MANIFEST_INVALID")
        expected[path] = artifact["sha256"]
    actual = {}
    for item in root.rglob("*"):
        require(not item.is_symlink(), "BUNDLE_SYMLINK_FORBIDDEN")
        if item.is_file():
            rel = item.relative_to(root).as_posix()
            actual[rel] = raw_sha(item)
        else:
            require(item.is_dir(), "BUNDLE_ENTRY_TYPE_INVALID")
    require(set(actual) == set(expected), "BUNDLE_FILE_SET_MISMATCH")
    require(actual == expected, "BUNDLE_FILE_HASH_MISMATCH")
    return {"status": "PASS", "artifactCount": len(actual), "artifactBundleSha256": canonical_sha(
        sorted(({"path": path, "sha256": digest} for path, digest in actual.items()), key=lambda row: row["path"]))}


def _clear_stage(stage: Path):
    for child in stage.iterdir():
        if child.is_dir() and not child.is_symlink():
            shutil.rmtree(child)
        else:
            child.unlink()


def rollback_rehearsal(baseline_root: Path, candidate_root: Path,
                       baseline_paths: list[str], candidate_paths: list[str]) -> dict:
    require(baseline_root.is_dir() and candidate_root.is_dir()
            and not baseline_root.is_symlink() and not candidate_root.is_symlink(), "BUNDLE_ROOT_INVALID")
    require(isinstance(baseline_paths, list) and isinstance(candidate_paths, list)
            and baseline_paths and candidate_paths
            and all(isinstance(path, str) for path in baseline_paths + candidate_paths)
            and len(baseline_paths) == len(set(baseline_paths))
            and len(candidate_paths) == len(set(candidate_paths)), "BUNDLE_MANIFEST_INVALID")
    for root, paths in ((baseline_root, baseline_paths), (candidate_root, candidate_paths)):
        for raw in paths:
            rel = _safe_relative(raw)
            source = root.joinpath(*rel.parts)
            require(source.is_file() and not source.is_symlink()
                    and source.resolve().is_relative_to(root.resolve()), "BUNDLE_FILE_MISSING_OR_LINKED")
            parent = root
            for part in rel.parts[:-1]:
                parent = parent / part
                require(not parent.is_symlink(), "BUNDLE_FILE_MISSING_OR_LINKED")
    with tempfile.TemporaryDirectory(prefix="oops-release-rollback-") as tmp:
        stage = Path(tmp) / "active"
        stage.mkdir()
        _copy_manifest(baseline_root, stage, baseline_paths)
        baseline_hashes = {p: raw_sha(baseline_root.joinpath(*_safe_relative(p).parts)) for p in baseline_paths}
        require(all(raw_sha(stage.joinpath(*_safe_relative(p).parts)) == baseline_hashes[p] for p in baseline_paths),
                "BASELINE_STAGE_MISMATCH")
        _clear_stage(stage)
        _copy_manifest(candidate_root, stage, candidate_paths)
        candidate_hashes = {p: raw_sha(candidate_root.joinpath(*_safe_relative(p).parts)) for p in candidate_paths}
        require(all(raw_sha(stage.joinpath(*_safe_relative(p).parts)) == candidate_hashes[p] for p in candidate_paths),
                "CANDIDATE_STAGE_MISMATCH")
        _clear_stage(stage)
        _copy_manifest(baseline_root, stage, baseline_paths)
        restored = {p: raw_sha(stage.joinpath(*_safe_relative(p).parts)) for p in baseline_paths}
        require(restored == baseline_hashes and not any(
            item.is_file() and item.relative_to(stage).as_posix() not in baseline_hashes for item in stage.rglob("*")),
            "ROLLBACK_RESTORE_MISMATCH")
    return {"status": "PASS", "baselinePathsTested": len(baseline_paths),
            "candidatePathsTested": len(candidate_paths), "baselineRestored": True,
            "candidateAppliedInTemporaryDirectory": True, "originalDirectoriesModified": False}


def current_readiness(registry: dict) -> dict:
    """Summarize the checked-in suite registry without upgrading development labels."""
    reasons = []
    if registry.get("purpose") != "INDEPENDENT_HOLDOUT_RELEASE_EVALUATION":
        reasons.append("ACTIVE_SUITE_IS_NOT_INDEPENDENT_HOLDOUT")
    if registry.get("policy", {}).get("modelCallsEnabled") is not True:
        reasons.append("NO_CURRENT_MODEL_RESULT_SET")
    if registry.get("workflow", {}).get("candidate") is None:
        reasons.append("NO_FROZEN_CANDIDATE_VERSION")
    return {"schemaVersion": "release-readiness-audit-1",
            "activeSuite": registry.get("activeSuite"), "purpose": registry.get("purpose"),
            "decision": "BLOCKED" if reasons else "READY_FOR_SUITE_VALIDATION",
            "reasons": reasons, "automaticPromotion": False, "providerCalls": 0}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    compare_parser = sub.add_parser("compare", help="Compare two frozen evaluation runs without model calls")
    compare_parser.add_argument("--suite", type=Path, required=True)
    compare_parser.add_argument("--baseline", type=Path, required=True)
    compare_parser.add_argument("--candidate", type=Path, required=True)
    compare_parser.add_argument("--criteria", type=Path, required=True)
    compare_parser.add_argument("--approval", type=Path)
    compare_parser.add_argument("--baseline-bundle-dir", type=Path)
    compare_parser.add_argument("--candidate-bundle-dir", type=Path)
    compare_parser.add_argument("--rollback-rehearsal", action="store_true")
    rollback = sub.add_parser("rollback-rehearsal", help="Test bundle restore only in a temporary directory")
    rollback.add_argument("--baseline-dir", type=Path, required=True)
    rollback.add_argument("--candidate-dir", type=Path, required=True)
    rollback.add_argument("--manifests", type=Path, required=True)
    readiness = sub.add_parser("current-readiness", help="Check whether the current suite can support release promotion")
    readiness.add_argument("--registry", type=Path, default=Path(__file__).with_name("evaluation-suites.json"))
    args = parser.parse_args()
    if args.command == "rollback-rehearsal":
        manifests = json.loads(args.manifests.read_bytes())
        require(isinstance(manifests, dict) and set(manifests) == {"baselinePaths", "candidatePaths"},
                "BUNDLE_MANIFEST_INVALID")
        result = rollback_rehearsal(args.baseline_dir, args.candidate_dir,
                                    manifests["baselinePaths"], manifests["candidatePaths"])
    elif args.command == "current-readiness":
        result = current_readiness(json.loads(args.registry.read_bytes()))
    else:
        suite_raw, criteria_raw = args.suite.read_bytes(), args.criteria.read_bytes()
        suite, criteria = json.loads(suite_raw), json.loads(criteria_raw)
        approval = json.loads(args.approval.read_bytes()) if args.approval else None
        require((args.baseline_bundle_dir is None) == (args.candidate_bundle_dir is None), "BUNDLE_PAIR_REQUIRED")
        result = compare(suite, json.loads(args.baseline.read_bytes()), json.loads(args.candidate.read_bytes()),
                         criteria, hashlib.sha256(suite_raw).hexdigest(), hashlib.sha256(criteria_raw).hexdigest(), approval,
                         args.baseline_bundle_dir,
                         args.candidate_bundle_dir,
                         args.rollback_rehearsal)
    print(json.dumps(result, ensure_ascii=False, default=lambda value: str(value) if isinstance(value, Decimal) else value,
                     sort_keys=True, indent=2))
    if result.get("decision") in {"REJECT_CANDIDATE", "BLOCKED"}:
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
