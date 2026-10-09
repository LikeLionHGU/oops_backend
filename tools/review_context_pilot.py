"""Check a private context-case draft against its original comment snapshot.

No API/model calls, semantic certification, human approval or runtime export.
Only counts and safe contract errors are printed, never comment text or keys.
"""
import argparse
import copy
import datetime
import hashlib
import json
from pathlib import Path


class PilotError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise PilotError(message)


def text(value, max_length=2000):
    return isinstance(value, str) and bool(value.strip()) and len(value) <= max_length


def read_bytes(path):
    with Path(path).open("rb") as stream:
        raw = stream.read(4_194_305)
    require(len(raw) <= 4_194_304, "FILE_TOO_LARGE")
    return raw


def parse(raw):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "DUPLICATE_JSON_KEY")
            result[key] = value
        return result
    return json.loads(raw, object_pairs_hook=unique)


def validate(pilot, collection_bytes, now=None):
    require(pilot.get("schemaVersion") == "context-pilot-2" and pilot.get("status") == "ASSISTANT_DRAFT", "DRAFT_SCHEMA_REQUIRED")
    require(pilot.get("collectionWorkflow") == "KNOWN_POINT_THEN_REACTION_MAPPING", "POINT_FIRST_WORKFLOW_REQUIRED")
    require(pilot.get("split") == "DEVELOPMENT", "DEVELOPMENT_ONLY")
    require(all(pilot.get(flag) is False for flag in ("datasetUseAuthorized", "trainingUseAuthorized", "humanApproved")), "NO_AUTOMATIC_APPROVAL")
    require(pilot.get("counterPolicy") == "REFERENCE_ONLY_NO_CANCELLATION", "COUNTER_POLICY_REQUIRED")
    require(text(pilot.get("version"), 64) and text(pilot.get("familyId"), 64), "IDENTITY_REQUIRED")
    reference = pilot["collection"]
    require(hashlib.sha256(collection_bytes).hexdigest() == reference["sha256"], "COLLECTION_HASH_MISMATCH")
    collection = parse(collection_bytes)
    require(collection.get("schemaVersion") == "youtube-temporary-review-1", "COLLECTION_SCHEMA_REQUIRED")
    require(collection.get("refreshOrDeleteBy") == reference["refreshOrDeleteBy"], "RETENTION_MISMATCH")
    deadline = datetime.datetime.fromisoformat(reference["refreshOrDeleteBy"])
    require(deadline.tzinfo is not None and deadline > (now or datetime.datetime.now(datetime.timezone.utc)), "REFRESH_OR_DELETE_REQUIRED")
    originals = {}
    for comment in collection["comments"]:
        identity = (comment["videoId"], comment["commentId"])
        require(identity not in originals, "DUPLICATE_SOURCE_COMMENT")
        originals[identity] = comment
    require(isinstance(pilot.get("cases"), list) and 1 <= len(pilot["cases"]) <= 20, "CASE_LIMIT")
    ids, report = set(), []
    for case in pilot["cases"]:
        require(text(case.get("id"), 64) and case["id"] not in ids, "CASE_ID_REQUIRED")
        ids.add(case["id"])
        require(case.get("status") == "DRAFT", "CASE_NOT_DRAFT")
        require(text(case.get("knownControversyPoint")) and text(case.get("pointBasis")), "CONTROVERSY_PROVENANCE_REQUIRED")
        window = case["windowMs"]
        require(isinstance(window, list) and len(window) == 2 and all(type(v) is int for v in window)
                and 0 <= window[0] < window[1], "INVALID_WINDOW")
        segments = {}
        require(isinstance(case["segments"], list) and 1 <= len(case["segments"]) <= 100, "SEGMENT_LIMIT")
        last_start = -1
        for segment in case["segments"]:
            require(text(segment.get("id"), 64) and segment["id"] not in segments, "SEGMENT_ID_REQUIRED")
            require(text(segment.get("rawText")), "RAW_TEXT_REQUIRED")
            start, end = segment["startMs"], segment["endMs"]
            require(type(start) is int and type(end) is int and window[0] <= start < end <= window[1]
                    and start >= last_start, "SEGMENT_TIME_OR_ORDER")
            last_start = start
            segments[segment["id"]] = segment
        require(case["anchorSegmentId"] in segments, "UNKNOWN_ANCHOR")
        require(isinstance(case["contextChain"], list) and 1 <= len(case["contextChain"]) <= 12, "CONTEXT_CHAIN_REQUIRED")
        for link in case["contextChain"]:
            require(text(link.get("claim")) and isinstance(link.get("segmentIds"), list) and link["segmentIds"]
                    and all(s in segments for s in link["segmentIds"]), "UNGROUNDED_CHAIN")
        reactions = case["reactions"]
        require(isinstance(reactions, list) and 1 <= len(reactions) <= 12, "REACTION_LIMIT")
        reaction_ids, counts = set(), {"criticisms": 0, "exactPointCriticisms": 0, "referenceCounters": 0}
        for reaction in reactions:
            require(text(reaction.get("id"), 64) and reaction["id"] not in reaction_ids, "REACTION_ID_REQUIRED")
            reaction_ids.add(reaction["id"])
            identity = (reaction["videoId"], reaction["commentId"])
            require(identity in originals, "COMMENT_NOT_IN_SOURCE")
            require(text(reaction.get("excerpt")) and reaction["excerpt"] in originals[identity]["text"], "EXCERPT_NOT_IN_SOURCE")
            require(text(reaction.get("claim")), "REACTION_CLAIM_REQUIRED")
            require(reaction["scope"] in {"EXACT_POINT", "SCENE_OR_FLOW", "INCIDENT_ONLY"}, "REACTION_SCOPE_REQUIRED")
            require(reaction["role"] in {"CRITICISM_SUPPORT", "COUNTER_REFERENCE_ONLY"}, "REACTION_ROLE_REQUIRED")
            if reaction["role"] == "CRITICISM_SUPPORT":
                counts["criticisms"] += 1
                counts["exactPointCriticisms"] += reaction["scope"] == "EXACT_POINT"
            else:
                counts["referenceCounters"] += 1
        require(counts["criticisms"] > 0, "CRITICISM_SUPPORT_REQUIRED")
        require(case.get("contextCoverage") == "SELECTED_EXCERPTS_NOT_FULL_TRANSCRIPT", "CONTEXT_COVERAGE_REQUIRED")
        require(isinstance(case.get("missingContext"), list) and case["missingContext"]
                and all(text(v) for v in case["missingContext"]), "MISSING_CONTEXT_REQUIRED")
        mappings = case.get("reactionMappings")
        require(isinstance(mappings, list) and len(mappings) == len(reactions), "MAPPING_COVERAGE_REQUIRED")
        mapped = set()
        scopes = {r["id"]: r["scope"] for r in reactions}
        for mapping in mappings:
            rid = mapping.get("reactionId")
            require(rid in reaction_ids and rid not in mapped, "MAPPING_ID_REQUIRED")
            mapped.add(rid)
            linked = mapping.get("segmentIds")
            require(isinstance(linked, list) and len(linked) == len(set(linked))
                    and all(s in segments for s in linked), "MAPPING_SEGMENTS_REQUIRED")
            require((not linked) if scopes[rid] == "INCIDENT_ONLY" else bool(linked), "MAPPING_SCOPE_BOUNDARY")
            require(text(mapping.get("connectionReason")), "MAPPING_REASON_REQUIRED")
            require(isinstance(mapping.get("unverifiedClaims"), list)
                    and all(text(v) for v in mapping["unverifiedClaims"]), "CLAIM_PROVENANCE_REQUIRED")
        pattern = case["generalPattern"]
        require(text(pattern.get("id"), 100) and text(pattern.get("description")), "PATTERN_REQUIRED")
        for name in ("conditions", "boundaries", "additionalEvidenceNeeded"):
            require(isinstance(pattern.get(name), list) and 1 <= len(pattern[name]) <= 12
                    and all(text(v) for v in pattern[name]), "PATTERN_CONDITIONS_REQUIRED")
        require(pattern.get("transferStatus") == "HYPOTHESIS_NOT_VALIDATED", "TRANSFER_NOT_VALIDATED")
        report.append(dict(caseId=case["id"], segments=len(segments), **counts))
    return {"valid": True, "status": "STRUCTURALLY_VALID_DRAFT", "cases": report,
            "humanApprovedCases": 0, "runtimeExportedCases": 0,
            "semanticLinkingVerified": False, "crossVideoGeneralizationVerified": False}


def context_handoff(pilot, case_id, collection_bytes, now=None):
    """Offline draft review packet, NOT an approved runtime/model input.

    Keep all selected raw segments and original sequence alongside interpretations.
    No truncation, majority voting, text correction or promotion of comment claims
    to video facts. Callers must keep the packet private like its source snapshot.
    """
    validate(pilot, collection_bytes, now=now)
    case = next((c for c in pilot["cases"] if c["id"] == case_id), None)
    require(case is not None, "CASE_NOT_FOUND")
    return copy.deepcopy({"status": "PRIVATE_DRAFT_REVIEW_ONLY", "runtimeEligible": False,
            "caseId": case["id"], "familyId": pilot["familyId"],
            "knownPoint": {"description": case["knownControversyPoint"], "basis": case["pointBasis"]},
            "videoEvidence": {"windowMs": case["windowMs"], "anchorSegmentId": case["anchorSegmentId"],
                              "coverage": case["contextCoverage"], "segments": case["segments"],
                              "provenance": pilot.get("speechProvenance", {})},
            "interpretationHypotheses": case["contextChain"],
            "reactionEvidence": {"reactions": case["reactions"], "mappings": case["reactionMappings"],
                                 "counterPolicy": pilot["counterPolicy"], "collection": pilot["collection"]},
            "missingContext": case["missingContext"], "transferHypothesis": case["generalPattern"]})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pilot")
    parser.add_argument("--collection", required=True)
    parser.add_argument("--check-handoff", action="store_true", help="Build draft packets in memory; print only counts")
    args = parser.parse_args()
    try:
        pilot, raw = parse(read_bytes(args.pilot)), read_bytes(args.collection)
        result = validate(pilot, raw)
        if args.check_handoff:
            result["handoffChecks"] = []
            for case in pilot["cases"]:
                packet = context_handoff(pilot, case["id"], raw)
                result["handoffChecks"].append({"caseId": case["id"],
                    "preservedSegments": len(packet["videoEvidence"]["segments"]),
                    "reactionMappings": len(packet["reactionEvidence"]["mappings"]),
                    "runtimeEligible": packet["runtimeEligible"]})
    except PilotError as error:
        parser.exit(2, str(error) + "\n")
    except (ValueError, KeyError, TypeError, AttributeError, OSError):
        parser.exit(2, "INVALID_OR_UNAVAILABLE_INPUT\n")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
