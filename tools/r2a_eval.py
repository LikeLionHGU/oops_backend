"""Score R2-A structural conditions against frozen labels under r2a-structural-v1.

python3 tools/r2a_eval.py EXPERIMENT_DIR NEW_OUTPUT.json 3001 3002 3003
Reads conditions-<ID>.json, target-draw-record-<ID>.json and labels-<ID>/diagnostic-t2-initial.json per video.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

CONDITIONS = ("S0_A0", "S1_PAGE_OVERLAP", "S2_NEIGHBOR_GUARANTEE", "S3_BOUNDARY_EXPANSION")
RULES_SHA256 = "425f8b9236a7e179702d2272bb06d81b3e356653b227b8f2e4612ad07238a3a8"


def _covered(gold: list[str], context: list[str]) -> bool:
    return set(gold) <= set(context)


def _ratio(hit: int, total: int, empty: str) -> str:
    return f"{hit}/{total}" if total else empty


def _spread(values: list[int]) -> dict:
    return {"mean": round(sum(values) / len(values), 3), "max": max(values)} if values else {"mean": None, "max": None}


def summarize_group(rows: list[dict]) -> dict:
    needed = [r for r in rows if r["gold"]]
    missed = [r for r in needed if not _covered(r["gold"], r["conditions"]["S0_A0"]["withinLimit"])]
    summary = {"cases": len(rows), "contextNecessary": len(needed), "a0Missed": len(missed)}
    for c in CONDITIONS:
        cells = [r["conditions"][c] for r in rows]
        fractions = [len(set(r["gold"]) & set(r["conditions"][c]["withinLimit"])) / len(r["gold"]) for r in needed]
        summary[c] = {
            "allContextCoverageTheoretical": _ratio(sum(_covered(r["gold"], r["conditions"][c]["theoretical"]) for r in needed),
                                                    len(needed), "N/A (0 context-necessary)"),
            "allContextCoverageWithinLimit": _ratio(sum(_covered(r["gold"], r["conditions"][c]["withinLimit"]) for r in needed),
                                                    len(needed), "N/A (0 context-necessary)"),
            "recoveredFromA0Miss": _ratio(sum(_covered(r["gold"], r["conditions"][c]["withinLimit"]) for r in missed),
                                          len(missed), "N/A (0 A0-missed)"),
            "meanGoldFractionWithinLimit": round(sum(fractions) / len(fractions), 3) if fractions else None,
            "addedSegments": _spread([x["addedSegments"] for x in cells]),
            "addedCodePoints": _spread([x["addedCodePoints"] for x in cells]),
            "trimmedCases": sum(x["status"] == "TRIMMED" for x in cells),
            "s0OverLimitCases": sum(x["status"] == "S0_OVER_LIMIT" for x in cells)}
    return summary


def load_rows(base: Path, video_id: str) -> list[dict]:
    conditions = json.loads((base / f"conditions-{video_id}.json").read_text(encoding="utf-8"))
    if conditions["rulesSha256"] != RULES_SHA256:
        raise ValueError("Conditions were not computed under r2a-structural-v1")
    diagnostic = json.loads((base / f"labels-{video_id}/diagnostic-t2-initial.json").read_text(encoding="utf-8"))
    labels = {c["targetSegmentId"]: c for c in diagnostic["cases"]}
    rows = []
    for target, entry in conditions["videos"][video_id].items():
        label = labels[target]
        if label["exportStatus"] != "VALID" or entry["status"] != "PLANNED":
            raise ValueError(f"Unusable case {video_id}/{target}")
        gold = label["goldContextSegmentIds"] if label["contextNecessary"] else []
        rows.append({"videoId": int(video_id), "target": target, "stratum": entry["stratum"], "gold": gold,
                     "conditions": entry["conditions"]})
    return rows


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("base", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("videos", nargs="+")
    args = parser.parse_args(argv)
    rows = [r for v in args.videos for r in load_rows(args.base, v)]
    groups = {"EDGE": [r for r in rows if r["stratum"] == "EDGE"], "INTERIOR": [r for r in rows if r["stratum"] == "INTERIOR"]}
    groups.update({f"VIDEO_{v}": [r for r in rows if r["videoId"] == int(v)] for v in args.videos})
    groups["POOLED_BOUNDARY_OVERSAMPLED"] = rows
    inputs = {v: {name: hashlib.sha256((args.base / name).read_bytes()).hexdigest()
                  for name in (f"conditions-{v}.json", f"labels-{v}/diagnostic-t2-initial.json")} for v in args.videos}
    output = {"format": "r2a-eval-v1", "rulesSha256": RULES_SHA256, "inputs": inputs,
              "summary": {name: summarize_group(group) for name, group in groups.items()},
              "contextNecessaryCases": [{k: r[k] for k in ("videoId", "target", "stratum", "gold")} for r in rows if r["gold"]],
              "note": "POOLED is a boundary-oversampled sample (EDGE drawn at twice the INTERIOR quota), not production average"}
    with args.output.open("x", encoding="utf-8") as out:
        json.dump(output, out, ensure_ascii=False, indent=1)
    args.output.chmod(0o444)
    print(json.dumps({k: output["summary"][k]["contextNecessary"] for k in groups}))


if __name__ == "__main__":
    main()
