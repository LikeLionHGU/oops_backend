"""Compile a locally authored, traceable WORKING reference, not approved training data.

New cards must be explicitly assigned to a mechanism; no automatic semantic
promotion. Default is validation only. --write creates an atomic private runtime
artifact; never uploads sources, calls models or changes original approvals.
"""
import argparse
import datetime
import hashlib
import json
import os
import tempfile
from pathlib import Path

from controversy_cards import build_bundle
from review_context_pilot import PilotError, parse, read_bytes, require, text

ROOT = Path(__file__).resolve().parents[1]
DEFAULT = ROOT / "datasets/controversy"


def reference_context(card):
    """Keep source limitations and criticism scope; never turn reactions into facts."""
    context = card["context"]
    require(context["coverage"] in {"SELECTED_EXCERPTS_NOT_FULL_TRANSCRIPT",
                                    "REPORTED_CONTEXT_ONLY_NOT_VIDEO_TRANSCRIPT"}, "REFERENCE_COVERAGE_REQUIRED")
    segments = {s["id"] for s in card["evidence"]["segments"]}
    flow = []
    for step in context["sequenceInterpretations"]:
        require(step["status"] == "ASSISTANT_DRAFT_NOT_VIDEO_FACT"
                and step["segmentIds"] and set(step["segmentIds"]) <= segments
                and text(step["text"], 350), "REFERENCE_FLOW_SOURCE_REQUIRED")
        flow.append(step["text"])
    reactions = {r["id"]: r for r in card["reactions"]}
    criticism = []
    for hypothesis in card["controversy"]["reasonHypotheses"]:
        reaction = reactions[hypothesis["reactionId"]]
        if (reaction["role"] == "CRITICISM_SUPPORT"
                and reaction["usage"]["purpose"] == "CONTENT_REACTION_RESEARCH"
                and reaction["mapping"]["scope"] in {"EXACT_POINT", "SCENE_OR_FLOW"}
                and hypothesis["reactionId"] in card["controversy"]["reasonSupportReactionIds"]):
            require(hypothesis["status"] == "ASSISTANT_DRAFT_NOT_VIDEO_FACT"
                    and text(hypothesis["text"], 350), "REFERENCE_CRITICISM_HYPOTHESIS_REQUIRED")
            criticism.append(hypothesis["text"])
    result = {"sourceCaseId": card["id"], "coverage": context["coverage"],
              "flow": flow or [card["knownPoint"]["text"]],
              "criticismHypotheses": criticism[:1],
              "missingContext": context["missingInformation"]}
    require(1 <= len(result["flow"]) <= 8 and all(text(s, 350) for s in result["flow"])
            and all(text(s, 350) for s in result["criticismHypotheses"])
            and 1 <= len(result["missingContext"]) <= 8
            and all(text(s, 350) for s in result["missingContext"]), "REFERENCE_CONTEXT_LIMIT")
    return result


def prompt_rules(archive, channel):
    if archive.get("schemaVersion") == "review-guidelines-4":
        return [{k: rule[k] for k in ("id", "axis", "condition", "normalContrast", "requiredEvidence", "missingContext")}
                for rule in archive["guidelines"] if channel in rule["channels"]]
    return [{**{k: v for k, v in rule.items() if k not in {"channels", "sourceCaseIds", "referenceContexts"}},
             "referenceContexts": [{k: v for k, v in ref.items() if k != "sourceCaseId"}
                                   for ref in rule["referenceContexts"]]}
            for rule in archive["guidelines"] if channel in rule["channels"]]


def compile_guidelines(bundle, plan, source_bytes, dictionary=None):
    require(plan.get("schemaVersion") == "review-guideline-plan-1"
            and plan.get("status") == "WORKING_REFERENCE_NOT_VALIDATED"
            and plan.get("humanValidated") is False and text(plan.get("version"), 64), "WORKING_PLAN_REQUIRED")
    cards = {c["id"]: c for i in bundle["incidents"] for c in i["cards"]}
    entries = {}
    if dictionary is not None:
        require(dictionary.get("schemaVersion") == "context-dictionary-1"
                and dictionary.get("status") == "UNREVIEWED_DRAFT"
                and dictionary.get("humanApproved") is False
                and dictionary.get("runtimeEligible") is False
                and dictionary.get("trainingUseAuthorized") is False
                and dictionary.get("sourceBundleSha256") == hashlib.sha256(source_bytes).hexdigest()
                and dictionary.get("guidelinePlanSha256") == hashlib.sha256(
                    json.dumps(plan, ensure_ascii=False, sort_keys=True).encode()).hexdigest(),
                "DICTIONARY_PROVENANCE_REQUIRED")
        entries = {e["id"]: e for e in dictionary["entries"]}
        require(set(entries) == set(cards) and len(entries) == len(dictionary["entries"]),
                "DICTIONARY_CARD_COVERAGE_MISMATCH")
    mechanisms = plan.get("mechanisms")
    require(isinstance(mechanisms, list) and 1 <= len(mechanisms) <= 16, "MECHANISM_LIMIT")
    ids, covered, rules = set(), set(), []
    for m in mechanisms:
        require(text(m.get("id"), 64) and m["id"] not in ids, "DUPLICATE_MECHANISM")
        ids.add(m["id"])
        require(m.get("axis") in {"TARGET_TREATMENT", "EXPRESSION_CONTENT"}, "AXIS_REQUIRED")
        require(isinstance(m.get("channels"), list) and m["channels"] and
                set(m["channels"]) <= {"SPEECH", "CAPTION"}, "CHANNEL_REQUIRED")
        require(isinstance(m.get("sourceCaseIds"), list) and m["sourceCaseIds"] and
                len(set(m["sourceCaseIds"])) == len(m["sourceCaseIds"]) and
                set(m["sourceCaseIds"]) <= set(cards), "SOURCE_CASE_REQUIRED")
        covered.update(m["sourceCaseIds"])
        for key in ("condition", "normalContrast", "requiredEvidence", "missingContext"):
            require(text(m.get(key), 350), "SHORT_CONTEXT_FIELDS_REQUIRED")
        rule = {k: m[k] for k in ("id", "axis", "channels", "condition", "normalContrast",
                                  "requiredEvidence", "missingContext", "sourceCaseIds")}
        rule["referenceContexts"] = [reference_context(cards[cid]) for cid in m["sourceCaseIds"]]
        if dictionary is not None:
            for ref in rule["referenceContexts"]:
                entry = entries[ref["sourceCaseId"]]
                require(m["id"] in entry["taxonomy"]["mechanismIds"], "DICTIONARY_MECHANISM_MISMATCH")
                # Keep every mapped content reason, not the most popular/first comment.
                # Timing reactions require a separate current-publication check.
                ref["criticismHypotheses"] = list(dict.fromkeys(
                    u["interpretation"]["reason"] for u in entry["audienceReception"]["pointReasonUnits"]
                    if u["stage"] == "CONTENT" and u["scope"] in {"EXACT_POINT", "SCENE_OR_FLOW"}))
                ref["sourceInterpretations"] = [
                    {"statementKind": s["statementKind"], "excerpt": s["excerpt"]}
                    for s in entry["interpretationMaterial"]["sourceExtracts"]
                    if s["statementKind"] in {"AUTHOR_INTERPRETATION", "REPORTED_AUDIENCE_REACTION"}]
                require(len(ref["criticismHypotheses"]) <= 16
                        and all(text(s, 350) for s in ref["criticismHypotheses"])
                        and len(ref["sourceInterpretations"]) <= 16
                        and all(text(s["excerpt"], 350) for s in ref["sourceInterpretations"]),
                        "DICTIONARY_REFERENCE_LIMIT")
        rules.append(rule)
    require(covered == set(cards), "NEW_CARDS_REQUIRE_EXPLICIT_MECHANISM_MAPPING")
    examples = []
    if dictionary is not None:
        # Store one example per source card, outside the pattern definitions.
        for cid, card in cards.items():
            mapped = [r for r in rules if cid in r["sourceCaseIds"]]
            ref = next(ref for ref in mapped[0]["referenceContexts"] if ref["sourceCaseId"] == cid)
            examples.append({"id": cid, "familyId": card["familyId"],
                             "mechanismIds": [r["id"] for r in mapped],
                             "channels": sorted({c for r in mapped for c in r["channels"]}),
                             "context": {k: v for k, v in ref.items() if k != "sourceCaseId"}})
        rules = [{k: v for k, v in r.items() if k != "referenceContexts"} for r in rules]
    schema = "review-guidelines-4" if dictionary else "review-guidelines-2"
    payloads = {channel: prompt_rules({"schemaVersion": schema, "guidelines": rules}, channel)
                for channel in ("SPEECH", "CAPTION")}
    require(all(len(json.dumps(p, ensure_ascii=False, separators=(",", ":"))) <= 6000
                for p in payloads.values()), "COMPACT_GUIDELINE_BUDGET_EXCEEDED")
    result = {"schemaVersion": schema,
            "version": plan["version"] + ("-patterns-4" if dictionary else ""),
            "status": "WORKING_REFERENCE_NOT_VALIDATED", "humanValidated": False,
            "usage": "REFERENCE_ONLY_CURRENT_INPUT_EVIDENCE_REQUIRED",
            "sourceBundleSha256": hashlib.sha256(source_bytes).hexdigest(),
            "refreshOrDeleteBy": min((datetime.datetime.fromisoformat(c["refreshOrDeleteBy"])
                                      for c in bundle["collections"])).isoformat(),
            "sourceCases": [{"caseId": c["id"], "familyId": c["familyId"], "status": c["status"]}
                            for c in cards.values()], "guidelines": rules}
    if dictionary is not None:
        result["examples"] = examples
        result["contextDictionarySha256"] = hashlib.sha256(
            json.dumps(dictionary, ensure_ascii=False, sort_keys=True).encode()).hexdigest()
    return result


def write_private(path, data):
    path = Path(path)
    require(not path.is_symlink(), "OUTPUT_SYMLINK_REFUSED")
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=".guidelines-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w") as stream:
            json.dump(data, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-root", type=Path, default=DEFAULT)
    parser.add_argument("--private-root", type=Path, default=ROOT / "uploads/comment-collections")
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    try:
        root = args.dataset_root
        source_bytes = read_bytes(root / "drafts/cards.json")
        bundle = parse(source_bytes)
        expected = build_bundle(root, args.private_root, parse(read_bytes(root / "research/reaction-classification-plan.json")))
        require(bundle == expected, "BUNDLE_SOURCE_OR_PLAN_MISMATCH")
        plan = parse(read_bytes(root / "guidelines/plan.json"))
        # Import here: the research builder reuses this module's atomic writer.
        from build_context_dictionary import compile_dictionary
        from review_dataset_catalog import inside
        dictionary_plan = parse(read_bytes(root / "research/context-dictionary-plan.json"))
        snapshots = {}
        for config in dictionary_plan["cards"].values():
            for document in config["documents"]:
                name = document["snapshot"]
                require(name.startswith("research/source-snapshots/"), "PRIVATE_SOURCE_SNAPSHOT_PATH_REQUIRED")
                snapshots[name] = read_bytes(inside(root, name))
        dictionary = compile_dictionary(bundle, dictionary_plan, plan, source_bytes, snapshots)
        require(dictionary == parse(read_bytes(root / "drafts/context-dictionary.json")),
                "DICTIONARY_SOURCE_OR_PLAN_MISMATCH")
        result = compile_guidelines(bundle, plan, source_bytes, dictionary)
        require(datetime.datetime.fromisoformat(result["refreshOrDeleteBy"]) >
                datetime.datetime.now(datetime.timezone.utc), "REFRESH_OR_DELETE_REQUIRED")
        output = root / "guidelines/runtime.json"
        if args.write:
            metadata = {k: result[k] for k in ("version", "status", "humanValidated", "usage",
                        "sourceBundleSha256", "contextDictionarySha256", "refreshOrDeleteBy")}
            write_private(root / "guidelines/patterns.json", {**metadata, "schemaVersion": "context-patterns-1",
                "patterns": [{k: v for k, v in r.items() if k != "sourceCaseIds"} for r in result["guidelines"]]})
            write_private(root / "guidelines/context-examples.json", {**metadata, "schemaVersion": "context-examples-1",
                "examples": result["examples"]})
            # Runtime reads only this final atomic envelope, never independently replaced projections.
            write_private(output, result)
        print(json.dumps({"status": result["status"], "version": result["version"],
                          "sourceCases": len(result["sourceCases"]), "mechanisms": len(result["guidelines"]),
                          "examples": len(result["examples"]), "runtimeSchema": result["schemaVersion"],
                          "written": args.write, "output": str(output) if args.write else None,
                          "humanValidated": False, "trainingPerformed": False}, ensure_ascii=False))
    except PilotError as error:
        parser.exit(2, str(error) + "\n")
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        parser.exit(2, "INVALID_OR_UNAVAILABLE_GUIDELINE_INPUT\n")


if __name__ == "__main__":
    main()
