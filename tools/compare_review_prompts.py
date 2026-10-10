"""Bounded paid comparison of frozen text inputs; not a historical pipeline replay.

No discovery, STT, images, repairs, dataset approvals or production changes.
The known family and synthetic controls are development calibration only.
"""
import argparse
import hashlib
import json
import re
import subprocess
import textwrap
from pathlib import Path

from build_review_guidelines import write_private
from collect_youtube_comments import read_key
from replay_draft_cases import prompts, complete

ROOT = Path(__file__).resolve().parents[1]
ENGINE = "oops-backend/src/main/java/com/example/oops/analyzer/CandidateReviewEngine.java"
BASELINE = "870dd72"


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True).encode()).hexdigest()


def prepare(snapshot, archive):
    rows = snapshot["transcript"]
    if not 1 <= len(rows) <= 100:
        raise ValueError("TRANSCRIPT_LIMIT")
    for row in rows:
        if (type(row.get("startMs")) is not int or type(row.get("endMs")) is not int
                or not 0 <= row["startMs"] < row["endMs"] or not isinstance(row.get("text"), str)
                or not row["text"].strip()):
            raise ValueError("INVALID_TRANSCRIPT")
    if archive.get("schemaVersion") != "review-guidelines-4":
        raise ValueError("REFERENCE_SCHEMA")
    patterns = [{k: r[k] for k in ("id", "axis", "condition", "normalContrast", "requiredEvidence", "missingContext")}
                for r in archive["guidelines"]]
    selected = [e for e in archive["examples"] if e["id"] in {"pisik-B", "pisik-C"}]
    if len(selected) != 2:
        raise ValueError("FIXED_REFERENCES_REQUIRED")
    reference = {"reviewGuidelines": patterns, "referenceContexts": [
        {"mechanismIds": e["mechanismIds"], "context": e["context"]} for e in selected]}
    if len(json.dumps(reference, ensure_ascii=False)) > 6000:
        raise ValueError("REFERENCE_BUDGET")
    cases = []
    def add(name, source, start, end, focus_times, hypothesis):
        window = [r for r in source if start <= r["startMs"] and r["endMs"] <= end]
        if not 1 <= len(window) <= 48 or end - start > 60000:
            raise ValueError("WINDOW_LIMIT")
        raw = [{"id": f"stt-replay-{i}", "type": "SPEECH", **r} for i, r in enumerate(window)]
        quotes = []
        for time in focus_times:
            matches = [r for r in raw if r["startMs"] == time]
            if len(matches) != 1:
                raise ValueError("UNIQUE_QUOTE_REQUIRED")
            quotes.append({"segmentId": matches[0]["id"], "quote": matches[0]["text"]})
        payload = {"promptRevision": "2026-10-10-discovery-window-contract-28", "raw": raw,
                   "candidates": [{"candidateId": "candidate-1", "anchorId": quotes[0]["segmentId"],
                       "axis": "TARGET_TREATMENT", "hypothesisNotEvidence": hypothesis,
                       "proposedEvidence": quotes, "contextExpanded": False,
                       "contextLimited": name in {"B", "C"}, "segmentIds": [r["id"] for r in raw]}]}
        cases.append({"name": name, "payload": payload, "inputSha256": digest(payload)})
    add("B", rows, 1500, 61500, [40500, 35000, 36500, 39000],
        "매장 부재·사연·타인의 대체 음식 설명의 연결에 별도 대상 평가가 있는지 검토한다.")
    add("C", rows, 30500, 90500, [70500, 69500, 72000],
        "메뉴 의미 평가와 주어진 대로 먹는다는 연결에 별도 대상 평가가 있는지 검토한다.")
    for name, texts in [("normal-choice", ["이 동네에는 그 가게가 없네.", "나는 햄버거가 먹고 싶어서 집에서 직접 만들었어."]),
                        ("normal-review", ["나는 이 메뉴가 특색 없고 가격에 비해 의미 없다고 느꼈어.",
                                           "오늘 선택 메뉴가 없어서 주는 대로 먹었지만 직원이나 주민을 평가하는 이야기는 아니야."])]:
        source = [{"startMs": i * 1500, "endMs": (i + 1) * 1500, "text": text} for i, text in enumerate(texts)]
        add(name, source, 0, 3000, [0, 1500], "두 발언의 연결에 별도 대상 평가가 있는지 검토한다.")
    return cases, reference


def run(snapshot_path, archive_path, output, execute=False, repeats=3):
    if repeats not in {1, 2, 3}:
        raise ValueError("REPEAT_LIMIT")
    snapshot = json.loads(Path(snapshot_path).read_text())
    archive = json.loads(Path(archive_path).read_text())
    cases, reference = prepare(snapshot, archive)
    baseline_source = subprocess.check_output(["git", "show", BASELINE + ":" + ENGINE], cwd=ROOT, text=True)
    current_source = (ROOT / ENGINE).read_text()
    library = (ROOT / "oops-backend/src/main/java/com/example/oops/analyzer/ReviewGuidelineLibrary.java").read_text()
    contract = textwrap.dedent(re.search(r'String CONTRACT = """\n(.*?)\n\s*""";', library, re.S)[1]) + "\n"
    suffix = "\n" + contract + "\ncontextReference=" + json.dumps(reference, ensure_ascii=False)
    arms = [("baseline-28", prompts(baseline_source)[2] + suffix), ("current-31", prompts(current_source)[2] + suffix)]
    planned = 2 * (2 * repeats + 2)
    if planned > 16:
        raise ValueError("CALL_LIMIT")
    report = {"schemaVersion": "fixed-prompt-comparison-1", "scope": "TEXT_ONLY_FIXED_CANDIDATE_DEVELOPMENT_CALIBRATION",
              "baselineCommit": BASELINE, "currentCommit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
              "model": "gpt-6-luna", "plannedCalls": planned, "execute": execute,
              "referenceSha256": digest(reference), "sourceTranscriptSha256": digest(snapshot["transcript"]),
              "armPromptSha256": {name: digest(system) for name, system in arms}, "cases": cases, "calls": []}
    if execute:
        key = read_key(ROOT / ".env", key_name="OPENAI_API_KEY")
        for case in cases:
            for repeat in range(repeats if case["name"] in {"B", "C"} else 1):
                # Alternate order to avoid always evaluating one arm first; no automatic retries.
                for name, system in (arms if repeat % 2 == 0 else list(reversed(arms))):
                    item = {"case": case["name"], "arm": name, "repeat": repeat, "inputSha256": case["inputSha256"]}
                    try:
                        item.update(complete(key, system, case["payload"]))
                    except Exception as error:
                        item["error"] = type(error).__name__
                    report["calls"].append(item)
                    write_private(output, report)
                    decisions = [v.get("assessment", {}).get("decision") for v in item.get("output", {}).get("verifications", [])]
                    print(json.dumps({k: item[k] for k in ["case", "arm", "repeat"]} | {"decisions": decisions, "error": item.get("error")}), flush=True)
    write_private(output, report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("snapshot"); parser.add_argument("archive"); parser.add_argument("output")
    parser.add_argument("--execute", action="store_true"); parser.add_argument("--repeats", type=int, default=3)
    args = parser.parse_args()
    result = run(args.snapshot, args.archive, Path(args.output), args.execute, args.repeats)
    print(json.dumps({"saved": True, "executedCalls": len(result["calls"]), "plannedCalls": result["plannedCalls"]}))


if __name__ == "__main__":
    main()
