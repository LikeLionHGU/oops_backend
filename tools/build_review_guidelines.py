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


def compile_guidelines(bundle, plan, source_bytes):
    require(plan.get("schemaVersion") == "review-guideline-plan-1"
            and plan.get("status") == "WORKING_REFERENCE_NOT_VALIDATED"
            and plan.get("humanValidated") is False and text(plan.get("version"), 64), "WORKING_PLAN_REQUIRED")
    cards = {c["id"]: c for i in bundle["incidents"] for c in i["cards"]}
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
        rules.append({k: m[k] for k in ("id", "axis", "channels", "condition", "normalContrast",
                                      "requiredEvidence", "missingContext", "sourceCaseIds")})
    require(covered == set(cards), "NEW_CARDS_REQUIRE_EXPLICIT_MECHANISM_MAPPING")
    payloads = {channel: [{k: v for k, v in r.items() if k not in {"channels", "sourceCaseIds"}}
                          for r in rules if channel in r["channels"]] for channel in ("SPEECH", "CAPTION")}
    require(all(len(json.dumps(p, ensure_ascii=False, separators=(",", ":"))) <= 6000
                for p in payloads.values()), "COMPACT_GUIDELINE_BUDGET_EXCEEDED")
    return {"schemaVersion": "review-guidelines-1", "version": plan["version"],
            "status": "WORKING_REFERENCE_NOT_VALIDATED", "humanValidated": False,
            "usage": "REFERENCE_ONLY_CURRENT_INPUT_EVIDENCE_REQUIRED",
            "sourceBundleSha256": hashlib.sha256(source_bytes).hexdigest(),
            "refreshOrDeleteBy": min((datetime.datetime.fromisoformat(c["refreshOrDeleteBy"])
                                      for c in bundle["collections"])).isoformat(),
            "sourceCases": [{"caseId": c["id"], "familyId": c["familyId"], "status": c["status"]}
                            for c in cards.values()], "guidelines": rules}


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
        result = compile_guidelines(bundle, plan, source_bytes)
        require(datetime.datetime.fromisoformat(result["refreshOrDeleteBy"]) >
                datetime.datetime.now(datetime.timezone.utc), "REFRESH_OR_DELETE_REQUIRED")
        output = root / "guidelines/runtime.json"
        if args.write:
            write_private(output, result)
        print(json.dumps({"status": result["status"], "version": result["version"],
                          "sourceCases": len(result["sourceCases"]), "mechanisms": len(result["guidelines"]),
                          "written": args.write, "output": str(output) if args.write else None,
                          "humanValidated": False, "trainingPerformed": False}, ensure_ascii=False))
    except PilotError as error:
        parser.exit(2, str(error) + "\n")
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        parser.exit(2, "INVALID_OR_UNAVAILABLE_GUIDELINE_INPUT\n")


if __name__ == "__main__":
    main()
