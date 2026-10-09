"""Validate source-only context cards and plan deduplicated comment collection.

No API requests, video access, approvals, runtime exports or comment generation.
Reported quotations are NOT timed video transcripts or observed reactions.
"""
import argparse
import datetime
import hashlib
import json
import re
from urllib.parse import urlparse

from review_context_pilot import PilotError, parse, read_bytes, require, text


def validate(intake):
    require(intake.get("schemaVersion") == "context-intake-1"
            and intake.get("status") == "SOURCE_RESEARCH_ONLY", "SOURCE_ONLY_SCHEMA_REQUIRED")
    require(intake.get("collectionWorkflow") == "KNOWN_POINT_THEN_REACTION_MAPPING", "POINT_FIRST_REQUIRED")
    require(intake.get("counterPolicy") == "REFERENCE_ONLY_NO_CANCELLATION", "COUNTER_POLICY_REQUIRED")
    require(all(intake.get(f) is False for f in ("runtimeEligible", "trainingUseAuthorized", "humanApproved")),
            "NO_APPROVAL_OR_RUNTIME")
    cases = intake.get("cases")
    require(isinstance(cases, list) and 1 <= len(cases) <= 20, "CASE_LIMIT")
    ids, families, videos, report = set(), set(), [], []
    layers = {"LOCAL_DIALOGUE", "SCENE", "SCREEN_TEXT", "PUBLICATION_TIME", "EXTERNAL_EVENT", "CULTURAL_ASSOCIATION"}
    for case in cases:
        require(text(case.get("id"), 100) and case["id"] not in ids, "CASE_ID_REQUIRED")
        ids.add(case["id"])
        require(text(case.get("familyId"), 100) and case.get("split") == "DEVELOPMENT", "FAMILY_AND_DEVELOPMENT_REQUIRED")
        families.add(case["familyId"])
        require(case.get("status") == "AWAITING_ORIGINAL_CONTEXT_AND_COMMENTS", "PENDING_ONLY")
        require(all(case.get(f) is False for f in ("humanApproved", "datasetUseAuthorized")), "CASE_NOT_APPROVED")
        require(all(case.get(f) == [] for f in ("observedSegments", "reactions", "reactionMappings", "collectionRefs")),
                "REPORTED_CONTENT_NOT_OBSERVED_EVIDENCE")
        require(text(case.get("knownPoint")) and text(case.get("patternHypothesis")), "POINT_REQUIRED")
        for field in ("contextRequirements", "boundaries", "gaps"):
            require(isinstance(case.get(field), list) and case[field] and all(text(v) for v in case[field]), "CONTEXT_REQUIREMENTS_REQUIRED")
        require(isinstance(case.get("contextLayers"), list) and case["contextLayers"]
                and all(v in layers for v in case["contextLayers"]), "CONTEXT_LAYERS_REQUIRED")
        require(isinstance(case.get("sources"), list) and case["sources"], "SOURCES_REQUIRED")
        source_ids, source_urls = set(), set()
        for source in case["sources"]:
            require(text(source.get("id"), 100) and source["id"] not in source_ids
                    and source.get("role") == "DISCOVERY_ONLY", "SOURCE_PROVENANCE_REQUIRED")
            require(isinstance(source.get("url"), str) and urlparse(source["url"]).scheme == "https", "HTTPS_SOURCE_REQUIRED")
            source_ids.add(source["id"])
            source_urls.add(source["url"])
        require(isinstance(case.get("reportedQuotes"), list), "REPORTED_QUOTES_REQUIRED")
        for quote in case["reportedQuotes"]:
            require(text(quote.get("text")) and quote.get("sourceId") in source_ids
                    and quote.get("kind") == "SOURCE_REPORTED_QUOTE_NOT_TRANSCRIPT"
                    and "startMs" in quote and "endMs" in quote
                    and quote["startMs"] is None and quote["endMs"] is None, "NO_INVENTED_VIDEO_TIMES")
        source = case.get("commentSource")
        if source is not None:
            vid = source.get("videoId")
            require(isinstance(vid, str) and re.fullmatch(r"[A-Za-z0-9_-]{11}", vid)
                    and source.get("url") == "https://www.youtube.com/watch?v=" + vid,
                    "COMMENT_VIDEO_REQUIRED")
            require(source.get("discoveredFrom") in source_urls
                    and source.get("discoveryBasis") in {"PUBLISHER_PAGE_YOUTUBE_ID_METADATA", "OFFICIAL_SEARCH_RESULT_UNVERIFIED"}
                    and source.get("originalClipVerified") is False, "COMMENT_SOURCE_PROVENANCE_REQUIRED")
            if vid not in videos:
                videos.append(vid)
        report.append({"caseId": case["id"], "contextLayers": case["contextLayers"],
                       "commentSourceLocated": source is not None, "observedReactions": 0})
    require(len(videos) <= 5, "COLLECTOR_VIDEO_LIMIT")
    return {"status": "VALID_SOURCE_RESEARCH_ONLY", "cases": report,
            "incidentFamilies": len(families), "uniqueCommentVideoIds": videos,
            "maximumApiRequestsAtTwoOrders": 2 * len(videos),
            "collectionExecuted": False, "runtimeEligible": False,
            "suggestedCollectionCommand": ("python tools/collect_youtube_comments.py " +
                " ".join("--video-id " + vid for vid in videos)) if videos else None}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("intake")
    parser.add_argument("--mapping", help="Private reported-context reaction draft")
    parser.add_argument("--collection", help="Original temporary comment snapshot")
    parser.add_argument("--patterns", help="Public context-pattern hypothesis registry")
    parser.add_argument("--pilot", help="Private timed-context pilot for reference checking only")
    args = parser.parse_args()
    if bool(args.mapping) != bool(args.collection):
        parser.error("MAPPING_AND_COLLECTION_REQUIRED_TOGETHER")
    if bool(args.patterns) != bool(args.pilot):
        parser.error("PATTERNS_AND_PILOT_REQUIRED_TOGETHER")
    try:
        intake = parse(read_bytes(args.intake))
        report = validate(intake)
        if args.mapping:
            report["reactionDraft"] = validate_mapping(intake, parse(read_bytes(args.mapping)), read_bytes(args.collection))
        if args.patterns:
            report["patternReferences"] = validate_patterns(parse(read_bytes(args.patterns)), intake,
                                                           parse(read_bytes(args.pilot)))
    except PilotError as error:
        parser.exit(2, str(error) + "\n")
    except (ValueError, KeyError, TypeError, AttributeError, OSError):
        parser.exit(2, "INVALID_OR_UNAVAILABLE_INPUT\n")
    print(json.dumps(report, ensure_ascii=False, indent=2))


def validate_mapping(intake, draft, collection_bytes, now=None):
    """Check citations/counts only. Do not certify reaction semantics or video facts."""
    validate(intake)
    require(draft.get("schemaVersion") == "reception-mapping-1"
            and draft.get("status") == "ASSISTANT_DRAFT_REPORTED_CONTEXT_ONLY", "RECEPTION_DRAFT_REQUIRED")
    require(draft.get("split") == "DEVELOPMENT" and draft.get("counterPolicy") == "REFERENCE_ONLY_NO_CANCELLATION",
            "RECEPTION_POLICY_REQUIRED")
    require(all(draft.get(f) is False for f in ("runtimeEligible", "datasetUseAuthorized", "trainingUseAuthorized",
                                               "humanApproved", "originalVideoVerified")), "NO_RECEPTION_APPROVAL")
    reference = draft["collection"]
    require(reference["sha256"] == hashlib.sha256(collection_bytes).hexdigest(), "COLLECTION_HASH_MISMATCH")
    collection = parse(collection_bytes)
    require(collection.get("schemaVersion") == "youtube-temporary-review-1", "COLLECTION_SCHEMA_REQUIRED")
    require(collection.get("refreshOrDeleteBy") == reference["refreshOrDeleteBy"], "RETENTION_MISMATCH")
    deadline = datetime.datetime.fromisoformat(reference["refreshOrDeleteBy"])
    require(deadline.tzinfo is not None and deadline > (now or datetime.datetime.now(datetime.timezone.utc)),
            "REFRESH_OR_DELETE_REQUIRED")
    originals = {}
    for comment in collection["comments"]:
        identity = (comment["videoId"], comment["commentId"])
        require(identity not in originals, "DUPLICATE_SOURCE_COMMENT")
        originals[identity] = comment["text"]
    cards = {c["id"]: c for c in intake["cases"]}
    cases = draft.get("cases")
    require(isinstance(cases, list) and 1 <= len(cases) <= 20, "CASE_LIMIT")
    seen_cases, seen_reactions, unique_comments, counts = set(), set(), set(), []
    for case in cases:
        cid = case.get("caseId")
        require(cid in cards and cid not in seen_cases and cards[cid]["familyId"] == draft.get("familyId"), "CASE_FAMILY_REQUIRED")
        seen_cases.add(cid)
        require(case.get("contextCoverage") == "REPORTED_CONTEXT_ONLY_NOT_VIDEO_TRANSCRIPT"
                and case.get("observedSegments") == [], "NO_INVENTED_VIDEO_CONTEXT")
        require(isinstance(case.get("missingContext"), list) and case["missingContext"]
                and all(text(v) for v in case["missingContext"]), "MISSING_CONTEXT_REQUIRED")
        reactions = case.get("reactions")
        require(isinstance(reactions, list) and 1 <= len(reactions) <= 12, "REACTION_LIMIT")
        source_ids = {s["id"] for s in cards[cid]["sources"]}
        criticism_count = 0
        for reaction in reactions:
            rid = reaction.get("id")
            require(text(rid, 100) and rid not in seen_reactions, "REACTION_ID_REQUIRED")
            seen_reactions.add(rid)
            identity = (reaction["videoId"], reaction["commentId"])
            require(identity in originals and text(reaction.get("excerpt"))
                    and reaction["excerpt"] in originals[identity], "REACTION_EXCERPT_NOT_IN_SOURCE")
            source = cards[cid].get("commentSource")
            require(source is not None and source["videoId"] == identity[0], "REACTION_VIDEO_MISMATCH")
            unique_comments.add(identity)
            require(reaction.get("role") in {"CRITICISM_SUPPORT", "COUNTER_REFERENCE_ONLY"}, "REACTION_ROLE_REQUIRED")
            require(reaction.get("scope") in {"SCENE_OR_FLOW", "INCIDENT_ONLY"}, "REPORTED_CONTEXT_SCOPE_REQUIRED")
            require(text(reaction.get("criticismReason")) and text(reaction.get("mappingReason")), "MAPPING_REASON_REQUIRED")
            linked = reaction.get("sourceIds")
            require(isinstance(linked, list) and len(set(linked)) == len(linked)
                    and all(s in source_ids for s in linked), "MAPPING_SOURCE_REQUIRED")
            require(not linked if reaction["scope"] == "INCIDENT_ONLY" else bool(linked), "MAPPING_SCOPE_BOUNDARY")
            require(isinstance(reaction.get("unverifiedClaims"), list)
                    and all(text(v) for v in reaction["unverifiedClaims"]), "UNVERIFIED_CLAIMS_REQUIRED")
            criticism_count += reaction["role"] == "CRITICISM_SUPPORT"
        require(criticism_count > 0, "CRITICISM_SUPPORT_REQUIRED")
        counts.append({"caseId": cid, "selectedReactions": len(reactions), "criticisms": criticism_count})
    return {"status": "STRUCTURALLY_VALID_REPORTED_CONTEXT_DRAFT", "cases": counts,
            "uniqueSelectedComments": len(unique_comments), "originalVideoVerified": False,
            "semanticMappingVerified": False, "runtimeEligible": False}


def validate_patterns(registry, intake, pilot):
    """Count distinct incident families, not cards, and check reference identities.

    This does not validate pilot source excerpts; use its dedicated validator too.
    No semantic certification or model performance measurement.
    """
    validate(intake)
    require(registry.get("schemaVersion") == "context-pattern-hypotheses-1"
            and registry.get("status") == "DRAFT_NOT_RUNTIME_RULES"
            and registry.get("runtimeEligible") is False and registry.get("humanApproved") is False,
            "PATTERN_DRAFT_ONLY")
    require(pilot.get("schemaVersion") == "context-pilot-2" and pilot.get("status") == "ASSISTANT_DRAFT"
            and pilot.get("humanApproved") is False and pilot.get("split") == "DEVELOPMENT", "PILOT_DRAFT_REQUIRED")
    refs = {}
    for case in pilot["cases"]:
        require(case["id"] not in refs, "DUPLICATE_CASE_REFERENCE")
        refs[case["id"]] = (pilot["familyId"], "SELECTED_STT_CONTEXT")
    for case in intake["cases"]:
        require(case["id"] not in refs, "DUPLICATE_CASE_REFERENCE")
        refs[case["id"]] = (case["familyId"], "REPORTED_CONTEXT_ONLY")
    mechanisms, modifiers = registry.get("mechanisms"), registry.get("modifiers")
    require(isinstance(mechanisms, list) and 1 <= len(mechanisms) <= 12
            and isinstance(modifiers, list) and len(modifiers) <= 12, "PATTERN_LIMIT")
    ids, report = set(), []
    for entry in mechanisms + modifiers:
        require(text(entry.get("id"), 100) and entry["id"] not in ids and text(entry.get("question")), "PATTERN_ID_REQUIRED")
        ids.add(entry["id"])
        require(entry.get("transferStatus") == "HYPOTHESIS_NOT_VALIDATED", "PATTERN_TRANSFER_NOT_VALIDATED")
        for field in ("requiredEvidence", "boundaries"):
            require(isinstance(entry.get(field), list) and entry[field] and all(text(v) for v in entry[field]), "PATTERN_EVIDENCE_REQUIRED")
        require(isinstance(entry.get("cases"), list) and entry["cases"], "PATTERN_CASES_REQUIRED")
        seen, families = set(), set()
        for ref in entry["cases"]:
            cid = ref.get("caseId")
            require(cid in refs and cid not in seen and refs[cid] == (ref.get("familyId"), ref.get("draftKind")),
                    "PATTERN_REFERENCE_MISMATCH")
            require(text(ref.get("conditionHypothesis")) and text(ref.get("gap")), "PATTERN_GAPS_REQUIRED")
            seen.add(cid)
            families.add(ref["familyId"])
        report.append({"patternId": entry["id"], "referencedCards": len(seen), "distinctIncidentFamilies": len(families)})
    return {"status": "STRUCTURALLY_VALID_HYPOTHESIS_REFERENCES", "patterns": report,
            "crossIncidentGeneralizationVerified": False, "sourceExcerptsVerifiedByThisCheck": False,
            "runtimeEligible": False}


if __name__ == "__main__":
    main()
