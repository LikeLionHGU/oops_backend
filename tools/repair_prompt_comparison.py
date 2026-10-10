"""At most three explicit target-contract repairs of saved contrast-only responses.

No discovery replay, production mutation, automatic retry or label approval.
Each selected response gets one independent re-judgment, not a forced warning.
"""
import argparse
import copy
import json
import re
import subprocess
import textwrap
from pathlib import Path

from compare_neutral_contract import CURRENT, neutralize
from compare_review_prompts import ROOT, ENGINE, digest
from ablate_review_prompts import export_audits
from build_review_guidelines import write_private
from collect_youtube_comments import read_key
from replay_draft_cases import complete, prompts


def run(report_path, audit_path, archive_path, output, execute=False):
    output = Path(output)
    if output.exists() or any(output.parent.glob("ablation-*-report.json")):
        raise ValueError("NEW_OUTPUT_DIRECTORY_REQUIRED")
    report = json.loads(Path(report_path).read_text())
    audit = json.loads(Path(audit_path).read_text())
    if (report.get("profile") != "contrast-only" or report.get("previousCommit") != CURRENT
            or not 1 <= len(report["calls"]) <= 22 or audit.get("sourceReportSha256") != digest(report)):
        raise ValueError("MATCHED_CONTRAST_AUDIT_REQUIRED")
    selected = [r for r in audit["results"] if r["arm"] == "contrast-only-proposal"
                and not r["accepted"] and r.get("failureCode") in
                {"TARGET_EVIDENCE_REQUIRED", "TARGET_MENTION_RELATION"}][:3]
    if not selected:
        raise ValueError("REPAIRABLE_TARGET_FAILURE_REQUIRED")
    source = subprocess.check_output(["git", "show", CURRENT + ":" + ENGINE], cwd=ROOT, text=True)
    library = (ROOT / "oops-backend/src/main/java/com/example/oops/analyzer/ReviewGuidelineLibrary.java").read_text()
    contract = textwrap.dedent(re.search(r'String CONTRACT = """\n(.*?)\n\s*""";', library, re.S)[1]) + "\n"
    archive = json.loads(Path(archive_path).read_text())
    if archive.get("schemaVersion") != "review-guidelines-4":
        raise ValueError("REFERENCE_SCHEMA")
    patterns = [{k: r[k] for k in ("id", "axis", "condition", "normalContrast", "requiredEvidence", "missingContext")}
                for r in archive["guidelines"]]
    selected_examples = [e for e in archive["examples"] if e["id"] in {"pisik-B", "pisik-C"}]
    reference = {"reviewGuidelines": patterns, "referenceContexts": [
        {"mechanismIds": e["mechanismIds"], "context": e["context"]} for e in selected_examples]}
    if digest(reference) != report["referenceSha256"]:
        raise ValueError("FROZEN_REFERENCE_REQUIRED")
    system = neutralize(prompts(source)[2], "contrast-only") + "\n" + contract + "\ncontextReference=" + json.dumps(reference, ensure_ascii=False)
    if digest(system) != report["armPromptSha256"]["contrast-only-proposal"]:
        raise ValueError("FROZEN_SYSTEM_REQUIRED")
    correction = textwrap.dedent(re.search(r'static final String TARGET_REPAIR_PROMPT = """\n(.*?)\n\s*""";', source, re.S)[1]) + "\n"
    system += "\n" + correction
    cases = [copy.deepcopy(c) for c in report["cases"] if c["name"] in {r["case"] for r in selected}]
    for case in cases:
        failures = {r["failureCode"] for r in selected if r["case"] == case["name"]}
        if len(failures) != 1:
            raise ValueError("SINGLE_CASE_FAILURE_REQUIRED")
        case["payload"]["repair"] = {"attempt": 1, "failureCode": next(iter(failures))}
        case["inputSha256"] = digest(case["payload"])
    result = {"schemaVersion": "saved-target-repair-comparison-1", "scope": "FIXED_CANDIDATE_REPAIR_NOT_FULL_PIPELINE",
              "sourceReportSha256": digest(report), "sourceAuditSha256": digest(audit), "model": "gpt-6-luna",
              "systemSha256": digest(system), "plannedCalls": len(selected), "execute": execute,
              "cases": cases, "calls": []}
    write_private(output, result)
    if execute:
        key = read_key(ROOT / ".env", key_name="OPENAI_API_KEY")
        for entry in selected:
            case = next(c for c in cases if c["name"] == entry["case"])
            call = {"case": case["name"], "arm": "contrast-only-repair", "repeat": entry["repeat"],
                    "inputSha256": case["inputSha256"], "initialFailure": entry["failureCode"]}
            try:
                call.update(complete(key, system, case["payload"]))
            except Exception as error:
                call["error"] = type(error).__name__
            result["calls"].append(call)
            write_private(output, result)
            print(json.dumps({"case": call["case"], "repeat": call["repeat"], "error": call.get("error"),
                              "decisions": [v.get("assessment", {}).get("decision") for v in call.get("output", {}).get("verifications", [])]}), flush=True)
        export_audits(result, output.parent)
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("report", "audit", "archive", "output"):
        parser.add_argument(name)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    result = run(args.report, args.audit, args.archive, args.output, args.execute)
    print(json.dumps({"plannedCalls": result["plannedCalls"], "executedCalls": len(result["calls"])}))
