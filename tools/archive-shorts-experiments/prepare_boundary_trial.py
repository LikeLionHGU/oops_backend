"""Prepare a pinned POLICY + common-guidelines development trial, without API calls.

Only the symmetric interpretation-evidence procedure differs between arms.
Not a replay of production candidate verification or an independent benchmark.
"""
import argparse
from decimal import Decimal
import re
import subprocess
import textwrap
from pathlib import Path

from fixed_review_suite import BASELINE_COMMIT, digest, read, require, write_private
from run_boundary_comparison import configuration_template, freeze, load_packet

ROOT = Path(__file__).resolve().parents[1]
ENGINE = "oops-backend/src/main/java/com/example/oops/analyzer/CandidateReviewEngine.java"
DELTA = """
# 문제 해석과 정상 해석에 같은 근거 기준 적용
대상과 행동 주체를 실제 지칭·연결 발언으로 먼저 확인한다. 화자가 본인의 선택을 설명하는지,
다른 사람·장소의 생활을 평가하는지 원문으로 구분하되 불명확한 주체를 채워 넣지 않는다.
문제 해석뿐 아니라 정상 해석도 무엇이 원문에 있고 무엇을 추가로 가정해야 하는지 대조한다.
개인의 자발적 선택·중립적인 안내·추억이라는 해석이 가능해도 원문에 없는 주체·의도를 보충해
그 해석을 사실처럼 확정하지 않는다. 반대로 정보·불만을 폄하로 만들기 위해 없는 평가를 추가하지 않는다.
원문에 근거한 별도 가치 절하나 표현 자체의 검토 이유가 있으면 정상 해석 가능성만으로 지우지 않는다.
직접 욕설이나 주민 전체 일반화는 필수 조건이 아니지만 실제 문제 연결의 근거는 필요하다.
비판에 필수인 연결이 없으면 PASS, 주체·전사·문맥 누락이 판단을 막으면 UNCERTAIN으로 구분한다.
추측이 아닌 실제 인용으로 쟁점을 설명한다. 절차는 내부적으로 수행하고 최종 판단 이유만 간결하게 반환한다.
"""


def prepare(packet, source, library, pricing):
    matches = re.findall(r'static final String POLICY = """\n(.*?)\n\s*""";', source, re.S)
    require(len(matches) == 1, "pinned POLICY extraction failed")
    policy = textwrap.dedent(matches[0]) + "\n"
    require(library.get("schemaVersion") == "review-guidelines-4", "reference schema mismatch")
    fields = ("id", "axis", "condition", "normalContrast", "requiredEvidence", "missingContext")
    guidelines = [{k: row[k] for k in fields} for row in library["guidelines"]]
    require(1 <= len(guidelines) <= 16, "reference size limit")
    config = configuration_template()
    config.update({"maxApiCalls": 42, "maxEstimatedCostUsd": 0.5, "pricing": pricing,
                   "baselinePrompt": policy, "candidatePrompt": policy + DELTA,
                   "referenceSnapshot": {"status": "WORKING_REFERENCE_NOT_VALIDATED",
                                         "scope": "SHARED_PATTERNS_NO_INCIDENT_EXAMPLES_OR_COMMENTS",
                                         "guidelines": guidelines}})
    artifact = freeze(packet, config)
    reserved = sum(Decimal(r["reservedCostUsd"]) for r in artifact["requests"])
    require(len(artifact["requests"]) == 42 and reserved <= Decimal("0.5"), "whole trial exceeds authorized budget")
    require(max(r["reservedInputTokensHeuristic"] for r in artifact["requests"]) < 272000,
            "long-context pricing tier requires separate calculation")
    provenance = {"schemaVersion": "boundary-trial-provenance-1", "baselineCommit": BASELINE_COMMIT,
                  "baselineSourcePath": ENGINE, "baselineSourceHash": digest(source),
                  "referenceLibraryHash": digest(library), "modelReferenceHash": digest(config["referenceSnapshot"]),
                  "baselinePolicyHash": digest(policy), "candidateDeltaHash": digest(DELTA),
                  "artifactHash": artifact["artifactHash"], "reservedTotalCostUsd": str(reserved),
                  "hypothesis": "Symmetric original-evidence requirements for normal and critical interpretations",
                  "scope": "POLICY_TEXT_WINDOW_WITH_SHARED_DERIVED_PATTERNS_NOT_RUNTIME_REPLAY",
                  "knownFamilyExposure": True, "independentGeneralizationTest": False,
                  "runtimeChanged": False, "apiCalls": 0, "qualityGateMet": None}
    return config, artifact, provenance


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--packet", required=True)
    parser.add_argument("--guidelines", required=True)
    parser.add_argument("--pricing", required=True)
    parser.add_argument("--output-dir", required=True)
    args = parser.parse_args()
    source = subprocess.check_output(["git", "show", f"{BASELINE_COMMIT}:{ENGINE}"], cwd=ROOT, text=True)
    config, artifact, provenance = prepare(load_packet(args.packet), source, read(args.guidelines), read(args.pricing))
    directory = Path(args.output_dir)
    require("datasets" in directory.resolve().parts, "private output required")
    directory.parent.mkdir(parents=True, exist_ok=True)
    directory.mkdir(mode=0o700)
    for name, value in (("config.json", config), ("frozen-execution.json", artifact), ("provenance.json", provenance)):
        write_private(directory / name, value)
    print({"plannedCalls": len(artifact["requests"]), "reservedCostUsd": provenance["reservedTotalCostUsd"],
           "executed": False})


if __name__ == "__main__":
    main()
