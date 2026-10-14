"""Explicit shared definitions, never implicitly regenerated during a build."""
import copy
import json
from pathlib import Path
from review_context_pilot import require

RELATIVE_PATH = "definitions/build.json"


def extract(cards, gplan, dplan, catalog):
    definition = {
        "schemaVersion": "incident-build-definition-1",
        "cards": {k: copy.deepcopy(v) for k, v in cards.items() if k != "incidents"},
        "guidelines": copy.deepcopy(gplan),
        "dictionary": {k: copy.deepcopy(v) for k, v in dplan.items() if k != "cards"},
        "catalog": {k: copy.deepcopy(v) for k, v in catalog.items() if k != "incidents"},
        "incidentOrder": [i["familyId"] for i in cards["incidents"]],
        "statementOrder": [c["id"] for i in cards["incidents"] for c in i["cards"]],
        "mechanismCaseOrder": {m["id"]: list(m.get("sourceCaseIds", [])) for m in gplan["mechanisms"]},
    }
    for m in definition["guidelines"]["mechanisms"]:
        m["sourceCaseIds"] = []
    return definition


def load(root):
    path = Path(root) / RELATIVE_PATH
    require(path.is_file() and not path.is_symlink(), "BUILD_DEFINITION_REQUIRED: run explicit --emit-definition migration")
    value = json.loads(path.read_text(encoding="utf-8"))
    require(value.get("schemaVersion") == "incident-build-definition-1"
            and set(value) == {"schemaVersion", "cards", "guidelines", "dictionary", "catalog", "incidentOrder", "statementOrder", "mechanismCaseOrder"},
            "BUILD_DEFINITION_INVALID")
    require("incidents" not in value["cards"] and "cards" not in value["dictionary"]
            and "incidents" not in value["catalog"]
            and all(m.get("sourceCaseIds") == [] for m in value["guidelines"]["mechanisms"]),
            "BUILD_DEFINITION_MUST_NOT_CONTAIN_CASE_DATA")
    for key in ("incidentOrder", "statementOrder"):
        require(isinstance(value[key], list) and all(isinstance(x, str) for x in value[key])
                and len(value[key]) == len(set(value[key])), "BUILD_DEFINITION_ORDER_INVALID")
    ids = [m["id"] for m in value["guidelines"]["mechanisms"]]
    require(len(ids) == len(set(ids)), "DUPLICATE_MECHANISM")
    require(set(value['mechanismCaseOrder']) == set(ids)
            and all(isinstance(v,list) and all(isinstance(x,str) for x in v) and len(v)==len(set(v))
                    for v in value['mechanismCaseOrder'].values()), "MECHANISM_ORDER_INVALID")
    return value


def emit(root):
    """One-time, explicit migration. Existing definitions are never overwritten."""
    root = Path(root)
    def old(name):
        return json.loads((root / name).read_text(encoding="utf-8"))
    value = extract(old("drafts/cards.json"), old("guidelines/plan.json"),
                    old("research/context-dictionary-plan.json"), old("catalog.json"))
    path = root / RELATIVE_PATH
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("x", encoding="utf-8") as file:
        json.dump(value, file, ensure_ascii=False, indent=2)
        file.write("\n")
    return path
