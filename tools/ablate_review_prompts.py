"""Fixed-input instruction ablation; dry-run default, at most 28 paid calls.

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


def boundary_cases(cases):
    result = [case for case in cases if case["name"] == "B-23.5"]
    if len(result) != 1:
        raise ValueError("B_CASE_REQUIRED")
    controls = [
        ("boundary-choice", ["이 동네에는 햄버거 가게가 없어.", "나는 햄버거가 먹고 싶어서 집에서 만들어 먹었어.",
                             "사 먹는 것보다 재료를 직접 고를 수 있어서 좋더라."], "PASS"),
        ("boundary-menu", ["여기는 오늘 메뉴가 하나뿐이네.", "주는 대로 먹어야 돼.",
                           "내 입맛에는 별로였고 이 가격이면 돈이 아까워."], "PASS"),
        ("boundary-access", ["여기에는 그 매장이 없어서 선택지가 적어.", "다음에는 근처 다른 동네까지 가야겠네.",
                             "차가 없으면 먹고 싶은 걸 사 먹기 불편하겠다."], "PASS"),
        ("boundary-belittlement", ["이 동네에는 햄버거 가게가 없어.", "여기 사람들은 수준이 낮아서 이런 대체 음식이나 먹는 거지.",
                                  "저 사람들의 생활이 참 한심해."], "REVIEW_REQUIRED"),
    ]
    for name, texts, expected in controls:
        raw = [{"id": f"stt-replay-{i}", "type": "SPEECH", "startMs": i * 1500,
                "endMs": (i + 1) * 1500, "text": text} for i, text in enumerate(texts)]
        payload = {"promptRevision": cases[0]["payload"]["promptRevision"], "raw": raw,
                   "candidates": [{"candidateId": "candidate-1", "anchorId": raw[1]["id"],
                       "axis": "TARGET_TREATMENT", "hypothesisNotEvidence": "발언들의 연결에 별도 대상 평가가 있는지 검토한다.",
                       "proposedEvidence": [{"segmentId": row["id"], "quote": row["text"]} for row in raw],
                       "contextExpanded": False, "contextLimited": False, "segmentIds": [row["id"] for row in raw]}]}
        # Expectations are development annotations outside the provider payload, not approved labels.
        result.append({"name": name, "payload": payload, "inputSha256": digest(payload),
                       "developmentExpectation": expected})
    return result


def schedule(cases, arms, repeats, profile="initial"):
    if repeats not in {1, 2, 3}:
        raise ValueError("REPEAT_LIMIT")
    planned = []
    for case in cases:
        selected = arms if profile == "boundary" or case["name"] == "C" else [arms[0], arms[-1]]
        for repeat in range(repeats if case["name"] in {"B-23.5", "C"} else 1):
            for arm in selected if repeat % 2 == 0 else list(reversed(selected)):
                planned.append((case, repeat, arm))
    if profile not in {"initial", "boundary"}:
        raise ValueError("PROFILE_REQUIRED")
    if len(planned) > (28 if profile == "boundary" else 22):
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


def run(snapshot_path, archive_path, output, execute=False, repeats=3, profile="initial"):
    if profile not in {"initial", "boundary"}:
        raise ValueError("PROFILE_REQUIRED")
    # Refuse to overwrite a previous paid run or its exported response audit inputs.
    if profile == "boundary" and (Path(output).exists() or any(Path(output).parent.glob("ablation-*-report.json"))):
        raise ValueError("NEW_OUTPUT_DIRECTORY_REQUIRED")
    snapshot = json.loads(Path(snapshot_path).read_text())
    cases, reference = prepare(snapshot, json.loads(Path(archive_path).read_text()), b_anchor=23500)
    if profile == "boundary":
        cases = boundary_cases(cases)
    old_source = subprocess.check_output(["git", "show", BASELINE + ":" + ENGINE], cwd=ROOT, text=True)
    library = (ROOT / "oops-backend/src/main/java/com/example/oops/analyzer/ReviewGuidelineLibrary.java").read_text()
    contract = textwrap.dedent(re.search(r'String CONTRACT = """\n(.*?)\n\s*""";', library, re.S)[1]) + "\n"
    suffix = "\n" + contract + "\ncontextReference=" + json.dumps(reference, ensure_ascii=False)
    arms = [(name, system + suffix) for name, system in ablation_arms(
        prompts(old_source)[2], prompts((ROOT / ENGINE).read_text())[2])]
    planned = schedule(cases, arms, repeats, profile)
    report = {"schemaVersion": "fixed-prompt-ablation-1", "scope": "DEVELOPMENT_CALIBRATION_NOT_HISTORICAL_REPLAY",
              "baselineCommit": BASELINE, "currentCommit": subprocess.check_output(
                  ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
              "profile": profile, "model": "gpt-6-luna", "plannedCalls": len(planned), "execute": execute,
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
    parser.add_argument("--profile", choices=["initial", "boundary"], default="initial")
    args = parser.parse_args()
    result = run(args.snapshot, args.archive, Path(args.output), args.execute, args.repeats, args.profile)
    print(json.dumps({"plannedCalls": result["plannedCalls"], "executedCalls": len(result["calls"])}))
