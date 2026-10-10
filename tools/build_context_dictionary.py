"""Build a private, three-layer research dictionary; never export runtime rules.

No network, model calls, approval or invented source extracts. A local source
snapshot proves an extract's integrity, not its truth or independent authorship.
"""
import argparse
import copy
import datetime
import hashlib
import json
from pathlib import Path
from urllib.parse import urlparse

from controversy_cards import build_bundle, validate_bundle
from build_review_guidelines import write_private
from review_context_pilot import PilotError, parse, read_bytes, require, text
from review_dataset_catalog import inside

ROOT = Path(__file__).resolve().parents[1]
DEFAULT = ROOT / "datasets/controversy"
KINDS = {"NEWS_BODY", "EDITORIAL", "WIKI", "COMMUNITY_SUMMARY"}


def check_extract(document, snapshots):
    """Caller resolves private local paths; no fetching arbitrary URLs."""
    require(document.get("materialKind") in KINDS, "SOURCE_KIND_REQUIRED")
    name = document.get("snapshot")
    require(text(name, 200) and name in snapshots, "SOURCE_SNAPSHOT_REQUIRED")
    raw = snapshots[name]
    require(document.get("sha256") == hashlib.sha256(raw).hexdigest(), "SOURCE_SNAPSHOT_HASH_MISMATCH")
    body = raw.decode("utf-8")
    start, end = document.get("startCodePoint"), document.get("endCodePoint")
    require(type(start) is int and type(end) is int and 0 <= start < end <= len(body)
            and text(document.get("excerpt"), 2000) and body[start:end] == document["excerpt"],
            "SOURCE_EXCERPT_SPAN_MISMATCH")
    require(document.get("statementKind") in {"REPORTED_UTTERANCE", "AUTHOR_INTERPRETATION", "REPORTED_AUDIENCE_REACTION"},
            "SOURCE_STATEMENT_KIND_REQUIRED")
    require(text(document.get("locationHint"), 300), "SOURCE_LOCATION_REQUIRED")
    require(document.get("snapshotCoverage") == "SHORT_EXTRACT_NOT_FULL_ARTICLE", "SOURCE_CAPTURE_SCOPE_REQUIRED")
    datetime.date.fromisoformat(document["retrievedOn"])
    require(document.get("originGroupId") is None or text(document["originGroupId"], 100),
            "SOURCE_ORIGIN_GROUP_INVALID")
    # Neither extract matching nor a shared origin ID supplies semantic approval.
    fields = ("id", "sourceId", "materialKind", "snapshot", "sha256", "startCodePoint", "endCodePoint",
              "excerpt", "statementKind", "originGroupId", "locationHint", "retrievedOn", "snapshotCoverage")
    return {**{key: copy.deepcopy(document[key]) for key in fields}, "status": "EXTRACT_MATCHED_NOT_FACT_VERIFIED",
            "contextReviewStatus": "PENDING", "humanVerified": False, "groundTruth": False}


def compile_dictionary(bundle, plan, guideline_plan, source_bytes, snapshots=None):
    validate_bundle(bundle)
    require(plan.get("schemaVersion") == "context-dictionary-plan-1"
            and plan.get("status") == "ASSISTANT_DRAFT" and plan.get("humanApproved") is False,
            "DRAFT_PLAN_REQUIRED")
    require(guideline_plan.get("humanValidated") is False
            and guideline_plan.get("status") == "WORKING_REFERENCE_NOT_VALIDATED", "WORKING_MECHANISMS_REQUIRED")
    cards = {c["id"]: c for incident in bundle["incidents"] for c in incident["cards"]}
    configured = plan.get("cards", {})
    require(set(configured) == set(cards), "DICTIONARY_CARD_COVERAGE_MISMATCH")
    mechanisms = {m["id"]: m for m in guideline_plan["mechanisms"]}
    snapshots = snapshots or {}
    entries = []
    for cid, card in cards.items():
        config = configured[cid]
        tags = config.get("topicTags")
        mids = config.get("mechanismIds")
        require(isinstance(tags, list) and 1 <= len(tags) <= 8
                and all(text(t, 80) for t in tags) and len(set(tags)) == len(tags), "TOPIC_TAGS_REQUIRED")
        expected = {m["id"] for m in mechanisms.values() if cid in m["sourceCaseIds"]}
        require(isinstance(mids, list) and mids and len(set(mids)) == len(mids)
                and set(mids) == expected, "MECHANISM_SOURCE_MAPPING_MISMATCH")
        sources = {s["id"]: s for s in card["evidence"]["sources"]}
        additions = config.get("additionalResearchSources", [])
        require(isinstance(additions, list) and len(additions) <= 16, "ADDITIONAL_SOURCE_LIMIT")
        for source in additions:
            require(text(source.get("id"), 100) and source["id"] not in sources
                    and text(source.get("publisher"), 200) and source.get("role") == "DISCOVERY_ONLY",
                    "ADDITIONAL_SOURCE_METADATA_REQUIRED")
            url = urlparse(source.get("url", ""))
            require(url.scheme in {"http", "https"} and url.hostname and not url.username and not url.password,
                    "ADDITIONAL_SOURCE_URL_REQUIRED")
            sources[source["id"]] = source
        origin_groups = config.get("sourceOriginGroups", {})
        require(isinstance(origin_groups, dict) and set(origin_groups) <= set(sources)
                and all(text(group, 100) for group in origin_groups.values()), "SOURCE_ORIGIN_MAPPING_INVALID")
        documents = config.get("documents")
        require(isinstance(documents, list) and len(documents) <= 16, "SOURCE_DOCUMENTS_REQUIRED")
        extracts, ids = [], set()
        for document in documents:
            require(text(document.get("id"), 100) and document["id"] not in ids
                    and document.get("sourceId") in sources, "SOURCE_DOCUMENT_REFERENCE_REQUIRED")
            ids.add(document["id"])
            require(document.get("originGroupId") is None or document.get("sourceId") not in origin_groups
                    or document["originGroupId"] == origin_groups[document["sourceId"]], "SOURCE_ORIGIN_GROUP_CONFLICT")
            extracts.append(check_extract(document, snapshots))
        units, background, alternatives = [], [], []
        for reaction in card["reactions"]:
            unit = {"reactionId": reaction["id"], "stage": reaction["stage"],
                    "scope": reaction["mapping"]["scope"],
                    "interpretation": copy.deepcopy(reaction["interpretation"]),
                    "mapping": copy.deepcopy(reaction["mapping"]),
                    "unverifiedClaims": copy.deepcopy(reaction["unverifiedCommentClaims"]),
                    "source": copy.deepcopy(reaction["source"]),
                    "rawExcerptLocation": {"cardId": cid, "reactionId": reaction["id"]},
                    "videoEvidence": False, "humanVerified": False}
            if reaction["role"] == "COUNTER_REFERENCE_ONLY":
                alternatives.append(unit)
            elif reaction["stage"] in {"CONTENT", "PUBLICATION_TIMING"} and reaction["mapping"]["scope"] != "INCIDENT_ONLY":
                units.append(unit)
            else:
                background.append(unit)
        gaps = list(card["context"]["missingInformation"])
        if not extracts:
            gaps.append("기사·위키·정리글의 원문 해석 발췌 미확보; 링크/보도 인용을 독립 해석 근거로 세지 않음")
        gaps.append("실제 정상 대조 영상 미확보; 적용 경계는 정상 사례 데이터가 아님")
        entries.append({
            "id": cid, "familyId": card["familyId"], "status": "UNREVIEWED_DRAFT",
            "taxonomy": {"topicTags": tags, "mechanismIds": mids, "status": "ASSISTANT_DRAFT_NOT_RISK_LABEL"},
            "utteranceEvidence": copy.deepcopy(card["evidence"]),
            "interpretationMaterial": {
                "knownPoint": copy.deepcopy(card["knownPoint"]),
                "sequenceHypotheses": copy.deepcopy(card["context"]["sequenceInterpretations"]),
                "researchLinks": [{**copy.deepcopy(s), "status": "EXTRACT_AVAILABLE_NOT_VERIFIED_INTERPRETATION"
                                   if any(e["sourceId"] == s["id"] for e in extracts) else "LINK_ONLY_NOT_VERIFIED_INTERPRETATION",
                                   "originGroupId": origin_groups.get(s["id"]), "independenceVerified": False} for s in sources.values()],
                "sourceExtracts": extracts, "independentEvidenceCount": None},
            "audienceReception": {"pointReasonUnits": units, "incidentOrOtherReactions": background,
                                  "counterReferenceOnly": alternatives,
                                  "aggregation": "NO_TOP_K_REASON_SUMMARY_NO_POPULATION_ESTIMATE",
                                  "counterCancelsCriticism": False},
            "normalComparison": {"status": "BOUNDARY_ONLY_NO_OBSERVED_CONTROL",
                                 "criteria": [{"mechanismId": mid, "text": mechanisms[mid]["normalContrast"]} for mid in mids],
                                 "observedCases": []},
            "review": {"status": "PENDING", "gaps": gaps, "humanApproved": False,
                       "privacyReviewed": False, "datasetUseAuthorized": False,
                       "groundTruth": False, "runtimeEligible": False, "trainingUseAuthorized": False}})
    deadlines = [datetime.datetime.fromisoformat(c["refreshOrDeleteBy"]) for c in bundle["collections"]]
    require(deadlines, "SOURCE_RETENTION_REQUIRED")
    return {"schemaVersion": "context-dictionary-1", "status": "UNREVIEWED_DRAFT",
            "sourceBundleSha256": hashlib.sha256(source_bytes).hexdigest(),
            "planSha256": hashlib.sha256(json.dumps(plan, ensure_ascii=False, sort_keys=True).encode()).hexdigest(),
            "guidelinePlanSha256": hashlib.sha256(json.dumps(guideline_plan, ensure_ascii=False, sort_keys=True).encode()).hexdigest(),
            "refreshOrDeleteBy": min(deadlines).isoformat(),
            "humanApproved": False, "runtimeEligible": False, "trainingUseAuthorized": False,
            "entries": entries}


def summary(dictionary):
    entries = dictionary["entries"]
    return {"status": dictionary["status"], "cards": len(entries),
            "pointReasonUnits": sum(len(c["audienceReception"]["pointReasonUnits"]) for c in entries),
            "incidentOrOtherReactions": sum(len(c["audienceReception"]["incidentOrOtherReactions"]) for c in entries),
            "counterReferences": sum(len(c["audienceReception"]["counterReferenceOnly"]) for c in entries),
            "sourceExtracts": sum(len(c["interpretationMaterial"]["sourceExtracts"]) for c in entries),
            "approvedCases": 0, "observedNormalCases": 0, "runtimeChanged": False,
            "refreshOrDeleteBy": dictionary["refreshOrDeleteBy"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-root", type=Path, default=DEFAULT)
    parser.add_argument("--private-root", type=Path, default=ROOT / "uploads/comment-collections")
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    if args.write and args.verify:
        parser.error("WRITE_OR_VERIFY_NOT_BOTH")
    try:
        root = args.dataset_root
        source_bytes = read_bytes(root / "drafts/cards.json")
        bundle = parse(source_bytes)
        expected = build_bundle(root, args.private_root, parse(read_bytes(root / "research/reaction-classification-plan.json")))
        require(bundle == expected, "BUNDLE_SOURCE_OR_PLAN_MISMATCH")
        plan = parse(read_bytes(root / "research/context-dictionary-plan.json"))
        snapshots = {}
        for config in plan["cards"].values():
            for document in config["documents"]:
                name = document["snapshot"]
                require(name.startswith("research/source-snapshots/"), "PRIVATE_SOURCE_SNAPSHOT_PATH_REQUIRED")
                snapshots[name] = read_bytes(inside(root, name))
        dictionary = compile_dictionary(bundle, plan, parse(read_bytes(root / "guidelines/plan.json")), source_bytes, snapshots)
        require(datetime.datetime.fromisoformat(dictionary["refreshOrDeleteBy"]) > datetime.datetime.now(datetime.timezone.utc),
                "REFRESH_OR_DELETE_REQUIRED")
        output = root / "drafts/context-dictionary.json"
        if args.verify:
            require(parse(read_bytes(output)) == dictionary, "DICTIONARY_SOURCE_OR_PLAN_MISMATCH")
        if args.write:
            write_private(output, dictionary)
        print(json.dumps({**summary(dictionary), "written": args.write, "verified": args.verify}, ensure_ascii=False))
    except PilotError as error:
        parser.exit(2, str(error) + "\n")
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        parser.exit(2, "INVALID_OR_UNAVAILABLE_CONTEXT_DICTIONARY_INPUT\n")


if __name__ == "__main__":
    main()
