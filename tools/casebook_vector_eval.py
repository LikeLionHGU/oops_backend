"""Score the casebook reason-vector experiment: statement flag rate and J1 random-layer false alarms per condition.

python3 tools/casebook_vector_eval.py ITEMS_DIR J1_BASE NEW_OUT.json \
    --run NONE=RUN_DIR --run KEYWORD=RUN_DIR --run VECTOR=RUN_DIR
ITEMS_DIR holds casebook-items-v1.json and targets.json; J1_BASE holds final-labels-v2.json and panel-comparison.json.
A statement counts as flagged in a repeat when any of its quote targets is REVIEW_REQUIRED.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


def _rate(hit: int, total: int) -> dict:
    return {"count": f"{hit}/{total}", "value": hit / total} if total else {"count": "N/A (0 runs)", "value": None}


def statement_flag_rate(records: list[dict], targets: list[dict], mechanisms: dict[str, list[str]]) -> dict:
    owner = {(t["videoId"], t["targetId"]): t["statementId"] for t in targets}
    flagged: dict[tuple[str, int], bool] = {}
    for r in records:
        sid = owner.get((r["videoId"], r["targetId"]))
        if sid is None:
            continue
        key = (sid, r["repeat"])
        flagged[key] = flagged.get(key, False) or r["targetState"] == "REVIEW_REQUIRED"
    groups: dict[str, list[bool]] = {}
    for (sid, _), value in flagged.items():
        groups.setdefault(sorted(mechanisms[sid])[0], []).append(value)
    return {**_rate(sum(flagged.values()), len(flagged)),
            "byMechanism": {m: _rate(sum(v), len(v)) for m, v in sorted(groups.items())}}


def false_alarm_rate(records: list[dict], random_pass: set[str]) -> dict:
    runs = [r for r in records if f"{r['videoId']}/{r['targetId']}" in random_pass]
    return _rate(sum(r["targetState"] == "REVIEW_REQUIRED" for r in runs), len(runs))


def _read(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def random_pass_targets(j1: Path) -> set[str]:
    layers = {(v, r["target"]): r["layer"] for v, rows in _read(j1 / "panel-comparison.json")["videos"].items() for r in rows}
    return {f"{v}/{r['target']}" for v, rows in _read(j1 / "final-labels-v2.json")["videos"].items() for r in rows
            if r["decision"] == "PASS" and layers[(v, r["target"])] == "RANDOM"}


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("items_dir", type=Path)
    parser.add_argument("j1", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--run", action="append", required=True, help="CONDITION=RUN_DIR")
    args = parser.parse_args(argv)
    targets = _read(args.items_dir / "targets.json")
    mechanisms = {i["statementId"]: i["mechanismIds"] for i in _read(args.items_dir / "casebook-items-v1.json")["items"]}
    random_pass = random_pass_targets(args.j1)
    conditions, inputs = {}, {}
    for spec in args.run:
        name, folder = spec.split("=", 1)
        path = Path(folder) / "results.jsonl"
        records = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
        inputs[name] = hashlib.sha256(path.read_bytes()).hexdigest()
        conditions[name] = {"statementFlagRate": statement_flag_rate(records, targets, mechanisms),
                            "falseAlarmRate": false_alarm_rate(records, random_pass)}
    with args.output.open("x", encoding="utf-8") as out:
        json.dump({"format": "casebook-vector-eval-v1", "inputs": inputs, "conditions": conditions,
                   "note": "casebook quotes come from articles, not original video; casebook entries are unreviewed drafts"},
                  out, ensure_ascii=False, indent=1)
    args.output.chmod(0o444)
    print(json.dumps({n: {k: c[k]["count"] for k in c} for n, c in conditions.items()}, ensure_ascii=False))


if __name__ == "__main__":
    main()
