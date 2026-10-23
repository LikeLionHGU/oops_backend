"""Offline context-first asset preview. Never writes the active runtime.

Authored context is not observed evidence or an executable decision rule.
Raw statements stay in a separate evidence file, verified through existing units.
"""
import argparse
import copy
import json
from pathlib import Path

from build_context_units import ROOT, digest, linked_lines
from build_review_guidelines import write_private
from review_context_pilot import parse, read_bytes, require, text
from review_dataset_catalog import inside

FIELDS = {"situation", "target", "observedRelations", "reviewMechanism",
          "requiredConditions", "insufficientConditions", "normalBoundary", "unknowns"}


def compile_assets(profile, unit, root):
    require(set(profile) == {"schemaVersion", "unitId", "unitSha256", "status", "context"},
            "ASSET_FIELDS_INVALID")
    require(profile["schemaVersion"] == "context-asset-profile-1"
            and profile["status"] == "AUTHOR_DRAFT_NOT_LABEL_APPROVAL"
            and profile["unitId"] == unit["id"], "ASSET_DRAFT_REQUIRED")
    require(profile["unitSha256"] == digest(canonical(unit)), "ASSET_UNIT_CHANGED")
    require(unit.get("schemaVersion") in {"context-reference-unit-1", "context-reference-unit-2"}
            and unit.get("status") == "DRAFT" and unit.get("humanVerified") is False
            and unit.get("trainingUseAuthorized") is False, "ASSET_UNIT_DRAFT_REQUIRED")
    lines = linked_lines(root, unit)
    ids = {line["id"] for line in lines}
    context = profile["context"]
    require(isinstance(context, dict) and set(context) == FIELDS, "ASSET_CONTEXT_FIELDS_INVALID")
    require(all(text(context[k], 500) for k in ("situation", "target", "reviewMechanism", "normalBoundary")),
            "ASSET_CONTEXT_TEXT_REQUIRED")
    for key in ("requiredConditions", "insufficientConditions", "unknowns"):
        require(isinstance(context[key], list) and 1 <= len(context[key]) <= 8
                and all(text(s, 350) for s in context[key]), "ASSET_BOUNDARIES_REQUIRED")
    relations = context["observedRelations"]
    require(isinstance(relations, list) and 1 <= len(relations) <= 8, "ASSET_RELATIONS_REQUIRED")
    for relation in relations:
        require(isinstance(relation, dict) and set(relation) == {"description", "evidenceIds"}
                and text(relation["description"], 350), "ASSET_RELATION_INVALID")
        refs = relation["evidenceIds"]
        require(isinstance(refs, list) and refs and len(refs) == len(set(refs))
                and set(refs) <= ids, "ASSET_RELATION_EVIDENCE_REQUIRED")
    # Only this compact view is a proposed model reference; never raw quotes or labels.
    reference = {"id": unit["id"], "basis": "AUTHORED_CONTEXT_NOT_CURRENT_EVIDENCE",
                 "context": copy.deepcopy(context)}
    evidence = {"id": unit["id"], "familyId": unit["familyId"],
                "source": copy.deepcopy(unit["source"]), "segments": lines,
                "existingDevelopmentDecision": unit.get("developmentDecision"),
                "humanVerified": False, "trainingUseAuthorized": False}
    search = " ".join([context["situation"], context["target"], context["reviewMechanism"],
                       *[r["description"] for r in relations]])
    return {"reference": reference, "evidence": evidence,
            "search": {"id": unit["id"], "basis": "AUTHORED_CONTEXT_SEARCH_NOT_KEYWORD_RULE", "text": search}}


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", type=Path, required=True)
    parser.add_argument("--dataset-root", type=Path, default=ROOT)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = args.dataset_root.resolve()
    profile_path = inside(root, str(args.profile))
    profile = parse(read_bytes(profile_path))
    units = [parse(read_bytes(p)) for p in sorted((root / "context-units").glob("*.json"))]
    matches = [u for u in units if u["id"] == profile["unitId"]]
    require(len(matches) == 1, "ASSET_UNIT_REQUIRED")
    result = compile_assets(profile, matches[0], root)
    output = args.output.absolute()
    require(not output.exists() and output.parent.resolve().is_relative_to((root / "experiments").resolve()),
            "NEW_PRIVATE_ASSET_OUTPUT_REQUIRED")
    runtime = root / "guidelines/runtime.json"
    runtime_sha = digest(read_bytes(runtime))
    manifest = {"schemaVersion": "context-asset-preview-manifest-1", "activeRuntimeChanged": False,
                "modelCalled": False, "profileSha256": digest(read_bytes(profile_path)),
                "compilerSha256": digest(Path(__file__).read_bytes()), "activeRuntimeSha256": runtime_sha,
                "canonicalOutputSha256": {k: digest(canonical(v)) for k, v in result.items()}}
    output.mkdir(mode=0o700)
    for key, value in result.items():
        write_private(output / (key + ".json"), value)
    write_private(output / "manifest.json", manifest)
    require(digest(read_bytes(runtime)) == runtime_sha, "ACTIVE_RUNTIME_CHANGED")
    print(json.dumps({"output": str(output), "activeRuntimeChanged": False,
                      "referenceCodePoints": len(canonical(result["reference"]).decode())}))


if __name__ == "__main__":
    main()
