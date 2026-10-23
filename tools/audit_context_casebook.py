"""Free inventory and freeze audit. No models, network, runtime edits or label promotion."""
import argparse
from collections import Counter
import json
from pathlib import Path

from build_context_assets import canonical
from build_context_units import digest, linked_lines
from build_review_guidelines import write_private
from review_context_pilot import parse, read_bytes, require


def inventory(runtime, units, incidents, root):
    unit_index = {u["id"]: u for u in units}
    require(len(unit_index) == len(units), "DUPLICATE_INVENTORY_UNIT")
    statements = {s["id"]: (i, s) for i in incidents for s in i["statements"]}
    rows = []
    for example in runtime["examples"]:
        uid = example["id"]
        unit = unit_index.get(uid)
        legacy = example["retrievalView"]["version"] == "frozen-legacy-lexical-1"
        require((unit is None) == legacy, "INVENTORY_MIGRATION_MISMATCH")
        if unit:
            lines = linked_lines(root, unit)
            kind = unit["source"]["kind"]
            source = parse(read_bytes(root / unit["source"]["path"]))
            approval = source.get("developmentReview", {})
            row = {"id": uid, "migrationStatus": "CONTEXT_UNIT_DRAFT",
                   "materialKind": "STORED_OCR" if kind == "STORED_OCR_FRAME_SNAPSHOT" else "STORED_TRANSCRIPT",
                   "sourceLink": "HASH_AND_SELECTED_LINES_VERIFIED_NOT_AUDIO_VERIFIED",
                   "sourceKind": kind, "linkedLineCount": len(lines),
                   "reviewBasis": approval.get("basis", "AUTHORED_BOUNDARY_NO_MACHINE_APPROVAL_RECORD"),
                   "developmentDecision": unit.get("developmentDecision"),
                   "independentGold": False, "trainingUseAuthorized": False,
                   "contrastKind": "SYNTHETIC_DEVELOPMENT_CONTROL" if unit["normalContrasts"] else "NONE",
                   "contrastCount": len(unit["normalContrasts"]),
                   "gaps": ["INDEPENDENT_REVIEW_NOT_ESTABLISHED", "REAL_CONTRAST_LINK_NOT_ESTABLISHED"]}
        else:
            require(uid in statements, "INVENTORY_LEGACY_STATEMENT_MISSING")
            incident, statement = statements[uid]
            card = statement["legacyCard"]
            evidence = card["evidence"]
            row = {"id": uid, "migrationStatus": "LEGACY_RETAINED_NEEDS_CONTEXT_CURATION",
                   "materialKind": "REPORTED_INCIDENT_NOT_INDEPENDENTLY_VERIFIED",
                   "sourceLink": "CANONICAL_STATEMENT_ONLY_NO_VERIFIED_TRANSCRIPT_LINK",
                   "sourceKind": evidence["level"], "linkedLineCount": len(evidence.get("segments", [])),
                   "structuredSourceCount": len(evidence.get("sources", [])),
                   "reportedQuoteCount": len(evidence.get("reportedQuotes", [])),
                   "reviewBasis": card["review"]["status"], "developmentDecision": None,
                   "independentGold": False, "trainingUseAuthorized": incident["trainingUseAuthorized"],
                   "contrastKind": "NONE", "contrastCount": 0,
                   "gaps": ["CONTEXT_UNIT_MISSING", "CONTRAST_LINK_MISSING", "SOURCE_AND_APPROVAL_REVIEW_REQUIRED"]}
        row.update(familyId=example["familyId"], routingKeys=example["routingKeys"],
                   lexicalIndexPresent=bool(example["retrievalView"]["text"]),
                   searchability="INDEX_PRESENT_NOT_RETRIEVAL_SUCCESS",
                   materialSynthetic=False, runtimeExampleCanonicalSha256=digest(canonical(example)))
        rows.append(row)
    require(len(rows) == len({r["id"] for r in rows}), "DUPLICATE_INVENTORY_EXAMPLE")
    return {"schemaVersion": "context-quality-inventory-1", "providerCalls": 0,
            "independentAccuracyMeasured": False, "rows": rows,
            "summary": {"examples": len(rows), "migration": dict(Counter(r["migrationStatus"] for r in rows)),
                        "reviewBasis": dict(Counter(r["reviewBasis"] for r in rows)),
                        "withContrasts": sum(r["contrastCount"] > 0 for r in rows),
                        "lexicalIndexPresent": sum(r["lexicalIndexPresent"] for r in rows)}}


def protected_files(repo, root):
    # Freeze a superset of prompts; do not read .env, database, uploads or credentials.
    paths = list((repo / "oops-backend/src/main").rglob("*.java"))
    paths += [p for p in (repo / "oops-backend/src/main/resources").rglob("*") if p.is_file()]
    for folder in ("incidents", "context-units", "context-sources", "context-relations", "definitions", "guidelines"):
        paths += list((root / folder).glob("*.json"))
    return {str(p.relative_to(repo)): digest(read_bytes(p)) for p in sorted(set(paths))}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-root", type=Path, default=Path(__file__).resolve().parents[1] / "datasets/controversy")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--verify-freeze", type=Path)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    root = args.dataset_root.resolve()
    protected = protected_files(repo, root)
    if args.verify_freeze:
        previous = parse(read_bytes(args.verify_freeze))
        require(previous["protectedFiles"] == protected, "PROMPT_OR_CANONICAL_INPUT_FREEZE_CHANGED")
        print(json.dumps({"freezeVerified": True, "files": len(protected)}))
        return
    require(args.output is not None, "OUTPUT_REQUIRED")
    output = args.output.absolute()
    require(not output.exists() and output.parent.resolve().is_relative_to((root / "experiments").resolve()),
            "NEW_PRIVATE_AUDIT_OUTPUT_REQUIRED")
    runtime = parse(read_bytes(root / "guidelines/runtime.json"))
    units = [parse(read_bytes(p)) for p in sorted((root / "context-units").glob("*.json"))]
    incidents = [parse(read_bytes(p)) for p in sorted((root / "incidents").glob("*.json"))]
    report = inventory(runtime, units, incidents, root)
    output.mkdir(mode=0o700)
    write_private(output / "quality-inventory.json", report)
    write_private(output / "legacy-index.json", {"schemaVersion": "context-legacy-registry-1",
                  "runtimeChanged": False, "action": "METADATA_ONLY_NO_MOVE_DELETE_OR_RELABEL",
                  "entries": [r for r in report["rows"] if r["migrationStatus"].startswith("LEGACY_")]})
    write_private(output / "freeze.json", {"schemaVersion": "context-freeze-1", "protectedFiles": protected})
    print(json.dumps(report["summary"], ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
