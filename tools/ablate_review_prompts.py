"""Fixed-input instruction ablation; dry-run default, at most 22 paid calls.

Development calibration only. No runtime prompt changes or automatic repairs.
"""
import argparse
import difflib
import json
import re
import subprocess
import textwrap
from pathlib import Path

from compare_review_prompts import ROOT, ENGINE, BASELINE, digest, prepare
from replay_draft_cases import prompts, complete
from collect_youtube_comments import read_key
from build_review_guidelines import write_private


def ablation_arms(old, current):
    before, after = old.splitlines(keepends=True), current.splitlines(keepends=True)
    insertions = []
    for tag, i, j, k, l in difflib.SequenceMatcher(None, before, after).get_opcodes():
        if tag == "equal":
            continue
        if tag != "insert":
            raise ValueError("UNEXPECTED_PROMPT_CHANGE")
        insertions.append((i, "".join(after[k:l])))
    if (len(insertions) != 2 or "상황 → 평가 → 평가 대상" not in insertions[0][1]
            or "PASS에도 대조 해석이 필수" not in insertions[1][1]):
        raise ValueError("ABLATION_BLOCKS_CHANGED")
    def single(index):
        offset, block = insertions[index]
        return "".join(before[:offset]) + block + "".join(before[offset:])
    return [("baseline-28", old), ("judgment-only", single(0)),
            ("pass-contract-only", single(1)), ("current-31", current)]


def schedule(cases, arms, repeats):
    if repeats not in {1, 2, 3}:
        raise ValueError("REPEAT_LIMIT")
    planned = []
    for case in cases:
        selected = arms if case["name"] == "C" else [arms[0], arms[-1]]
        for repeat in range(repeats if case["name"] in {"B-23.5", "C"} else 1):
            for arm in selected if repeat % 2 == 0 else list(reversed(selected)):
                planned.append((case, repeat, arm))
    if len(planned) > 22:
        raise ValueError("CALL_LIMIT")
    return planned


def export_audits(report, directory):
    """Export fixed proposals, NOT real discovery, for the current Java validator."""
    for case in report["cases"]:
        payload = case["payload"]
        candidate = payload["candidates"][0]
        proposal = {"axis": candidate["axis"], "anchorId": candidate["anchorId"],
                    "reason": candidate["hypothesisNotEvidence"], "evidence": candidate["proposedEvidence"]}
        rows = [{k: row[k] for k in ("startMs", "endMs", "text")} for row in payload["raw"]]
        arms = []
        for call in report["calls"]:
            if call["case"] != case["name"] or "error" in call:
                continue
            arms.append({"name": f'{call["arm"]}-{call["repeat"]}',
                         "calls": [{"output": {"candidates": [proposal]}}],
                         "verifications": call["output"].get("verifications", [])})
        write_private(directory / f'ablation-{case["name"]}-input.json', {"transcript": rows})
        write_private(directory / f'ablation-{case["name"]}-report.json', {"arms": arms})


def run(snapshot_path, archive_path, output, execute=False, repeats=3):
    snapshot = json.loads(Path(snapshot_path).read_text())
    cases, reference = prepare(snapshot, json.loads(Path(archive_path).read_text()), b_anchor=23500)
    old_source = subprocess.check_output(["git", "show", BASELINE + ":" + ENGINE], cwd=ROOT, text=True)
    library = (ROOT / "oops-backend/src/main/java/com/example/oops/analyzer/ReviewGuidelineLibrary.java").read_text()
    contract = textwrap.dedent(re.search(r'String CONTRACT = """\n(.*?)\n\s*""";', library, re.S)[1]) + "\n"
    suffix = "\n" + contract + "\ncontextReference=" + json.dumps(reference, ensure_ascii=False)
    arms = [(name, system + suffix) for name, system in ablation_arms(
        prompts(old_source)[2], prompts((ROOT / ENGINE).read_text())[2])]
    planned = schedule(cases, arms, repeats)
    report = {"schemaVersion": "fixed-prompt-ablation-1", "scope": "DEVELOPMENT_CALIBRATION_NOT_HISTORICAL_REPLAY",
              "baselineCommit": BASELINE, "currentCommit": subprocess.check_output(
                  ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
              "model": "gpt-6-luna", "plannedCalls": len(planned), "execute": execute,
              "referenceSha256": digest(reference), "sourceTranscriptSha256": digest(snapshot["transcript"]),
              "armPromptSha256": {name: digest(system) for name, system in arms}, "cases": cases, "calls": []}
    write_private(output, report)
    if execute:
        key = read_key(ROOT / ".env", key_name="OPENAI_API_KEY")
        for case, repeat, (name, system) in planned:
            call = {"case": case["name"], "arm": name, "repeat": repeat, "inputSha256": case["inputSha256"]}
            try:
                call.update(complete(key, system, case["payload"]))
            except Exception as error:
                call["error"] = type(error).__name__
            report["calls"].append(call)
            write_private(output, report)
            print(json.dumps({"case": call["case"], "arm": name, "repeat": repeat,
                "decisions": [v.get("assessment", {}).get("decision") for v in call.get("output", {}).get("verifications", [])],
                "error": call.get("error")}), flush=True)
        export_audits(report, Path(output).parent)
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ("snapshot", "archive", "output"):
        parser.add_argument(arg)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--repeats", type=int, default=3)
    args = parser.parse_args()
    result = run(args.snapshot, args.archive, Path(args.output), args.execute, args.repeats)
    print(json.dumps({"plannedCalls": result["plannedCalls"], "executedCalls": len(result["calls"])}))
