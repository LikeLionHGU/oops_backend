"""Audit saved single-candidate responses with the current Java validator, no AI calls.

Expectations, if present, are synthetic development annotations, not training labels.
"""
import argparse
import json
import os
import re
import subprocess
from pathlib import Path

from ablate_review_prompts import ROOT, export_audits
from compare_review_prompts import ENGINE, digest
from build_review_guidelines import write_private


def summarize(case, calls, audit):
    entries = {arm["name"]: arm["assessments"] for arm in audit["arms"]}
    results = []
    for call in calls:
        name = f'{call["arm"]}-{call["repeat"]}'
        item = {"case": case["name"], "arm": call["arm"], "repeat": call["repeat"], "accepted": False}
        if "error" in call:
            item["failureCode"] = "PROVIDER_FAILURE"
        else:
            verifications = call.get("output", {}).get("verifications", [])
            assessments = entries.get(name, [])
            if (len(verifications) != 1 or verifications[0].get("candidateId") != "candidate-1"
                    or len(assessments) != 1):
                item["failureCode"] = "MODEL_VERIFICATION_SHAPE"
            else:
                item.update({"decision": verifications[0].get("assessment", {}).get("decision"),
                             "accepted": assessments[0]["accepted"], "failureCode": assessments[0]["failureCode"]})
        expected = case.get("developmentExpectation")
        if expected is not None:
            item["developmentExpectation"] = expected
            item["matchesDevelopmentExpectationAndContract"] = item["accepted"] and item.get("decision") == expected
        results.append(item)
    return results


def run(report_path, archive_path, output):
    report_path, output = Path(report_path).resolve(), Path(output).resolve()
    if output.exists() or output == report_path:
        raise ValueError("NEW_AUDIT_OUTPUT_REQUIRED")
    report = json.loads(report_path.read_text())
    if not 1 <= len(report["cases"]) <= 8 or not 1 <= len(report["calls"]) <= 28:
        raise ValueError("AUDIT_LIMIT")
    if any(not re.fullmatch(r"[A-Za-z0-9.-]+", c["name"]) for c in report["cases"]):
        raise ValueError("CASE_NAME_REQUIRED")
    export_audits(report, report_path.parent)
    result = {"schemaVersion": "current-validator-prompt-audit-1", "fullPipelineReplayed": False,
              "historicalValidatorReplayed": False, "sourceReportSha256": digest(report),
              "validatorSourceSha256": digest((ROOT / ENGINE).read_text()), "results": [], "caseAudits": []}
    env = dict(os.environ)
    env["JAVA_HOME"] = "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
    for case in report["cases"]:
        calls = [call for call in report["calls"] if call["case"] == case["name"]]
        args = ["gradle", "-Dorg.gradle.java.installations.paths=" + env["JAVA_HOME"],
                "reviewGuidelineReplay", "-PreplayInput=" + str(report_path.parent / f'ablation-{case["name"]}-input.json'),
                "-PreplayReport=" + str(report_path.parent / f'ablation-{case["name"]}-report.json'),
                "-PguidelineArchive=" + str(Path(archive_path).resolve()), "--no-daemon"]
        process = subprocess.run(args, cwd=ROOT / "oops-backend", env=env, capture_output=True, text=True, timeout=60)
        if process.returncode:
            raise ValueError("JAVA_AUDIT_FAILED")
        lines = [line for line in process.stdout.splitlines() if line.startswith("{")]
        if len(lines) != 1:
            raise ValueError("JAVA_AUDIT_OUTPUT_REQUIRED")
        audit = json.loads(lines[0])
        result["caseAudits"].append({"case": case["name"], "audit": audit})
        results = summarize(case, calls, audit)
        result["results"].extend(results)
        write_private(output, result)
        print(json.dumps({"case": case["name"], "responses": len(results),
                          "accepted": sum(r["accepted"] for r in results)}), flush=True)
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ("report", "archive", "output"):
        parser.add_argument(arg)
    args = parser.parse_args()
    run(args.report, args.archive, args.output)
