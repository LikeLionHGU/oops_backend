"""Read-only integrity audit for a private cross-case mechanism research draft.

Checks pinned local source files, record identifiers, transcript rows and explicit
approval scope. It does not judge the authored interpretation or modify runtime.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

from casebook_provenance import validate_approval_document
from build_context_units import linked_lines, relation_profiles
from review_context_pilot import parse, read_bytes, require
from review_dataset_catalog import inside

ROOT = Path(__file__).resolve().parents[1] / "datasets/controversy"
SHA256 = re.compile(r"[0-9a-f]{64}\Z")


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def pointer(value, path):
    require(isinstance(path, str) and (path == "" or path.startswith("/")),
            "REVIEW_JSON_POINTER_INVALID")
    current = value
    for token in path.split("/")[1:]:
        token = token.replace("~1", "/").replace("~0", "~")
        if isinstance(current, list):
            require(token.isdigit(), f"REVIEW_JSON_POINTER_INVALID:{path}")
            index = int(token)
            require(index < len(current), f"REVIEW_JSON_POINTER_MISSING:{path}")
            current = current[index]
        elif isinstance(current, dict):
            require(token in current, f"REVIEW_JSON_POINTER_MISSING:{path}")
            current = current[token]
        else:
            require(False, f"REVIEW_JSON_POINTER_MISSING:{path}")
    return current


def audit(root, draft):
    require(draft.get("schemaVersion") == "cross-case-mechanism-review-2"
            and draft.get("status") == "DRAFT_RESEARCH_ONLY_NOT_RUNTIME",
            "REVIEW_DRAFT_STATUS_INVALID")
    require(draft.get("promotion") == {
        "runtimeChange": False,
        "promptChange": False,
        "existingMechanismIdsChanged": False,
        "trainingUseAuthorized": False,
        "reason": draft.get("promotion", {}).get("reason")},
        "REVIEW_PROMOTION_SCOPE_INVALID")

    loaded, formats = {}, {}
    bindings = draft.get("sourceBindings")
    require(isinstance(bindings, list) and bindings, "REVIEW_SOURCE_BINDINGS_REQUIRED")
    for binding in bindings:
        require(isinstance(binding, dict)
                and set(binding) == {"id", "path", "sha256", "format"}
                and isinstance(binding["id"], str) and binding["id"],
                "REVIEW_SOURCE_BINDING_INVALID")
        bid = binding["id"]
        require(bid not in loaded and SHA256.fullmatch(binding["sha256"] or "")
                and binding["format"] in {"JSON", "UTF8_TEXT"}, "REVIEW_SOURCE_BINDING_INVALID")
        raw = read_bytes(inside(root, binding["path"]))
        require(digest(raw) == binding["sha256"], "REVIEW_SOURCE_HASH_CHANGED")
        loaded[bid] = parse(raw) if binding["format"] == "JSON" else raw.decode("utf-8")
        formats[bid] = binding

    links = draft.get("caseLinks")
    require(isinstance(links, list) and links, "REVIEW_CASE_LINKS_REQUIRED")
    case_ids = [case.get("caseId") for case in links if isinstance(case, dict)]
    require(len(case_ids) == len(links) and all(isinstance(cid, str) and cid for cid in case_ids)
            and len(case_ids) == len(set(case_ids)), "REVIEW_CASE_ID_INVALID")
    for case in links:
        refs = case.get("assetBindingIds")
        require(isinstance(refs, list) and refs and len(refs) == len(set(refs))
                and set(refs) <= set(loaded), "REVIEW_CASE_ASSET_LINK_INVALID")

    cross_cutting = draft.get("crossCuttingBoundaryNotMechanismEvidence", {})
    known_context_ids = set(case_ids)
    if isinstance(cross_cutting, dict) and isinstance(cross_cutting.get("caseId"), str):
        known_context_ids.add(cross_cutting["caseId"])
    unit_links = draft.get("contextUnitLinks")
    require(isinstance(unit_links, list), "REVIEW_CONTEXT_UNIT_LINKS_INVALID")
    for link in unit_links:
        require(isinstance(link, dict) and set(link) <= {
            "caseId", "unitBindingId", "relationBindingId"}
            and link.get("caseId") in known_context_ids
            and link.get("unitBindingId") in loaded
            and formats[link["unitBindingId"]]["format"] == "JSON",
            "REVIEW_CONTEXT_UNIT_LINK_INVALID")
        unit = loaded[link["unitBindingId"]]
        require(unit.get("id") == link["caseId"] and unit.get("status") == "DRAFT"
                and unit.get("humanVerified") is False
                and unit.get("trainingUseAuthorized") is False,
                "REVIEW_CONTEXT_UNIT_NOT_DRAFT")
        lines = linked_lines(root, unit)
        require(lines, "REVIEW_CONTEXT_UNIT_SOURCE_EMPTY")
        relation_id = link.get("relationBindingId")
        if relation_id is not None:
            require(relation_id in loaded and formats[relation_id]["format"] == "JSON",
                    "REVIEW_CONTEXT_RELATION_LINK_INVALID")
            relation_profiles([loaded[relation_id]], [unit])

    checks = draft.get("recordChecks")
    require(isinstance(checks, list) and checks, "REVIEW_RECORD_CHECKS_REQUIRED")
    for check in checks:
        require(isinstance(check, dict) and check.get("bindingId") in loaded,
                "REVIEW_RECORD_CHECK_INVALID")
        if set(check) == {"bindingId", "jsonPointer", "equals"}:
            require(formats[check["bindingId"]]["format"] == "JSON"
                    and pointer(loaded[check["bindingId"]], check["jsonPointer"]) == check["equals"],
                    "REVIEW_RECORD_CHECK_MISMATCH")
        elif set(check) == {"bindingId", "textIncludes"}:
            require(formats[check["bindingId"]]["format"] == "UTF8_TEXT"
                    and isinstance(check["textIncludes"], str) and check["textIncludes"]
                    and check["textIncludes"] in loaded[check["bindingId"]],
                    "REVIEW_SOURCE_TEXT_CHECK_MISMATCH")
        else:
            require(False, "REVIEW_RECORD_CHECK_INVALID")

    line_checks = draft.get("lineChecks")
    require(isinstance(line_checks, list) and line_checks, "REVIEW_LINE_CHECKS_REQUIRED")
    for check in line_checks:
        require(isinstance(check, dict) and set(check) == {
            "bindingId", "arrayPointer", "index", "segmentId", "startMs", "endMs", "text"}
            and check["bindingId"] in loaded and formats[check["bindingId"]]["format"] == "JSON"
            and type(check["index"]) is int and check["index"] >= 0,
            "REVIEW_LINE_CHECK_INVALID")
        rows = pointer(loaded[check["bindingId"]], check["arrayPointer"])
        require(isinstance(rows, list) and check["index"] < len(rows), "REVIEW_LINE_SOURCE_MISSING")
        row = rows[check["index"]]
        require(row.get("text") == check["text"]
                and row.get("startMs") == check["startMs"]
                and row.get("endMs") == check["endMs"]
                and check["segmentId"] == f"stt-index-{check['index']}"
                and check["startMs"] <= check["endMs"], "REVIEW_LINE_SOURCE_MISMATCH")

    for approval in draft.get("approvalChecks", []):
        require(isinstance(approval, dict)
                and approval.get("reviewBindingId") in loaded
                and formats[approval["reviewBindingId"]]["format"] == "JSON",
                "REVIEW_APPROVAL_CHECK_INVALID")
        binding = formats[approval["reviewBindingId"]]
        validate_approval_document(
            {"path": binding["path"], "sha256": binding["sha256"],
             "eventIds": approval["eventIds"]},
            loaded[approval["reviewBindingId"]], approval["decision"],
            approval["selectedIds"], approval["sourceSha256"], approval["videoId"])

    cluster = draft.get("researchCluster", {})
    require(cluster.get("status") == "TAXONOMIC_GROUPING_ONLY_NOT_A_SINGLE_DETECTOR_RULE",
            "REVIEW_CLUSTER_MUST_NOT_BECOME_RULE")
    subpatterns = cluster.get("subpatterns")
    require(isinstance(subpatterns, list) and subpatterns, "REVIEW_SUBPATTERNS_REQUIRED")
    linked = set(case_ids)
    for subpattern in subpatterns:
        require(isinstance(subpattern, dict) and isinstance(subpattern.get("caseIds"), list)
                and subpattern["caseIds"] and set(subpattern["caseIds"]) <= linked,
                "REVIEW_SUBPATTERN_CASE_LINK_INVALID")

    candidate = draft.get("normalContrastCandidate")
    require(isinstance(candidate, dict)
            and candidate.get("sourceBindingId") in loaded
            and candidate.get("runBindingId") in loaded
            and candidate.get("trainingUseAuthorized") is False
            and candidate.get("goldEvaluationUseAuthorized") is False,
            "REVIEW_NORMAL_CANDIDATE_USE_SCOPE_INVALID")
    candidate_status = candidate.get("status")
    human_review = candidate.get("humanReview")
    if candidate_status == "PENDING_HUMAN_REVIEW_NOT_A_LABEL":
        require(candidate.get("expectedDecision") is None and human_review is None,
                "REVIEW_PENDING_CANDIDATE_HAS_LABEL")
    elif candidate_status == "HUMAN_REVIEWED_DEVELOPMENT_NORMAL_CONTRAST":
        require(candidate.get("expectedDecision") in {"PASS", "REVIEW_REQUIRED", "UNCERTAIN"}
                and isinstance(human_review, dict)
                and set(human_review) == {
                    "decision", "reviewer", "reviewedAt", "scope", "sourceBasis",
                    "audioVerified", "recordedResponse"}
                and human_review["decision"] == candidate["expectedDecision"]
                and human_review["reviewer"] == "USER_APPROVED_IN_CHAT"
                and human_review["reviewedAt"]
                and human_review["scope"] == "DEVELOPMENT_NORMAL_CONTRAST_ONLY"
                and human_review["sourceBasis"] == "SAVED_STT_ONLY"
                and human_review["audioVerified"] is False
                and human_review["recordedResponse"],
                "REVIEW_HUMAN_DECISION_RECORD_INVALID")
    else:
        require(False, "REVIEW_NORMAL_CANDIDATE_STATUS_INVALID")
    source_id = candidate["sourceBindingId"]
    require(formats[source_id]["format"] == "JSON", "REVIEW_NORMAL_SOURCE_INVALID")
    transcript_rows = pointer(loaded[source_id], "/data")
    segments = candidate.get("segments")
    require(isinstance(segments, list) and segments, "REVIEW_NORMAL_SEGMENTS_REQUIRED")
    source_indices = []
    for segment in segments:
        require(isinstance(segment, dict) and set(segment) == {
            "sourceIndex", "id", "startMs", "endMs", "text"}
            and type(segment["sourceIndex"]) is int
            and 0 <= segment["sourceIndex"] < len(transcript_rows),
            "REVIEW_NORMAL_SEGMENT_INVALID")
        index = segment["sourceIndex"]
        actual = transcript_rows[index]
        require(segment["id"] == f"stt-index-{index}"
                and all(segment[field] == actual.get(field) for field in ("startMs", "endMs", "text")),
                "REVIEW_NORMAL_SEGMENT_SOURCE_MISMATCH")
        source_indices.append(index)
    require(source_indices == list(range(source_indices[0], source_indices[-1] + 1)),
            "REVIEW_NORMAL_CONTEXT_HAS_GAPS")
    summary = loaded[candidate["runBindingId"]]
    require(summary.get("videoId") == candidate.get("videoId")
            and summary.get("youtubeId") == candidate.get("youtubeVideoId"),
            "REVIEW_NORMAL_SOURCE_ID_MISMATCH")

    cross_video_control = draft.get("crossVideoNormalControl")
    cross_video_cues_verified = 0
    historical_outcome = None
    if cross_video_control is not None:
        require(isinstance(cross_video_control, dict)
                and cross_video_control.get("sourceBindingId") in loaded
                and cross_video_control.get("historicalComparisonBindingId") in loaded
                and cross_video_control.get("status") == "HUMAN_REVIEWED_DEVELOPMENT_NORMAL_CONTRAST"
                and cross_video_control.get("expectedDecision") == "PASS"
                and cross_video_control.get("trainingUseAuthorized") is False
                and cross_video_control.get("goldEvaluationUseAuthorized") is False,
                "REVIEW_CROSS_VIDEO_CONTROL_SCOPE_INVALID")
        control_source_id = cross_video_control["sourceBindingId"]
        comparison_id = cross_video_control["historicalComparisonBindingId"]
        require(formats[control_source_id]["format"] == "JSON"
                and formats[comparison_id]["format"] == "JSON",
                "REVIEW_CROSS_VIDEO_SOURCE_INVALID")
        captions = loaded[control_source_id]
        cue_events = captions.get("events")
        cue_checks = cross_video_control.get("cueChecks")
        time_range = cross_video_control.get("timeRangeMs")
        require(isinstance(cue_events, list) and isinstance(cue_checks, list) and cue_checks
                and isinstance(time_range, list) and len(time_range) == 2
                and all(type(value) is int for value in time_range)
                and time_range[0] <= time_range[1],
                "REVIEW_CROSS_VIDEO_CUES_INVALID")
        for cue in cue_checks:
            require(isinstance(cue, dict) and set(cue) == {
                "eventIndex", "tStartMs", "dDurationMs", "text"}
                and type(cue["eventIndex"]) is int
                and 0 <= cue["eventIndex"] < len(cue_events),
                "REVIEW_CROSS_VIDEO_CUE_INVALID")
            event = cue_events[cue["eventIndex"]]
            text = "".join(part.get("utf8", "") for part in event.get("segs", [])).replace("\n", "")
            require(event.get("tStartMs") == cue["tStartMs"]
                    and event.get("dDurationMs") == cue["dDurationMs"]
                    and text == cue["text"]
                    and time_range[0] <= cue["tStartMs"]
                    and cue["tStartMs"] + cue["dDurationMs"] <= time_range[1],
                    "REVIEW_CROSS_VIDEO_CUE_SOURCE_MISMATCH")
        review = cross_video_control.get("humanReview")
        require(isinstance(review, dict) and set(review) == {
            "decision", "reviewer", "reviewedAt", "scope", "sourceBasis",
            "audioVerified", "recordedResponse"}
            and review["decision"] == "PASS"
            and review["reviewer"] == "USER_APPROVED_IN_CHAT"
            and review["reviewedAt"] is None
            and review["scope"] == "DEVELOPMENT_NORMAL_CONTRAST_ONLY"
            and review["sourceBasis"] == "PUBLIC_KOREAN_CAPTIONS"
            and review["audioVerified"] is False
            and review["recordedResponse"] == "PASS",
            "REVIEW_CROSS_VIDEO_HUMAN_APPROVAL_INVALID")
        historical = loaded[comparison_id]
        d05_source_video_ids = []
        for case in links:
            if case["caseId"] == "podcast-dev#d05-taekwondo-belt-standard-devaluation":
                for binding_id in case["assetBindingIds"]:
                    source = loaded[binding_id].get("source") if isinstance(loaded[binding_id], dict) else None
                    if isinstance(source, dict) and type(source.get("videoId")) is int:
                        d05_source_video_ids.append(source["videoId"])
        require(str(historical.get("videoId")) == str(cross_video_control.get("videoId"))
                and historical.get("sourceId") == cross_video_control.get("youtubeVideoId")
                and cross_video_control.get("videoId") != candidate.get("videoId")
                and cross_video_control.get("videoId") not in d05_source_video_ids,
                "REVIEW_CROSS_VIDEO_NOT_INDEPENDENT")
        point = next((item for item in historical.get("points", [])
                      if item.get("id") == "B02"), None)
        outcome = cross_video_control.get("historicalServiceOutcome")
        require(point is not None and isinstance(outcome, dict)
                and outcome.get("decision") == point.get("serviceOutcome") == "REVIEW_REQUIRED"
                and outcome.get("eventIds") == point.get("eventIds")
                and outcome.get("userGoldLabelsAtRun") is historical.get("userGoldLabels") is False
                and outcome.get("versionScope") == "HISTORICAL_RUN_NOT_CURRENT_BASELINE",
                "REVIEW_CROSS_VIDEO_HISTORICAL_RESULT_MISMATCH")
        cross_video_cues_verified = len(cue_checks)
        historical_outcome = outcome["decision"]

    return {
        "draftId": draft["id"], "sourceBindingsVerified": len(loaded),
        "caseLinksVerified": len(links), "recordChecksVerified": len(checks),
        "contextUnitsVerified": len(unit_links),
        "transcriptLineChecksVerified": len(line_checks),
        "normalCandidateSegmentsVerified": len(segments),
        "normalCandidateStatus": candidate["status"],
        "expectedDecisionPresent": candidate["expectedDecision"] is not None,
        "humanDecisionRecorded": human_review is not None,
        "goldEvaluationUseAuthorized": candidate["goldEvaluationUseAuthorized"],
        "crossVideoNormalControlCuesVerified": cross_video_cues_verified,
        "crossVideoHistoricalOutcome": historical_outcome,
        "providerCalls": 0, "runtimeChanged": False, "promptChanged": False,
        "semanticInterpretationVerified": False,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--draft", required=True, help="Path relative to datasets/controversy")
    parser.add_argument("--dataset-root", type=Path, default=ROOT)
    args = parser.parse_args()
    root = args.dataset_root.resolve()
    draft = parse(read_bytes(inside(root, args.draft)))
    print(json.dumps(audit(root, draft), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
