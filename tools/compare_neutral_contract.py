"""Bounded revision-31 versus neutral contract proposal; no production mutation.

Default plan-only. Known-family calibration, not an independent quality benchmark.
"""
import argparse
import json
import re
import subprocess
import textwrap
from pathlib import Path

from ablate_review_prompts import boundary_cases, export_audits
from compare_review_prompts import ROOT, ENGINE, digest, prepare
from build_review_guidelines import write_private
from collect_youtube_comments import read_key
from replay_draft_cases import complete, prompts

PREVIOUS = "4e4e3fb"
CURRENT = "3d15941"
CURRENT_33 = "a1cd646"
OLD_CONTRAST = """reason은 400자 이내, alternativeInterpretation은 300자 이내로 정상 해석이 설명하는 범위와
  별도의 검토 이유가 남는지를 적는다. REVIEW_REQUIRED에는 대조 해석이 필수다.
  TARGET_TREATMENT의 proposedEvidence가 서로 다른 두 줄 이상이면 PASS에도 대조 해석이 필수다.
  이 PASS의 reason에는 전체 연결의 정상 해석을, alternativeInterpretation에는 가장 강한 비판 가설과
  그 가설을 현재 원문이 뒷받침하지 않는 이유를 적는다. 후보 이유를 사실로 받아들이지 않는다.
  또한 proposedEvidence의 anchor 이외 줄에서 실제 연결을 설명하는 CONTEXT 인용을 하나 이상 반환한다.
  필요한 연결을 확인할 수 없으면 UNCERTAIN이다. 통과·경고 어느 쪽도 강제하지 않는다.
"""
NEW_CONTRAST = """정상 해석과 검토 필요 해석을 같은 원문 근거로 대조한 뒤 판정을 정한다. 후보 이유는 사실이 아니다.
reason에는 판정의 핵심 근거를 400자 이내로, alternativeInterpretation에는 반대 해석과
  그 해석을 채택하거나 배제한 원문 근거를 300자 이내로 적는다. 어느 판정도 먼저 정해 설명하지 않는다.
  REVIEW_REQUIRED 및 proposedEvidence가 서로 다른 두 줄 이상인 TARGET_TREATMENT의 PASS에는 대조가 필수다.
  이 PASS는 proposedEvidence의 anchor 이외 줄에서 실제 연결을 설명하는 CONTEXT 인용을 하나 이상 반환한다.
  필요한 연결을 확인할 필수 정보가 부족하면 UNCERTAIN이다. 통과·경고 어느 쪽도 강제하지 않는다.
"""
OLD_TARGET = """대상 평가가 이유이면 target과 TARGET 인용 및 targetType·targetRelation·targetReason이 필요하다.
  target은 문맥상 해석한 대상, targetMention은 TARGET 인용에 그대로 있는 지칭어(각 200자 이내)다.
  EXPLICIT은 targetMention이 target과 같아야 한다. CONTEXTUAL은 '여기' 같은 지칭어와
  별도 발언의 CONTEXT 인용을 함께 반환하고 targetReason에 둘의 연결 근거를 설명한다.
  시간상 인접함만으로 대상을 연결하지 않는다. 연결이 불분명하면 UNCERTAIN이다.
  REVIEW_REQUIRED 반환 전 targetMention이 TARGET.quote에 실제로 있는지,
  targetReason이 비어 있지 않은지, CONTEXTUAL이면 별도 CONTEXT 인용이 있는지 점검한다.
  표현 자체에는 대상을 억지로 만들지 않는다. 기존 직접 지칭 응답은 targetMention=null도 허용한다.
출력 순서: 먼저 TARGET 원문을 고르고 그 안의 연속 문자열을 targetMention으로 복사한다.
  다음에 해석한 대상 target을 적는다. 해석한 대상 이름을 원문 인용으로 바꾸거나 역으로 꾸미지 않는다.
  같은 줄을 PRIMARY와 TARGET으로 각각 인용해도 된다. CONTEXTUAL의 별도 CONTEXT는
  다른 segmentId에서 복사한다. 실제 대상 연결 근거가 없으면 UNCERTAIN이지 근거 생성이 아니다.
"""
NEW_TARGET = """대상 평가로 REVIEW_REQUIRED를 반환하면 target과 실제 TARGET 역할 인용,
  targetType·targetRelation·targetReason이 필요하다. 표현 자체에는 대상을 억지로 만들지 않는다.
대상 작성 순서: TARGET.quote에서 연속 지칭어 targetMention을 그대로 복사한 뒤 해석 대상 target을 적는다.
  target·targetMention은 각각 200자 이내다. 같은 줄을 PRIMARY와 TARGET으로 각각 인용해도 된다.
  target과 targetMention이 동일할 때만 EXPLICIT을 사용한다. 다르면 CONTEXTUAL로
  다른 segmentId의 실제 CONTEXT 인용과 targetReason에 대상 연결 근거를 함께 반환한다.
  시간상 인접함만으로 대상을 연결하지 않는다. 연결에 필수 정보가 없으면 UNCERTAIN이다.
  반환 전 TARGET 역할 인용 존재, targetMention이 TARGET.quote에 포함되는지,
  EXPLICIT의 두 값 일치 또는 CONTEXTUAL의 별도 CONTEXT·연결 설명을 점검한다.
  원문·대상·관계를 형식에 맞춰 바꾸거나 만들어 보충하지 않는다.
  기존 직접 지칭 응답은 targetMention=null도 허용하지만, 이때 target 자체가 TARGET 인용에 있어야 한다.
"""


def neutralize(system, profile="combined"):
    if profile not in {"combined", "contrast-only", "contrast-33"}:
        raise ValueError("PROFILE_REQUIRED")
    blocks = [(OLD_CONTRAST, NEW_CONTRAST)]
    if profile == "combined":
        blocks.append((OLD_TARGET, NEW_TARGET))
    for old, new in blocks:
        if system.count(old) != 1:
            raise ValueError("PINNED_PROMPT_BLOCK_REQUIRED")
        system = system.replace(old, new)
    return system


def expression_case(snapshot):
    rows = [r for r in snapshot["transcript"] if 51500 <= r["startMs"] and r["endMs"] <= 80500]
    if not 1 <= len(rows) <= 48:
        raise ValueError("EXPRESSION_WINDOW_REQUIRED")
    raw = [{"id": f"stt-replay-{i}", "type": "SPEECH", **row} for i, row in enumerate(rows)]
    quotes = []
    for time in (79000, 75000):
        matches = [r for r in raw if r["startMs"] == time]
        if len(matches) != 1:
            raise ValueError("UNIQUE_QUOTE_REQUIRED")
        quotes.append({"segmentId": matches[0]["id"], "quote": matches[0]["text"]})
    payload = {"promptRevision": "2026-10-11-target-role-repair-32", "raw": raw,
               "candidates": [{"candidateId": "candidate-1", "anchorId": quotes[0]["segmentId"],
                   "axis": "EXPRESSION_CONTENT", "hypothesisNotEvidence": "표현 자체에 별도 검토 이유가 있는지 원문으로 확인한다.",
                   "proposedEvidence": quotes, "contextExpanded": False, "contextLimited": False,
                   "segmentIds": [r["id"] for r in raw]}]}
    return {"name": "D", "payload": payload, "inputSha256": digest(payload)}


def schedule(cases, arms, repeats):
    if repeats not in {1, 2, 3}:
        raise ValueError("REPEAT_LIMIT")
    result = []
    for case in cases:
        for repeat in range(repeats if case["name"] in {"B-23.5", "C"} else 1):
            for arm in arms if repeat % 2 == 0 else list(reversed(arms)):
                result.append((case, repeat, arm))
    if len(result) > 22:
        raise ValueError("CALL_LIMIT")
    return result


def run(snapshot_path, archive_path, output, execute=False, repeats=3, profile="combined"):
    if profile not in {"combined", "contrast-only", "contrast-33"}:
        raise ValueError("PROFILE_REQUIRED")
    output = Path(output)
    if output.exists() or any(output.parent.glob("ablation-*-report.json")):
        raise ValueError("NEW_OUTPUT_DIRECTORY_REQUIRED")
    snapshot = json.loads(Path(snapshot_path).read_text())
    cases, reference = prepare(snapshot, json.loads(Path(archive_path).read_text()), b_anchor=23500)
    controls = boundary_cases(cases)[1:]
    cases = cases[:2] + controls
    previous = {"combined": PREVIOUS, "contrast-only": CURRENT, "contrast-33": CURRENT_33}[profile]
    if profile != "combined":
        cases.append(expression_case(snapshot))
    source = subprocess.check_output(["git", "show", previous + ":" + ENGINE], cwd=ROOT, text=True)
    old = prompts(source)[2]
    library = (ROOT / "oops-backend/src/main/java/com/example/oops/analyzer/ReviewGuidelineLibrary.java").read_text()
    contract = textwrap.dedent(re.search(r'String CONTRACT = """\n(.*?)\n\s*""";', library, re.S)[1]) + "\n"
    suffix = "\n" + contract + "\ncontextReference=" + json.dumps(reference, ensure_ascii=False)
    arms = [({"combined": "previous-31", "contrast-only": "current-32", "contrast-33": "current-33"}[profile], old + suffix),
            (profile + "-proposal" if profile != "combined" else "neutral-proposal", neutralize(old, profile) + suffix)]
    planned = schedule(cases, arms, repeats)
    report = {"schemaVersion": "neutral-contract-comparison-1", "scope": "FIXED_CANDIDATE_DEVELOPMENT_CALIBRATION",
              "previousCommit": previous, "profile": profile, "model": "gpt-6-luna", "plannedCalls": len(planned), "execute": execute,
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
            print(json.dumps({"case": case["name"], "arm": name, "repeat": repeat,
                "decisions": [v.get("assessment", {}).get("decision") for v in call.get("output", {}).get("verifications", [])],
                "error": call.get("error")}), flush=True)
        export_audits(report, output.parent)
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ("snapshot", "archive", "output"):
        parser.add_argument(arg)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--profile", choices=["combined", "contrast-only", "contrast-33"], default="combined")
    args = parser.parse_args()
    result = run(args.snapshot, args.archive, args.output, args.execute, args.repeats, args.profile)
    print(json.dumps({"plannedCalls": result["plannedCalls"], "executedCalls": len(result["calls"])}))
