"""Re-transcribe the two reviewed local originals with the production STT adapter.

Outputs are private experiment artifacts, never application DB updates or gold labels.
Run from the repository root: python tools/groq_stt_audit.py NEW_PRIVATE_DIRECTORY
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "oops-analysis"))

from app import stt
from app.config import Settings
from app.media import PreparedVideo, probe_duration


def run(output: Path) -> None:
    output = output.resolve()
    private_root = (ROOT / "datasets/controversy/experiments").resolve()
    if output.parent != private_root or output.exists():
        raise ValueError("A new direct private experiment directory is required")
    settings = Settings(_env_file=ROOT / "oops-analysis/.env")
    if (settings.stt_model or settings.whisper_model) != "whisper-large-v3" or not settings.groq_api_key:
        raise ValueError("Configured Groq whisper-large-v3 and its key are required")
    originals = [(name, ROOT / f"oops-backend/videos/{name}.mp4") for name in ("v4", "v6")]
    durations = [probe_duration(path) for _, path in originals]
    if sum(durations) > 1600:
        raise ValueError("Reviewed v4/v6 duration scope exceeded")
    output.mkdir()
    manifest = {"schemaVersion": "groq-stt-audit-1", "state": "RUNNING",
                "scope": "V4_V6_ORIGINAL_AUDIO_PRODUCTION_STT_ONLY_NO_DB_WRITES",
                "model": "whisper-large-v3", "chunkSec": stt.CHUNK_SEC,
                "promptAndLanguageHintsAdded": False,
                "estimatedUsd": sum(durations) / 3600 * .111,
                "actualBillingConfirmed": False, "sources": []}
    manifest_path = output / "stt-manifest.json"
    stt.get_settings = lambda: settings
    try:
        for (name, path), duration in zip(originals, durations):
            work = Path(tempfile.mkdtemp(prefix=f"{name}-audio-"))
            video = PreparedVideo(path, work, duration, path.name)
            started = time.monotonic()
            try:
                result = stt.transcribe(video)
            finally:
                video.cleanup()
            payload = json.dumps(result, ensure_ascii=False, indent=2).encode()
            (output / f"{name}-groq.json").write_bytes(payload)
            source = {"caseId": name, "durationSec": duration,
                      "segments": len(result["segments"]),
                      "elapsedSec": round(time.monotonic() - started, 3),
                      "transcriptSha256": hashlib.sha256(payload).hexdigest()}
            manifest["sources"].append(source)
            manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
            print(json.dumps(source), flush=True)
        manifest["state"] = "COMPLETE"
    except Exception as exc:
        manifest["state"] = "STOPPED"
        manifest["failureType"] = type(exc).__name__
        raise
    finally:
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2))


def prepare(output: Path) -> None:
    output = output.resolve()
    if output.parent != (ROOT / "datasets/controversy/experiments").resolve():
        raise ValueError("Private experiment required")
    old_path = ROOT / "datasets/controversy/experiments/v7-v4-v6-routing-plan-20261016/plan.json"
    manifest = json.loads((output / "stt-manifest.json").read_text())
    if manifest["state"] != "COMPLETE" or manifest["model"] != "whisper-large-v3":
        raise ValueError("Complete target-model transcription required")
    old = json.loads(old_path.read_text())
    originals = {s["caseId"]: s["raw"] for s in old["sources"]}
    fresh = {}
    for name in ("v4", "v6"):
        source_bytes = (output / f"{name}-groq.json").read_bytes()
        expected = next(s for s in manifest["sources"] if s["caseId"] == name)
        if hashlib.sha256(source_bytes).hexdigest() != expected["transcriptSha256"]:
            raise ValueError("Stored transcription hash changed")
        result = json.loads(source_bytes)
        fresh[name] = [{**s, "id": f"groq-{name}-{i}", "type": "SPEECH", "confidence": None, "role": None}
                       for i, s in enumerate(result["segments"])]
    windows = [("v4", "hygiene", 290000, 370000), ("v4", "intimate-question", 435000, 495000),
               ("v6", "reported-abuse", 370000, 425000), ("v6", "death-framing", 710000, 750000),
               ("v6", "group-generalization", 770000, 820000)]
    cases = []
    for name, issue, start, end in windows:
        for provider, collection in (("openai", originals), ("groq", fresh)):
            raw = [s for s in collection[name] if s["startMs"] < end and s["endMs"] > start]
            cases.append({"id": f"{name}-{issue}-{provider}", "labelStatus": "INDEPENDENT_REVIEW_PROVISIONAL_NOT_GOLD",
                          "source": provider, "requestedWindowMs": [start, end], "raw": raw})
    contrasts = [
        ("hygiene-self-experience", ["집에서 쉬는 날은 씻는 시간을 제가 정해요.",
            "비데는 초등학교 때 친구 집에서 한 번 써봤고, 평소에는 휴지를 써요."],
            ["leemiju-mijupichu#s1-sexual", "kimwonhun-nego#s1-sexual"]),
        ("voluntary-personal-experience", ["제가 제 생활 습관을 이야기해 볼게요.",
            "저는 집에서 혼자 쉴 때 속옷을 입지 않을 때가 있어요.", "다른 분의 사생활을 물으려는 건 아니에요."],
            ["leemiju-mijupichu#s1-sexual", "kimwonhun-nego#s1-sexual"]),
        ("critical-quotation", ["보도에는 상사가 직원에게 잠옷 사진을 보내 달라고 요청했다는 내용이 있습니다.",
            "그런 사적 요구는 권력 차이 속에서 거절하기 어려웠다는 피해자의 호소를 설명하는 대목입니다."],
            ["gamst-yeobj#s1-sexual", "kwakjeongeun-chimdae#s1-sexual"]),
        ("specific-actor-criticism", ["당시 행사에서 발언한 민주당 관계자 세 명의 대응을 비판합니다.",
            "그 세 사람이 피해자의 설명을 확인하지 않고 공격한 행동은 무책임했다고 생각합니다."],
            ["jangdongmin-ongdalsaem#s1-group", "gonuri-dunpa#s1-group"]),
        ("death-accountability", ["당사자가 사망하면서 재판을 통해 본인의 책임을 확인할 수 없게 됐습니다.",
            "남은 관계자의 책임과 피해 회복 절차는 계속 확인해야 합니다."],
            ["singbungbung-gunin#s1-dismissal", "kwak-naeun#s1-dismissal"]),
        ("victim-sympathy", ["당시 그는 경험이 많지 않은 신입 직원이었습니다.",
            "권력 차이 때문에 대응하기 어려웠던 상황을 설명하는 것이며 피해자에게 책임을 묻는 말은 아닙니다."],
            ["singbungbung-gunin#s1-dismissal"]),
    ]
    proposals = []
    for ident, lines, refs in contrasts:
        raw = [{"id": f"normal-{ident}-{i}", "type": "SPEECH", "startMs": i * 4000,
                "endMs": (i + 1) * 4000, "text": line, "confidence": None, "role": None}
               for i, line in enumerate(lines)]
        cases.append({"id": f"normal-{ident}", "source": "SYNTHETIC",
                      "labelStatus": "DRAFT_EXPECT_PASS_NOT_HUMAN_GOLD", "raw": raw})
        proposals.append({"id": ident, "relatedExampleIds": refs, "speech": lines,
                          "status": "SYNTHETIC_DRAFT_NOT_PROMOTED", "expectedDecision": "PASS"})
    payload = {"schemaVersion": "groq-pipeline-audit-input-1", "scope": "SELECTED_TEXT_EXCERPTS_NO_MEDIA_OR_DB_WRITES",
               "estimatedTextReservationCapUsd": .50, "maxHttpAttempts": 80,
               "cases": cases, "normalContrastProposals": proposals}
    with (output / "pipeline-input.json").open("x") as stream:
        json.dump(payload, stream, ensure_ascii=False, indent=2)
    print(json.dumps({"preparedCases": len(cases), "syntheticDrafts": len(contrasts)}))


def summarize(output: Path) -> None:
    saved = json.loads((output / "decisions.json").read_text())
    inputs = json.loads((output / "pipeline-input.json").read_text())
    manifest = json.loads((output / "stt-manifest.json").read_text())
    rows = []
    for row in saved["results"]:
        pipeline = row["diagnostics"].get("candidatePipeline", {})
        missing_truncated = any(call["kind"] == "Discovery" and call["output"] is not None
                                and call["output"].get("truncated") is None for call in row["calls"])
        rows.append({"id": row["id"], "status": row["status"], "findings": len(row["findings"]),
                     "unassessedSegments": len(row["unassessedSegmentIds"]),
                     "invalidProposals": pipeline.get("invalidProposals", 0),
                     "missingDiscoveryTruncated": missing_truncated,
                     "candidates": [{k: c.get(k) for k in ("anchorId", "axis", "state", "failureCode")}
                                    for c in pipeline.get("candidates", [])]})
    old = json.loads((ROOT / "datasets/controversy/experiments/v7-v4-v6-routing-plan-20261016/plan.json").read_text())
    speech_stats = []
    for source in old["sources"]:
        name = source["caseId"]
        fresh = json.loads((output / f"{name}-groq.json").read_text())["segments"]
        speech_stats.append({"id": name, "oldSegments": len(source["raw"]), "newSegments": len(fresh),
                             "oldExactExclamationOnly": sum(s["text"].strip() in ("와.", "아.") for s in source["raw"]),
                             "newExactExclamationOnly": sum(s["text"].strip() in ("와.", "아.") for s in fresh),
                             "invalidNewTimes": sum(s["startMs"] < 0 or s["endMs"] <= s["startMs"] for s in fresh)})
    summary = {"scope": saved["scope"], "state": saved["state"], "runtimeSha256": saved["runtimeSha256"],
               "stt": speech_stats, "conditions": rows,
               "normalDrafts": {"total": 6, "completeNoFinding": sum(r["id"].startswith("normal-")
                   and r["status"] == "SUCCESS" and r["findings"] == 0 for r in rows),
                   "humanGold": False, "promotedToRuntime": False},
               "cost": {"groqDurationEstimatedUsd": manifest["estimatedUsd"],
                        "openaiUsageEstimatedUsd": saved["usageEstimatedUsd"],
                        "openaiReservedEstimatedUsd": saved["reservedEstimatedUsd"],
                        "textHttpAttempts": saved["httpAttempts"], "actualBillingConfirmed": False},
               "sourceHashes": {name: hashlib.sha256((output / name).read_bytes()).hexdigest()
                                for name in ("pipeline-input.json", "retrieval.json", "decisions.json")},
               "implementationHashes": {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in (
                   "oops-analysis/app/stt.py", "oops-backend/src/main/java/com/example/oops/analyzer/CandidateReviewEngine.java",
                   "oops-backend/src/main/java/com/example/oops/analyzer/ReviewGuidelineLibrary.java",
                   "oops-backend/src/test/java/com/example/oops/analyzer/GroqPipelineAudit.java")},
               "normalContrastProposals": inputs["normalContrastProposals"]}
    with (output / "summary.json").open("x") as stream:
        json.dump(summary, stream, ensure_ascii=False, indent=2)
    print(json.dumps({"state": summary["state"], "conditions": len(rows), "cost": summary["cost"],
                      "normalDrafts": summary["normalDrafts"]}))


if __name__ == "__main__":
    if len(sys.argv) == 2:
        run(Path(sys.argv[1]))
    elif len(sys.argv) == 3 and sys.argv[1] == "prepare":
        prepare(Path(sys.argv[2]))
    elif len(sys.argv) == 3 and sys.argv[1] == "summarize":
        summarize(Path(sys.argv[2]))
    else:
        raise SystemExit("Usage: groq_stt_audit.py [prepare|summarize] PRIVATE_DIRECTORY")
