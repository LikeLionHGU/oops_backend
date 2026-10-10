"""Read-only local run capture and offline evaluation. Never runs AI or approves labels.

Known goals require the existing human assessment contract. Exact-quote duplicates
are mechanical hints, not semantic judgments. Missing quality/cost data stays null.
"""
import argparse
import datetime
import hashlib
import json
from urllib.request import urlopen

from build_review_guidelines import write_private
from review_dataset import assess_benchmark, read_json, require


def data(response):
    require(isinstance(response, dict) and response.get("success") is True, "API response unsuccessful")
    return response["data"]


def capture(video_id):
    require(str(video_id).isdigit() and int(video_id) > 0, "Invalid video ID")
    result = {"schemaVersion": "analysis-run-snapshot-1", "capturedAt": datetime.datetime.now(datetime.timezone.utc).isoformat()}
    for key, path in (("status", "/status"), ("report", "/report"),
                      ("diagnostics", "/analysis/diagnostics"), ("transcript", "/transcript")):
        with urlopen(f"http://127.0.0.1:8080/api/v1/videos/{video_id}{path}", timeout=10) as response:
            raw = response.read(4_194_305)
        require(len(raw) <= 4_194_304, "API response too large")
        result[key] = data(json.loads(raw))
    require(result["status"]["status"] == "COMPLETED", "Run not completed")
    require(str(result["report"]["videoId"]) == str(video_id)
            and str(result["status"]["videoId"]) == str(video_id), "Run identity mismatch")
    require(result["status"].get("jobId") == result["report"].get("jobId"), "Run job changed during capture")
    snapshot = result["diagnostics"].get("snapshot")
    require(snapshot is None or str(snapshot["videoId"]) == str(video_id), "Diagnostic identity mismatch")
    return result


def evaluate(benchmark, run, assessment=None):
    require(run.get("schemaVersion") == "analysis-run-snapshot-1", "Invalid run schema")
    report, status = run["report"], run["status"]
    require(report.get("status") == "COMPLETED" and status.get("status") == "COMPLETED", "Run not completed")
    require(str(report["videoId"]) == str(status["videoId"]), "Run identity mismatch")
    events = report["events"]
    ids = [str(e["id"]) for e in events]
    require(len(ids) == len(set(ids)), "Duplicate event IDs")
    groups = {}
    for e in events:
        key = (e["startMs"], e["endMs"], e.get("candidateType"), e.get("type"), e.get("text"))
        groups.setdefault(key, []).append(str(e["id"]))
    mechanical = [group for group in groups.values() if len(group) > 1]
    goal_result = assess_benchmark(benchmark, report, assessment) if assessment is not None else None
    quality = assessment.get("qualityReview") if assessment else None
    duplicate_count = unsupported_count = None
    if quality is not None:
        require(quality.get("allEventsReviewed") is True, "Quality review must cover all events")
        duplicates = quality.get("duplicateGroups")
        unsupported = quality.get("unsupportedInterpretationEventIds")
        require(isinstance(duplicates, list) and isinstance(unsupported, list), "Quality labels required")
        used = set()
        for group in duplicates:
            require(isinstance(group, list) and len(group) >= 2 and len(group) == len(set(group))
                    and all(e in ids and e not in used for e in group), "Invalid semantic duplicate group")
            used.update(group)
        require(len(unsupported) == len(set(unsupported)) and all(e in ids for e in unsupported), "Invalid interpretation labels")
        duplicate_count = sum(len(group) - 1 for group in duplicates)
        unsupported_count = len(unsupported)
    elapsed = None
    if status.get("startedAt") and status.get("completedAt"):
        elapsed = (datetime.datetime.fromisoformat(status["completedAt"].replace("Z", "+00:00"))
                   - datetime.datetime.fromisoformat(status["startedAt"].replace("Z", "+00:00"))).total_seconds()
        require(elapsed >= 0, "Invalid elapsed time")
    snapshot = run["diagnostics"].get("snapshot")
    require(snapshot is None or str(snapshot["videoId"]) == str(report["videoId"]), "Diagnostic identity mismatch")
    speech = next((a for a in (snapshot or {}).get("analyzers", []) if a["evaluatorId"] == "speech-review"), None)
    return {"schemaVersion": "analysis-run-evaluation-1", "videoId": str(report["videoId"]),
            "benchmarkVersion": benchmark["version"], "familyId": benchmark["familyId"],
            "reviewStatus": "HUMAN_DECLARED_NOT_INDEPENDENTLY_VERIFIED" if assessment else "PENDING_HUMAN_REVIEW",
            "goalEvaluation": goal_result, "cardCount": len(events), "elapsedSeconds": elapsed,
            "mechanicalDuplicateGroups": mechanical, "semanticDuplicateExtraCards": duplicate_count,
            "unsupportedInterpretationCards": unsupported_count,
            "qualityGateMet": None if goal_result is None or quality is None else
                goal_result["developmentGoalMet"] and duplicate_count == 0 and unsupported_count == 0,
            "speechStatus": speech.get("status") if speech else None,
            "promptRevision": (snapshot or {}).get("textReviewPromptRevision"),
            "configuredModel": (snapshot or {}).get("configuredModel"),
            "transcriptSha256": hashlib.sha256(json.dumps(run["transcript"], ensure_ascii=False, sort_keys=True).encode()).hexdigest()
                if isinstance(run.get("transcript"), list) else None,
            "diagnosticsAvailable": snapshot is not None, "costUsd": None,
            "costNote": "Not present in report API; inspect measured usage log separately.",
            "generalAccuracyMeasured": False}


def compare(evaluations):
    require(len(evaluations) >= 2, "Need at least two runs")
    require(all(e.get("schemaVersion") == "analysis-run-evaluation-1" for e in evaluations), "Invalid evaluation schema")
    require(len({(e["familyId"], e["benchmarkVersion"]) for e in evaluations}) == 1, "Different evaluation protocols")
    require(len({e["videoId"] for e in evaluations}) == len(evaluations), "Repeated run IDs")
    reviewed = [e for e in evaluations if e.get("goalEvaluation") is not None]
    return {"runCount": len(evaluations), "reviewedRunCount": len(reviewed),
            "cardCounts": [e["cardCount"] for e in evaluations],
            "sameTranscript": None if any(e.get("transcriptSha256") is None for e in evaluations) else
                len({e["transcriptSha256"] for e in evaluations}) == 1,
            "sameModelAndRevision": None if any(not e.get("configuredModel") or not e.get("promptRevision") for e in evaluations) else
                len({(e["configuredModel"], e["promptRevision"]) for e in evaluations}) == 1,
            "goalOutcomeConsistent": None if len(reviewed) != len(evaluations) else
                all(e["goalEvaluation"]["outcomes"] == reviewed[0]["goalEvaluation"]["outcomes"] for e in reviewed),
            "interpretation": "Descriptive development comparison; input/model/version differences are not controlled.",
            "generalAccuracyMeasured": False}


def assessment_template(benchmark, run):
    return {"videoId": str(run["report"]["videoId"]), "benchmarkVersion": benchmark["version"],
            "reviewerId": "replace-with-human-reviewer", "normalControlsReviewed": False,
            "falsePositiveEventIds": [],
            "matches": [{"goalId": goal["id"], "eventId": None, "status": "UNASSESSED",
                         "reason": "Pending original evidence and normal-contrast review.",
                         "reportQuote": None, "targetCorrect": False, "interpretationCorrect": False,
                         "normalContrastChecked": False, "originalEvidenceChecked": False, "sceneChecked": False}
                        for goal in benchmark["goals"]],
            "qualityReview": {"allEventsReviewed": False, "duplicateGroups": [], "unsupportedInterpretationEventIds": []}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    grab = commands.add_parser("capture")
    grab.add_argument("video_id")
    grab.add_argument("--output", required=True)
    review = commands.add_parser("evaluate")
    review.add_argument("benchmark")
    review.add_argument("run")
    review.add_argument("--assessment")
    review.add_argument("--output")
    template = commands.add_parser("template")
    template.add_argument("benchmark")
    template.add_argument("run")
    template.add_argument("--output", required=True)
    repeated = commands.add_parser("compare")
    repeated.add_argument("evaluations", nargs="+")
    args = parser.parse_args()
    try:
        if args.command == "capture":
            result = capture(args.video_id)
            write_private(args.output, result)
            print(json.dumps({"saved": True, "videoId": args.video_id}))
            return
        if args.command == "template":
            result = assessment_template(read_json(args.benchmark), read_json(args.run))
            write_private(args.output, result)
            print(json.dumps({"saved": True, "status": "PENDING_HUMAN_REVIEW"}))
            return
        if args.command == "evaluate":
            result = evaluate(read_json(args.benchmark), read_json(args.run), read_json(args.assessment) if args.assessment else None)
            if args.output:
                write_private(args.output, result)
        else:
            result = compare([read_json(p) for p in args.evaluations])
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (ValueError, KeyError, TypeError, OSError):
        parser.exit(1, "Run evaluation failed: invalid/missing input or unavailable local server.\n")


if __name__ == "__main__":
    main()
