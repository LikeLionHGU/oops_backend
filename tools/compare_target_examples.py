"""Compare unchanged revision-32 judgment with format-only target examples.

At most 22 explicit calls; no runtime edits, discovery replay or automatic repairs.
"""
import argparse
import json
import re
import subprocess
import textwrap
from pathlib import Path

from compare_neutral_contract import CURRENT, expression_case, schedule
from compare_review_prompts import ROOT, ENGINE, digest, prepare
from ablate_review_prompts import boundary_cases, export_audits
from build_review_guidelines import write_private
from collect_youtube_comments import read_key
from replay_draft_cases import complete, prompts

TARGET_EXAMPLES = """
# 대상 필드 형식 예시 — 판정 예시가 아니다
아래 raw는 설명용 가상 원문이다. 실제 요청의 raw에 없는 ID·문구·대상을 복사하지 않는다.
대상 평가를 이유로 REVIEW_REQUIRED를 이미 결정한 경우에만 다음 작성 관계를 참고한다.
가상 raw: [{"id":"example-1","text":"이 가게를 소개합니다."},{"id":"example-2","text":"여기는"}]
직접 원문 지칭을 target으로 쓰면:
{"target":"이 가게","targetMention":"이 가게","targetType":"BUSINESS","targetRelation":"EXPLICIT",
 "targetReason":"원문에서 이 가게를 직접 지칭한다.",
 "evidence":[{"segmentId":"example-1","quote":"이 가게","role":"TARGET"}]}
지칭어와 해석 대상의 문자열이 다르면 의미가 가까워도 CONTEXTUAL이다:
{"target":"앞서 소개한 가게","targetMention":"여기는","targetType":"BUSINESS","targetRelation":"CONTEXTUAL",
 "targetReason":"이 가게를 소개한 발언과 여기는이라는 후속 지칭이 같은 가게를 연결한다.",
 "evidence":[{"segmentId":"example-2","quote":"여기는","role":"TARGET"},
 {"segmentId":"example-1","quote":"이 가게를 소개합니다.","role":"CONTEXT"}]}
이는 대상 필드와 인용 역할만 보여주는 부분 객체다. 실제 응답에는 anchor의 PRIMARY와 다른 필수 필드도 필요하다.
대상 지칭어를 CONTEXT 역할로만 반환하면 TARGET 인용이 아니다. PRIMARY와 TARGET이 같은 줄이어도 두 역할을 각각 적는다.
PASS/UNCERTAIN은 대상 필드를 null로, 표현 자체 검토는 대상 근거가 없으면 대상 필드를 null로 유지한다.
가게 소개·여기는 같은 말 자체는 경고 근거가 아니다. 실제 연결이 없으면 예시처럼 관계를 만들어 채우지 않는다.
"""


def with_examples(system):
    if "# 후보 근거 검증" not in system or "# 대상 필드 형식 예시" in system:
        raise ValueError("PINNED_VERIFICATION_REQUIRED")
    return system + TARGET_EXAMPLES


def run(snapshot_path, archive_path, output, execute=False, repeats=3):
    output = Path(output)
    if output.exists() or any(output.parent.glob("ablation-*-report.json")):
        raise ValueError("NEW_OUTPUT_DIRECTORY_REQUIRED")
    snapshot = json.loads(Path(snapshot_path).read_text())
    cases, reference = prepare(snapshot, json.loads(Path(archive_path).read_text()), b_anchor=23500)
    cases = cases[:2] + boundary_cases(cases)[1:] + [expression_case(snapshot)]
    source = subprocess.check_output(["git", "show", CURRENT + ":" + ENGINE], cwd=ROOT, text=True)
    old = prompts(source)[2]
    library = (ROOT / "oops-backend/src/main/java/com/example/oops/analyzer/ReviewGuidelineLibrary.java").read_text()
    contract = textwrap.dedent(re.search(r'String CONTRACT = """\n(.*?)\n\s*""";', library, re.S)[1]) + "\n"
    suffix = "\n" + contract + "\ncontextReference=" + json.dumps(reference, ensure_ascii=False)
    arms = [("current-32", old + suffix), ("target-examples", with_examples(old) + suffix)]
    planned = schedule(cases, arms, repeats)
    report = {"schemaVersion": "target-example-comparison-1", "scope": "FORMAT_ONLY_FIXED_CANDIDATE_CALIBRATION",
              "previousCommit": CURRENT, "model": "gpt-6-luna", "plannedCalls": len(planned), "execute": execute,
              "referenceSha256": digest(reference), "sourceTranscriptSha256": digest(snapshot["transcript"]),
              "armPromptSha256": {name: digest(system) for name, system in arms}, "examples": TARGET_EXAMPLES,
              "cases": cases, "calls": []}
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
    for name in ("snapshot", "archive", "output"):
        parser.add_argument(name)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--repeats", type=int, default=3)
    args = parser.parse_args()
    result = run(args.snapshot, args.archive, args.output, args.execute, args.repeats)
    print(json.dumps({"plannedCalls": result["plannedCalls"], "executedCalls": len(result["calls"])}))
