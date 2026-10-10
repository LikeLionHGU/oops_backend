"""Offline conversion to common draft cards. No approval, training or API calls.

Default output is counts only. --build explicitly prints private JSON for local storage.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path

from review_context_pilot import PilotError, parse, read_bytes, require, text
from review_dataset_catalog import DEFAULT_ROOT, inside, validate as validate_catalog, validate_private

STAGES = {"CONTENT", "PUBLICATION_TIMING", "POST_CONTROVERSY_RESPONSE", "UNKNOWN"}
LEVELS = {"SELECTED_STT_UNVERIFIED_AUDIO", "REPORTED_CONTEXT_ONLY"}


def classification(plan, family, reaction_id):
    entry = plan.get("reactions", {}).get(family, {}).get(reaction_id)
    require(isinstance(entry, dict) and entry.get("stage") in STAGES
            and text(entry.get("basis")), "EXPLICIT_REACTION_CLASSIFICATION_REQUIRED")
    return copy.deepcopy(entry)


def convert(incident, draft_bytes, collection_bytes, intake, plan):
    draft, collection = parse(draft_bytes), parse(collection_bytes)
    original = {(c["videoId"], c["commentId"]): c for c in collection["comments"]}
    cards = []
    is_pilot = draft["schemaVersion"] == "context-pilot-2"
    require(is_pilot or draft["schemaVersion"] == "reception-mapping-1", "UNKNOWN_DRAFT_SCHEMA")
    source_cards = {c["id"]: c for c in intake["cases"]}
    for old in draft["cases"]:
        cid = old["id"] if is_pilot else old["caseId"]
        source = old if is_pilot else source_cards[cid]
        mappings = {m["reactionId"]: m for m in old.get("reactionMappings", [])}
        reactions = []
        for reaction in old["reactions"]:
            entry = classification(plan, incident["familyId"], reaction["id"])
            row = original[(reaction["videoId"], reaction["commentId"])]
            excerpt = reaction["excerpt"]
            start = row["text"].find(excerpt)
            require(start >= 0, "EXCERPT_NOT_IN_ORIGINAL")
            mapping = mappings[reaction["id"]] if is_pilot else reaction
            stage, role = entry["stage"], reaction["role"]
            purpose = ("ALTERNATIVE_INTERPRETATION_REFERENCE" if role == "COUNTER_REFERENCE_ONLY"
                       else "POST_RESPONSE_RESEARCH" if stage == "POST_CONTROVERSY_RESPONSE"
                       else "PUBLICATION_REACTION_RESEARCH" if stage == "PUBLICATION_TIMING"
                       else "CONTENT_REACTION_RESEARCH" if stage == "CONTENT" else "UNCLASSIFIED_RESEARCH")
            reactions.append({
                "id": reaction["id"], "role": role, "stage": stage,
                "classification": {"basis": entry["basis"], "status": "ASSISTANT_DRAFT", "humanVerified": False},
                "source": {"collectionId": incident["collectionId"], "videoId": row["videoId"],
                           "commentId": row["commentId"], "publishedAt": row.get("publishedAt")},
                "excerpt": {"text": excerpt, "startCodePoint": start, "endCodePoint": start + len(excerpt),
                            "contextReviewStatus": "PENDING"},
                "interpretation": {"reason": reaction["claim"] if is_pilot else reaction["criticismReason"],
                                   "status": "ASSISTANT_DRAFT_NOT_VIDEO_FACT"},
                "mapping": {"scope": reaction["scope"], "segmentIds": mapping.get("segmentIds", []),
                            "sourceIds": mapping.get("sourceIds", []),
                            "reason": mapping["connectionReason"] if is_pilot else mapping["mappingReason"],
                            "humanVerified": False},
                "unverifiedCommentClaims": mapping.get("unverifiedClaims", []),
                "usage": {"purpose": purpose, "detectionEvidenceEligible": False,
                          "cancelsCriticism": False, "trainingUseAuthorized": False}
            })
        cards.append({
            "id": cid, "familyId": incident["familyId"], "status": "UNREVIEWED_DRAFT",
            "knownPoint": {"text": old["knownControversyPoint"] if is_pilot else source["knownPoint"],
                           "basis": old["pointBasis"] if is_pilot else "SOURCE_RESEARCH_NOT_INDEPENDENTLY_VERIFIED"},
            "evidence": {"level": incident["evidenceLevel"], "originalVideoVerified": False,
                         "segments": copy.deepcopy(old.get("segments", [])),
                         "reportedQuotes": copy.deepcopy(source.get("reportedQuotes", [])), "frames": [],
                         "sources": copy.deepcopy(source.get("sources", [])),
                         "speechProvenance": copy.deepcopy(draft.get("speechProvenance")),
                         "windowMs": old.get("windowMs"), "anchorSegmentId": old.get("anchorSegmentId")},
            "context": {"coverage": old["contextCoverage"], "target": None,
                        "targetStatus": "NOT_RECONSTRUCTED", "missingInformation": copy.deepcopy(old["missingContext"]),
                        "sequenceInterpretations": [{"text": c["claim"], "segmentIds": c["segmentIds"],
                                                     "status": "ASSISTANT_DRAFT_NOT_VIDEO_FACT"}
                                                    for c in old.get("contextChain", [])]},
            "controversy": {"status": "KNOWN_POINT_REASON_MAPPING_PENDING_REVIEW",
                            "reasonHypotheses": [{"text": r["interpretation"]["reason"], "reactionId": r["id"],
                                                 "status": "ASSISTANT_DRAFT_NOT_VIDEO_FACT"} for r in reactions
                                if r["role"] == "CRITICISM_SUPPORT" and r["stage"] in {"CONTENT", "PUBLICATION_TIMING"}],
                            "reasonSupportReactionIds": [r["id"] for r in reactions
                                if r["role"] == "CRITICISM_SUPPORT" and r["stage"] in {"CONTENT", "PUBLICATION_TIMING"}],
                            "boundaries": copy.deepcopy(old.get("generalPattern", {}).get("boundaries", source.get("boundaries", [])))},
            "reactions": reactions,
            "review": {"status": "PENDING", "humanApproved": False, "privacyReviewed": False,
                       "datasetUseAuthorized": False, "trainingUseAuthorized": False, "runtimeEligible": False},
            "provenance": {"legacyFilename": incident["privateDraft"],
                           "legacySha256": hashlib.sha256(draft_bytes).hexdigest()}
        })
    return {"familyId": incident["familyId"], "split": incident["split"], "cards": cards}


def validate_bundle(bundle):
    require(bundle.get("schemaVersion") == "controversy-cards-1" and bundle.get("status") == "UNREVIEWED_DRAFT",
            "COMMON_DRAFT_SCHEMA_REQUIRED")
    require(bundle.get("counterPolicy") == "REFERENCE_ONLY_NO_CANCELLATION", "COUNTER_POLICY_REQUIRED")
    require(all(bundle.get(k) is False for k in ("runtimeEligible", "trainingUseAuthorized", "humanApproved")),
            "NO_AUTOMATIC_APPROVAL")
    collections = {c["id"]: c for c in bundle["collections"]}
    require(len(collections) == len(bundle["collections"]), "DUPLICATE_COLLECTION")
    families, cards, reaction_ids = set(), set(), set()
    stages = dict.fromkeys(sorted(STAGES), 0)
    for incident in bundle["incidents"]:
        family = incident["familyId"]
        require(family not in families and incident["split"] == "DEVELOPMENT", "FAMILY_SPLIT_REQUIRED")
        families.add(family)
        for card in incident["cards"]:
            require(text(card["id"]) and card["id"] not in cards and card["familyId"] == family
                    and card["status"] == "UNREVIEWED_DRAFT", "CARD_IDENTITY_REQUIRED")
            cards.add(card["id"])
            require(text(card["knownPoint"]["text"]) and text(card["knownPoint"]["basis"]), "KNOWN_POINT_REQUIRED")
            require(card["review"]["status"] == "PENDING" and all(card["review"].get(k) is False for k in
                    ("humanApproved", "privacyReviewed", "datasetUseAuthorized", "trainingUseAuthorized", "runtimeEligible")),
                    "CARD_NOT_APPROVED")
            evidence = card["evidence"]
            require(evidence["level"] in LEVELS and evidence["originalVideoVerified"] is False
                    and evidence["frames"] == [], "NO_INVENTED_VIDEO_EVIDENCE")
            segments = {s["id"] for s in evidence["segments"]}
            require(len(segments) == len(evidence["segments"]), "DUPLICATE_SEGMENT")
            if evidence["level"] == "REPORTED_CONTEXT_ONLY":
                require(not segments and evidence["windowMs"] is None and evidence["anchorSegmentId"] is None,
                        "NO_INVENTED_TIMESTAMPS")
            else:
                require(segments and evidence["anchorSegmentId"] in segments, "ANCHOR_REQUIRED")
            sources = {s["id"] for s in evidence["sources"]}
            for quote in evidence["reportedQuotes"]:
                require(quote["sourceId"] in sources and quote["kind"] == "SOURCE_REPORTED_QUOTE_NOT_TRANSCRIPT"
                        and quote["startMs"] is None and quote["endMs"] is None, "REPORTED_QUOTE_NOT_TRANSCRIPT")
            require(card["context"]["target"] is None and card["context"]["targetStatus"] == "NOT_RECONSTRUCTED",
                    "NO_INVENTED_TARGET")
            for link in card["context"]["sequenceInterpretations"]:
                require(text(link["text"]) and link["status"] == "ASSISTANT_DRAFT_NOT_VIDEO_FACT"
                        and link["segmentIds"] and set(link["segmentIds"]) <= segments, "SEQUENCE_REFERENCE_REQUIRED")
            supports = []
            for r in card["reactions"]:
                identity = (family, r["id"])
                require(identity not in reaction_ids and r["stage"] in STAGES, "REACTION_ID_OR_STAGE")
                reaction_ids.add(identity)
                stages[r["stage"]] += 1
                require(r["role"] in {"CRITICISM_SUPPORT", "COUNTER_REFERENCE_ONLY"}, "REACTION_ROLE")
                require(r["classification"]["humanVerified"] is False and text(r["classification"]["basis"]),
                        "CLASSIFICATION_NOT_VERIFIED")
                require(r["source"]["collectionId"] in collections, "UNKNOWN_COMMENT_COLLECTION")
                excerpt = r["excerpt"]
                require(text(excerpt["text"]) and type(excerpt["startCodePoint"]) is int and excerpt["startCodePoint"] >= 0
                        and excerpt["endCodePoint"] == excerpt["startCodePoint"] + len(excerpt["text"])
                        and excerpt["contextReviewStatus"] == "PENDING", "EXCERPT_SPAN_REQUIRED")
                require(r["interpretation"]["status"] == "ASSISTANT_DRAFT_NOT_VIDEO_FACT"
                        and text(r["interpretation"]["reason"]), "COMMENT_NOT_VIDEO_FACT")
                m = r["mapping"]
                require(m["scope"] in {"EXACT_POINT", "SCENE_OR_FLOW", "INCIDENT_ONLY"}
                        and m["humanVerified"] is False and text(m["reason"])
                        and set(m["segmentIds"]) <= segments and set(m["sourceIds"]) <= sources, "MAPPING_REFERENCE_REQUIRED")
                require(not m["segmentIds"] and not m["sourceIds"] if m["scope"] == "INCIDENT_ONLY"
                        else bool(m["segmentIds"] or m["sourceIds"]), "SCOPE_BOUNDARY_REQUIRED")
                usage = r["usage"]
                require(all(usage.get(k) is False for k in
                        ("detectionEvidenceEligible", "cancelsCriticism", "trainingUseAuthorized")), "NO_REACTION_PROMOTION")
                expected = ("ALTERNATIVE_INTERPRETATION_REFERENCE" if r["role"] == "COUNTER_REFERENCE_ONLY"
                            else "POST_RESPONSE_RESEARCH" if r["stage"] == "POST_CONTROVERSY_RESPONSE"
                            else "PUBLICATION_REACTION_RESEARCH" if r["stage"] == "PUBLICATION_TIMING"
                            else "CONTENT_REACTION_RESEARCH" if r["stage"] == "CONTENT" else "UNCLASSIFIED_RESEARCH")
                require(usage["purpose"] == expected, "REACTION_PURPOSE_MISMATCH")
                if r["role"] == "CRITICISM_SUPPORT" and r["stage"] in {"CONTENT", "PUBLICATION_TIMING"}:
                    supports.append(r["id"])
            require(card["controversy"]["reasonSupportReactionIds"] == supports, "POST_RESPONSE_OR_COUNTER_NOT_REASON_SUPPORT")
            require(card["controversy"]["reasonHypotheses"] == [
                {"text": r["interpretation"]["reason"], "reactionId": r["id"],
                 "status": "ASSISTANT_DRAFT_NOT_VIDEO_FACT"} for r in card["reactions"] if r["id"] in supports],
                "REASON_HYPOTHESIS_REFERENCE_MISMATCH")
    return {"status": "STRUCTURALLY_VALID_COMMON_DRAFT", "incidentFamilies": len(families), "cards": len(cards),
            "reactions": len(reaction_ids), "reactionStages": stages, "semanticMappingVerified": False,
            "approvedCases": 0, "runtimeEligible": False}


def build_bundle(dataset_root, private_root, plan):
    require(plan.get("schemaVersion") == "reaction-classification-plan-1"
            and plan.get("status") == "ASSISTANT_DRAFT" and plan.get("humanApproved") is False,
            "CLASSIFICATION_PLAN_REQUIRED")
    catalog = parse(read_bytes(dataset_root / "catalog.json"))
    validate_catalog(catalog, dataset_root)
    validate_private(catalog, private_root, dataset_root=dataset_root)
    intake = parse(read_bytes(inside(dataset_root, catalog["sourceResearchSnapshot"])))
    incidents = []
    for incident in catalog["incidents"]:
        collection = next(c for c in catalog["activeCollections"] if c["id"] == incident["collectionId"])
        incidents.append(convert(incident, read_bytes(inside(private_root, incident["privateDraft"])),
                                 read_bytes(inside(private_root, collection["snapshot"])), intake, plan))
    actual = {(i["familyId"], r["id"]) for i in incidents for c in i["cards"] for r in c["reactions"]}
    planned = {(family, rid) for family, reactions in plan["reactions"].items() for rid in reactions}
    require(actual == planned, "CLASSIFICATION_PLAN_COVERAGE_MISMATCH")
    bundle = {"schemaVersion": "controversy-cards-1", "status": "UNREVIEWED_DRAFT",
              "counterPolicy": "REFERENCE_ONLY_NO_CANCELLATION", "runtimeEligible": False,
              "trainingUseAuthorized": False, "humanApproved": False,
              "collections": copy.deepcopy(catalog["activeCollections"]), "incidents": incidents}
    validate_bundle(bundle)
    return bundle


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--private-root", type=Path, default=Path("uploads/comment-collections"))
    parser.add_argument("--plan", type=Path)
    parser.add_argument("--build", action="store_true", help="Print private common JSON, not a runtime export")
    parser.add_argument("--bundle", type=Path, help="Check an existing local bundle against original sources and plan")
    args = parser.parse_args()
    if args.build and args.bundle:
        parser.error("BUILD_OR_VERIFY_NOT_BOTH")
    try:
        plan = parse(read_bytes(args.plan or args.dataset_root / "research/reaction-classification-plan.json"))
        expected = build_bundle(args.dataset_root, args.private_root, plan)
        if args.bundle:
            actual = parse(read_bytes(args.bundle))
            validate_bundle(actual)
            require(actual == expected, "BUNDLE_SOURCE_OR_PLAN_MISMATCH")
        report = expected if args.build else validate_bundle(expected)
    except PilotError as error:
        parser.exit(2, str(error) + "\n")
    except (ValueError, KeyError, TypeError, AttributeError, OSError, StopIteration):
        parser.exit(2, "INVALID_OR_UNAVAILABLE_INPUT\n")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
