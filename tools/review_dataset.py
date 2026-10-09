"""Offline curation/benchmark tooling. No network, AI, scraping or automatic labels.

Outputs JSON to stdout; never modifies production resources or source records.
All approval/rights fields are human declarations, not independently verified facts.
"""
import argparse
import datetime
import hashlib
import json
import re
import unicodedata
from pathlib import Path


DECISIONS = {"PASS", "REVIEW_REQUIRED", "UNCERTAIN"}
HELDOUT = {"DEVELOPMENT", "VALIDATION", "TEST"}
ID = re.compile(r"[A-Za-z0-9_-]{1,64}\Z")


class ContractError(ValueError):
    """Only constant, source-free diagnostic messages."""


def require(condition, message):
    if not condition:
        raise ContractError(message)


def nonblank(value, maximum=6000):
    return isinstance(value, str) and bool(value.strip()) and len(value) <= maximum


def normalized(value):
    return "".join(c for c in unicodedata.normalize("NFKC", value).lower() if c.isalnum())


def fingerprint(lines):
    return hashlib.sha256(normalized(" ".join(lines)).encode("utf-8")).hexdigest()


def read_json(path):
    with Path(path).open("rb") as stream:
        raw = stream.read(4_194_305)
    require(len(raw) <= 4_194_304, "Input exceeds 4 MiB")
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "Duplicate JSON key")
            result[key] = value
        return result
    return json.loads(raw, object_pairs_hook=unique)


def indexed(rows, name):
    require(isinstance(rows, list) and len(rows) <= 10000, f"Invalid {name} collection")
    result = {}
    for row in rows:
        require(isinstance(row, dict), f"Invalid {name} record")
        key = row.get("id")
        require(isinstance(key, str) and ID.fullmatch(key), f"Invalid {name} ID")
        require(key not in result, f"Duplicate {name} ID")
        result[key] = row
    return result


def validate(dataset):
    require(isinstance(dataset, dict) and dataset.get("schemaVersion") == "1", "Unsupported schema")
    require(nonblank(dataset.get("version"), 64), "Invalid version")
    sources, cases, reactions, annotations, adjudications = (
        indexed(dataset.get(name), name)
        for name in ("sources", "cases", "reactions", "annotations", "adjudications")
    )
    family_splits = {}
    for source in sources.values():
        require(ID.fullmatch(source.get("familyId", "")), "Invalid familyId")
        require(source.get("split") in HELDOUT | {"TRAIN"}, "Invalid source split")
        previous = family_splits.setdefault(source["familyId"], source["split"])
        require(previous == source["split"], "Family leakage across splits")
        require(source.get("kind") in {"DIRECT_FEEDBACK", "LICENSED_DATA", "SYNTHETIC"}, "Invalid source kind")
        require(nonblank(source.get("provenance")), "Missing provenance")
        require(nonblank(source.get("rightsBasis"), 300), "Missing rights basis (pending allowed)")
        scopes = source.get("allowedUses")
        require(isinstance(scopes, list) and set(scopes) <= {"LOCAL_REVIEW", "RETRIEVAL", "TRAINING"}, "Invalid allowed uses")
        require(type(source.get("privacyReviewed")) is bool, "Missing privacy review")
        lines = source.get("fullRawSpeech")
        require(isinstance(lines, list) and all(nonblank(s) for s in lines), "Invalid full raw speech")
    contrast_splits = {}
    for case in cases.values():
        require(case.get("sourceId") in sources, "Unknown case source")
        require(case.get("status") in {"DRAFT", "READY", "REJECTED"}, "Invalid case status")
        require(case.get("contrastGroupId") is None or ID.fullmatch(case.get("contrastGroupId", "")), "Invalid contrast group")
        if case.get("contrastGroupId"):
            split = sources[case["sourceId"]]["split"]
            require(contrast_splits.setdefault(case["contrastGroupId"], split) == split, "Contrast pair leakage across splits")
        require(nonblank(case.get("contextSummary"), 800), "Missing context")
        segments = indexed(case.get("segments"), "segment")
        require(bool(segments), "Case needs raw segments")
        for segment in segments.values():
            require(nonblank(segment.get("rawText")), "Missing raw transcript")
            require(type(segment.get("startMs")) is int and type(segment.get("endMs")) is int
                    and 0 <= segment["startMs"] < segment["endMs"], "Invalid segment timing")
            verified = segment.get("verifiedText")
            require(verified is None or (nonblank(verified) and nonblank(segment.get("verifiedBy"), 64)), "Verified transcript needs reviewer")
        require(case.get("anchorSegmentId") in segments, "Unknown anchor")
        frames = indexed(case.get("frames"), "frame")
        for frame in frames.values():
            require(type(frame.get("timeMs")) is int and frame["timeMs"] >= 0
                    and nonblank(frame.get("observation")), "Invalid scene observation")
        require(isinstance(case.get("retrievalTerms"), list), "Missing retrieval terms")
    for reaction in reactions.values():
        require(reaction.get("caseId") in cases and reaction.get("sourceId") in sources, "Unknown reaction source/case")
        require(reaction.get("stance") in {"CRITICISM", "COUNTER", "IRRELEVANT"}, "Invalid reaction stance")
        require(nonblank(reaction.get("redactedText")) and nonblank(reaction.get("claim")), "Missing reaction text/claim")
        require(reaction.get("contextRelation") in {"DIRECT", "NEWS_REACTION", "UNKNOWN"}, "Invalid reaction relation")
    reviewer_stages = set()
    for annotation in annotations.values():
        require(annotation.get("caseId") in cases, "Unknown annotation case")
        require(ID.fullmatch(annotation.get("reviewerId", "")), "Invalid reviewer ID")
        require(annotation.get("stage") in {"BLIND", "REACTION_INFORMED"}, "Invalid annotation stage")
        key = (annotation["caseId"], annotation["reviewerId"], annotation["stage"])
        require(key not in reviewer_stages, "Duplicate reviewer stage")
        reviewer_stages.add(key)
        reaction_ids = annotation.get("reactionIds")
        require(isinstance(reaction_ids, list) and all(r in reactions and reactions[r]["caseId"] == annotation["caseId"] for r in reaction_ids), "Invalid linked reactions")
        require(annotation["stage"] != "BLIND" or not reaction_ids, "Blind annotation cannot cite reactions")
        judgment(annotation, cases[annotation["caseId"]])
    adjudicated = set()
    for adjudication in adjudications.values():
        case_id = adjudication.get("caseId")
        require(case_id in cases and case_id not in adjudicated, "Unknown/duplicate adjudication case")
        adjudicated.add(case_id)
        require(ID.fullmatch(adjudication.get("adjudicatorId", "")), "Missing adjudicator")
        refs = adjudication.get("annotationIds")
        require(isinstance(refs, list) and len(refs) == len(set(refs)) and len(refs) >= 2, "Need independent annotations")
        require(all(a in annotations and annotations[a]["caseId"] == case_id for a in refs), "Wrong annotation links")
        reviewers = {annotations[a]["reviewerId"] for a in refs}
        require(len(reviewers) >= 2, "Need two actual independent reviewers")
        require(all(any(annotations[a]["reviewerId"] == r and annotations[a]["stage"] == "BLIND" for a in refs) for r in reviewers), "Each reviewer needs blind annotation")
        if len({annotations[a]["decision"] for a in refs}) > 1:
            require(adjudication["adjudicatorId"] not in reviewers, "Disagreement needs third adjudicator")
        require(nonblank(adjudication.get("resolutionReason")), "Missing resolution reason")
        try:
            reviewed_at = datetime.date.fromisoformat(adjudication.get("reviewedAt", ""))
        except (ValueError, TypeError):
            raise ValueError("Invalid review date") from None
        require(reviewed_at <= datetime.date.today(), "Future approval")
        judgment(adjudication, cases[case_id])
    require(all(c["status"] != "READY" or c["id"] in adjudicated for c in cases.values()), "READY case needs adjudication")
    return sources, cases, reactions, annotations, adjudications


def judgment(row, case):
    require(row.get("decision") in DECISIONS, "Invalid decision")
    require(nonblank(row.get("reason")) and nonblank(row.get("normalInterpretation"), 400), "Need reason and normal contrast")
    missing = row.get("missingInformation")
    require(isinstance(missing, list) and len(missing) <= 6 and all(nonblank(s, 200) for s in missing), "Invalid missing information")
    require((row["decision"] == "UNCERTAIN") == bool(missing), "Missing information only for UNCERTAIN")
    require(row.get("axis") in {"TARGET_TREATMENT", "EXPRESSION_CONTENT", "NONE"}, "Invalid axis")
    require(row["decision"] != "REVIEW_REQUIRED" or row["axis"] != "NONE", "Review needs axis")
    target = row.get("target")
    require(target is None or (isinstance(target, dict) and nonblank(target.get("referent"), 200)
            and target.get("mentionMode") in {"EXPLICIT", "CONTEXTUAL", "OMITTED"}), "Invalid target")
    require(row["axis"] != "TARGET_TREATMENT" or target is not None, "Target axis needs referent")
    segments = {s["id"]: s for s in case["segments"]}
    evidence = row.get("evidence")
    require(isinstance(evidence, list) and bool(evidence), "Need exact raw evidence")
    for item in evidence:
        require(isinstance(item, dict) and item.get("segmentId") in segments, "Unknown evidence segment")
        require(nonblank(item.get("quote"), 500) and item["quote"] in segments[item["segmentId"]]["rawText"], "Evidence must match raw transcript")
    if target is not None:
        require(nonblank(target.get("contextReason")), "Target needs context reason")
        if target["mentionMode"] == "EXPLICIT":
            require(nonblank(target.get("rawMention"), 200) and any(target["rawMention"] in e["quote"] for e in evidence), "Explicit mention needs raw evidence")
        else:
            require(target.get("rawMention") is None, "Omitted/contextual target must not invent raw mention")


def export_retrieval(dataset, excluded_families):
    sources, cases, reactions, annotations, adjudications = validate(dataset)
    blocked = set(excluded_families) | {s["familyId"] for s in sources.values() if s["split"] != "TRAIN"}
    exported = []
    for adjudication in adjudications.values():
        case = cases[adjudication["caseId"]]
        source = sources[case["sourceId"]]
        if case["status"] != "READY" or source["familyId"] in blocked or source["kind"] == "SYNTHETIC":
            continue
        linked = [r for r in reactions.values() if r["caseId"] == case["id"]]
        relevant = [r for r in linked if r["stance"] != "IRRELEVANT"]
        require(all("RETRIEVAL" in sources[r["sourceId"]]["allowedUses"] and sources[r["sourceId"]]["privacyReviewed"] for r in relevant), "Reaction retrieval rights/privacy not cleared")
        require("RETRIEVAL" in source["allowedUses"] and source["privacyReviewed"], "Case retrieval rights/privacy not cleared")
        require(bool(source["fullRawSpeech"]), "Export needs full raw speech fingerprint")
        require(all(s["rawText"] in source["fullRawSpeech"] for s in case["segments"]), "Case raw segments absent from full speech")
        terms = case["retrievalTerms"]
        require(2 <= len(terms) <= 12 and all(nonblank(t, 40) and len(normalized(t)) >= 2 for t in terms)
                and len({normalized(t) for t in terms}) == len(terms), "Invalid export retrieval terms")
        anchor = next(s for s in case["segments"] if s["id"] == case["anchorSegmentId"])
        require(nonblank(anchor["rawText"], 500), "Export utterance too long")
        evidence = adjudication["evidence"][0]["quote"]
        require(evidence in anchor["rawText"] or evidence in case["contextSummary"], "Evidence not in export utterance/context; curate summary")
        critics = [r["claim"] for r in relevant if r["stance"] == "CRITICISM"]
        critic = " / ".join(critics) if critics else adjudication["reason"]
        require(nonblank(critic, 400), "Export critic claim exceeds budget; curate summary")
        exported.append({
            "caseId": case["id"], "familyId": source["familyId"], "sourceFingerprint": fingerprint(source["fullRawSpeech"]),
            "sourceKind": source["kind"], "split": "TRAIN", "reviewStatus": "APPROVED", "rightsCleared": True,
            "rightsBasis": source["rightsBasis"], "reviewerIds": sorted({annotations[a]["reviewerId"] for a in adjudication["annotationIds"]}),
            "reviewedAt": adjudication["reviewedAt"], "retrievalTerms": terms, "utterance": anchor["rawText"],
            "context": case["contextSummary"], "criticClaim": critic, "counterInterpretation": adjudication["normalInterpretation"],
            "supportedEvidence": evidence, "decision": adjudication["decision"], "missingInformation": adjudication["missingInformation"]
        })
    require(len(exported) <= 1000 and all(len(c["reviewerIds"]) <= 6 for c in exported), "Archive exceeds library limits")
    archive = {"version": dataset["version"], "cases": exported}
    require(len((json.dumps(archive, ensure_ascii=False, indent=2) + "\n").encode("utf-8")) <= 1_048_576, "Export exceeds library byte limit")
    return archive


def assess_benchmark(benchmark, report, assessment):
    data = report.get("data", report)
    require(str(data.get("videoId")) == assessment.get("videoId"), "Report video ID mismatch")
    require(benchmark.get("version") == assessment.get("benchmarkVersion"), "Benchmark version mismatch")
    require(ID.fullmatch(assessment.get("reviewerId", "")), "Missing reviewer")
    require(data.get("status") == "COMPLETED", "Report not completed")
    goals = {g["id"]: g for g in benchmark["goals"]}
    require(all(type(g.get("required", True)) is bool for g in goals.values()), "Invalid required goal flag")
    required_goals = {key for key, goal in goals.items() if goal.get("required", True)}
    require(bool(required_goals), "Benchmark needs required goals")
    events = {str(e["id"]): e for e in data["events"]}
    matches = assessment.get("matches")
    require(isinstance(matches, list), "Invalid matches")
    used_goals, used_events, outcomes = set(), set(), {}
    for match in matches:
        goal_id, event_id = match.get("goalId"), match.get("eventId")
        require(goal_id in goals and goal_id not in used_goals, "Unknown/duplicate goal")
        used_goals.add(goal_id)
        require(match.get("status") in {"MATCH", "MISSING", "UNASSESSED"}, "Invalid match status")
        if match["status"] != "MATCH":
            require(event_id is None and nonblank(match.get("reason")), "Nonmatch needs reason, no event")
            outcomes[goal_id] = match["status"]
            continue
        require(event_id in events and event_id not in used_events, "Unknown/reused card")
        used_events.add(event_id)
        event, goal = events[event_id], goals[goal_id]
        require(type(event.get("startMs")) is int and type(event.get("endMs")) is int
                and 0 <= event["startMs"] < event["endMs"], "Invalid event timing")
        require(event["startMs"] < goal["windowMs"][1] and event["endMs"] > goal["windowMs"][0], "Card outside goal window")
        require(all(match.get(k) is True for k in ("targetCorrect", "interpretationCorrect", "normalContrastChecked", "originalEvidenceChecked")), "Match needs human evidence/target/contrast checks")
        require(nonblank(match.get("reason")), "Missing match rationale")
        quote = match.get("reportQuote")
        require(nonblank(quote) and quote in event.get("text", ""), "Quote must match report card text")
        require(not goal["needsScene"] or match.get("sceneChecked") is True, "Scene-dependent goal needs original scene review")
        outcomes[goal_id] = "MATCH"
    false_positives = assessment.get("falsePositiveEventIds")
    require(isinstance(false_positives, list) and len(false_positives) == len(set(false_positives))
            and all(e in events and e not in used_events for e in false_positives), "Invalid false-positive cards")
    require(type(assessment.get("normalControlsReviewed")) is bool, "Missing normal control check")
    for goal_id in goals:
        outcomes.setdefault(goal_id, "UNASSESSED")
    hits = sum(outcomes[key] == "MATCH" for key in required_goals)
    return {"videoId": assessment["videoId"], "benchmarkVersion": benchmark["version"],
            "goalStatus": benchmark["status"], "matches": hits, "goals": len(required_goals), "outcomes": outcomes,
            "auxiliaryOutcomes": {key: outcomes[key] for key in goals if key not in required_goals},
            "falsePositiveCards": len(false_positives), "normalControlsReviewed": assessment["normalControlsReviewed"],
            "developmentGoalMet": hits == len(required_goals) and assessment["normalControlsReviewed"] and not false_positives,
            "generalAccuracyMeasured": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("validate", "export-retrieval"):
        command = commands.add_parser(name)
        command.add_argument("dataset")
        if name == "export-retrieval":
            command.add_argument("--exclude-family", action="append", default=["pisik-yeongyang"])
    command = commands.add_parser("benchmark")
    command.add_argument("benchmark")
    command.add_argument("report")
    command.add_argument("assessment")
    args = parser.parse_args()
    try:
        if args.command == "validate":
            collections = validate(read_json(args.dataset))
            result = {"valid": True, "counts": dict(zip(("sources", "cases", "reactions", "annotations", "adjudications"), map(len, collections)))}
        elif args.command == "export-retrieval":
            result = export_retrieval(read_json(args.dataset), args.exclude_family)
        else:
            result = assess_benchmark(read_json(args.benchmark), read_json(args.report), read_json(args.assessment))
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (ValueError, TypeError, KeyError, OSError, AttributeError) as error:
        # Avoid source texts, local paths and full input dumps in diagnostic output.
        detail = str(error) if isinstance(error, ContractError) else type(error).__name__
        parser.exit(2, f"Validation failed: {detail}\n")


if __name__ == "__main__":
    main()
