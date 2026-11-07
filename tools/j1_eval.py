"""Score the J1 service runs against the frozen v2 reference labels (j1-baseline-v1 metrics).

python3 tools/j1_eval.py EXPERIMENT_DIR NEW_OUTPUT.json
Reads final-labels-v2.json, panel-comparison.json (layers) and service/main-{t0,a0t,a4t}-run-v2/results.jsonl.
Results are agreement with an AI-panel reference with a light human check, not accuracy against human ground truth.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import random
from pathlib import Path

CONDITIONS = {"T0": "main-t0-run-v2", "A0-T": "main-a0t-run-v2", "A4-T": "main-a4t-run-v2"}
PAIRS = [("A0-T", "T0"), ("A4-T", "A0-T")]
RESAMPLES = 2000
SEED = 20261107
GOLD = {"REVIEW_REQUIRED": "RISK", "PASS": "NO_RISK"}


def server_class(state: str | None) -> str:
    if state == "REVIEW_REQUIRED":
        return "RISK"
    if state in ("PASS", "NO_CANDIDATE"):
        return "NO_RISK"
    if state == "UNCERTAIN":
        return "ABSTAIN"
    return "NOT_EVALUATED"


def _rate(hit: int, total: int, empty: str) -> dict:
    return {"value": hit / total, "count": f"{hit}/{total}"} if total else {"value": None, "count": empty}


def _accuracy(case: dict) -> float:
    return sum(server_class(s) == case["gold"] for s in case["runs"]) / len(case["runs"])


def _cluster_accuracy(cases: list[dict]) -> float | None:
    videos = {}
    for c in cases:
        videos.setdefault(c["video"], []).append(_accuracy(c))
    return sum(sum(v) / len(v) for v in videos.values()) / len(videos) if videos else None


def group_metrics(cases: list[dict]) -> dict:
    risk = [server_class(s) for c in cases if c["gold"] == "RISK" for s in c["runs"]]
    safe = [server_class(s) for c in cases if c["gold"] == "NO_RISK" for s in c["runs"]]
    every = [(server_class(s), c["gold"]) for c in cases for s in c["runs"]]
    decided = [(k, g) for k, g in every if k in ("RISK", "NO_RISK")]
    return {"cases": {"RISK": sum(c["gold"] == "RISK" for c in cases), "NO_RISK": sum(c["gold"] == "NO_RISK" for c in cases)},
            "missRate": _rate(risk.count("NO_RISK"), len(risk), "N/A (0 RISK runs)"),
            "falseAlarmRate": _rate(safe.count("RISK"), len(safe), "N/A (0 NO_RISK runs)"),
            "abstainRate": _rate(sum(k == "ABSTAIN" for k, _ in every), len(every), "N/A (0 runs)"),
            "notEvaluatedRate": _rate(sum(k == "NOT_EVALUATED" for k, _ in every), len(every), "N/A (0 runs)"),
            "coverage": _rate(len(decided), len(every), "N/A (0 runs)"),
            "selectiveAccuracy": _rate(sum(k == g for k, g in decided), len(decided), "N/A (0 decided runs)"),
            "caseWeightedAccuracy": sum(map(_accuracy, cases)) / len(cases) if cases else None,
            "clusterWeightedAccuracy": _cluster_accuracy(cases)}


def bootstrap(by_condition: dict[str, list[dict]], pairs: list[tuple[str, str]], resamples: int = RESAMPLES,
              seed: int = SEED) -> dict:
    rng = random.Random(seed)
    videos = sorted({c["video"] for cases in by_condition.values() for c in cases})
    samples = {k: [] for k in list(by_condition) + [f"{a}-{b}" for a, b in pairs]}
    for _ in range(resamples):
        pick = [rng.choice(videos) for _ in videos]
        value = {}
        for name, cases in by_condition.items():
            drawn = [dict(c, video=f"{v}#{n}") for n, v in enumerate(pick) for c in cases if c["video"] == v]
            value[name] = _cluster_accuracy(drawn)
            samples[name].append(value[name])
        for a, b in pairs:
            samples[f"{a}-{b}"].append(value[a] - value[b])
    cut = lambda xs: [sorted(xs)[int(0.025 * (len(xs) - 1))], sorted(xs)[int(0.975 * (len(xs) - 1))]]
    return {k: [round(x, 4) for x in cut(v)] for k, v in samples.items()}


def _majority_right(case: dict) -> bool:
    return _accuracy(case) > 0.5


def error_breakdown(a0: dict, a4: dict) -> dict:
    out, counts = [], {"MISSING_CONTEXT": 0, "JUDGMENT_ERROR": 0}
    for key, case in a0.items():
        if _majority_right(case):
            continue
        kind = "MISSING_CONTEXT" if _majority_right(a4[key]) else "JUDGMENT_ERROR"
        counts[kind] += 1
        out.append({"case": "/".join(key), "gold": case["gold"], "kind": kind, "a0Runs": case["runs"], "a4Runs": a4[key]["runs"]})
    return {"counts": counts, "cases": out}


def _read(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def load(base: Path) -> tuple[dict, dict, dict]:
    final = _read(base / "final-labels-v2.json")
    layers = {(v, r["target"]): r["layer"] for v, rows in _read(base / "panel-comparison.json")["videos"].items() for r in rows}
    labels = {(v, r["target"]): r["decision"] for v, rows in final["videos"].items() for r in rows}
    states = {}
    for name, folder in CONDITIONS.items():
        per = {}
        for line in (base / "service" / folder / "results.jsonl").read_text(encoding="utf-8").splitlines():
            r = json.loads(line)
            per.setdefault((str(r["videoId"]), r["targetId"]), {})[r["repeat"]] = r["targetState"]
        states[name] = per
    return labels, layers, states


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("base", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args(argv)
    labels, layers, states = load(args.base)
    scored = {k: GOLD[d] for k, d in labels.items() if d in GOLD}
    excluded = {k: d for k, d in labels.items() if d not in GOLD}
    tables = {}
    for name, per in states.items():
        missing = [k for k in labels if len(per.get(k, {})) != 3]
        if missing:
            raise ValueError(f"{name}: incomplete runs for {missing[:5]}")
        tables[name] = {k: {"video": k[0], "gold": g, "runs": [per[k][r] for r in range(3)]} for k, g in scored.items()}
    groups = {"RISK_NOMINATED": lambda k: layers[k] == "RISK_NOMINATED", "RANDOM": lambda k: layers[k] == "RANDOM",
              "POOLED_RISK_OVERSAMPLED": lambda k: True}
    summary = {g: {name: group_metrics([c for k, c in t.items() if f(k)]) for name, t in tables.items()} for g, f in groups.items()}
    ci = {g: bootstrap({name: [c for k, c in t.items() if f(k)] for name, t in tables.items()}, PAIRS) for g, f in groups.items()}
    inputs = {name: hashlib.sha256((args.base / "service" / folder / "results.jsonl").read_bytes()).hexdigest()
              for name, folder in CONDITIONS.items()}
    inputs["final-labels-v2.json"] = hashlib.sha256((args.base / "final-labels-v2.json").read_bytes()).hexdigest()
    output = {"format": "j1-eval-v1", "inputs": inputs, "excludedFromPrimary": {"/".join(k): d for k, d in excluded.items()},
              "summary": summary, "bootstrapClusterWeightedAccuracy95": ci,
              "errorBreakdownA0": error_breakdown(tables["A0-T"], tables["A4-T"]),
              "note": "agreement with an AI-panel reference (silver) plus a light human check; POOLED is risk-oversampled; "
                      "only 3 video clusters, so bootstrap intervals are coarse"}
    with args.output.open("x", encoding="utf-8") as out:
        json.dump(output, out, ensure_ascii=False, indent=1)
    args.output.chmod(0o444)
    print(json.dumps({g: {n: {k: m[k]["count"] for k in ("missRate", "falseAlarmRate", "abstainRate")} for n, m in s.items()}
                      for g, s in summary.items()}, ensure_ascii=False))


if __name__ == "__main__":
    main()
