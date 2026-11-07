"""Compare the two J1 panel labellers, show the disagreements and write the final reference labels (j1-baseline-v2).

python3 tools/j1_panel.py compare BASE NEW_COMPARISON.json 5001 5002 5003
python3 tools/j1_panel.py show BASE COMPARISON.json NEW_CARDS.md
python3 tools/j1_panel.py finalize COMPARISON.json CHOICES.json|- NEW_FINAL.json
Member A is labels-<ID>, member B is labels-b-<ID>; CHOICES maps "videoId/target" to A, B or NEITHER ("-" = check skipped).
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

CHOICES = ("A", "B", "NEITHER")
WINDOW_MS = 30_000


def _decision(case: dict | None) -> str | None:
    return case["t2Decision"] if case and case["exportStatus"] == "VALID" else None


def _label(case: dict) -> dict:
    return {"contextSegmentIds": case["goldContextSegmentIds"], "evidenceSegmentIds": case["evidenceSegmentIds"]}


def compare_cases(a_cases: list[dict], b_cases: list[dict], order: dict[str, int]) -> list[dict]:
    a, b = {c["targetSegmentId"]: c for c in a_cases}, {c["targetSegmentId"]: c for c in b_cases}
    rows = []
    for target in sorted(set(a) | set(b), key=order.get):
        da, db = _decision(a.get(target)), _decision(b.get(target))
        row = {"target": target, "a": da, "b": db, "aLabel": _label(a[target]) if da else None,
               "bLabel": _label(b[target]) if db else None}
        if da is not None and da == db:
            ids = lambda key: sorted(set(row["aLabel"][key]) | set(row["bLabel"][key]), key=order.get)
            row.update(status="SILVER", silver={"decision": da, "contextSegmentIds": ids("contextSegmentIds"),
                                                "evidenceSegmentIds": ids("evidenceSegmentIds")})
        else:
            row["status"] = "DISAGREEMENT"
        rows.append(row)
    return rows


def final_labels(rows: list[dict], choices: dict[str, str] | None) -> list[dict]:
    disagreements = [r["target"] for r in rows if r["status"] == "DISAGREEMENT"]
    if choices is not None:
        if set(choices) != set(disagreements) or any(v not in CHOICES for v in choices.values()):
            raise ValueError("Choices must give A, B or NEITHER for exactly the disagreement cases")
    out = []
    for r in rows:
        empty = {"contextSegmentIds": [], "evidenceSegmentIds": []}
        if r["status"] == "SILVER":
            s = r["silver"]
            out.append({"target": r["target"], "decision": s["decision"], "source": "SILVER",
                        "contextSegmentIds": s["contextSegmentIds"], "evidenceSegmentIds": s["evidenceSegmentIds"]})
        elif choices is None:
            out.append({"target": r["target"], "decision": None, "source": "PANEL_DISAGREEMENT", **empty})
        elif choices[r["target"]] == "NEITHER":
            out.append({"target": r["target"], "decision": "UNCERTAIN", "source": "HUMAN_NEITHER", **empty})
        else:
            side = choices[r["target"]].lower()
            if r[side] is None:
                raise ValueError(f"Member {side.upper()} has no valid label for {r['target']}")
            out.append({"target": r["target"], "decision": r[side], "source": f"HUMAN_CHOSE_{side.upper()}",
                        **r[f"{side}Label"]})
    return out


def _read(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def _write_once(path: Path, value) -> str:
    text = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False, indent=1) + "\n"
    with path.open("x", encoding="utf-8") as out:
        out.write(text)
    path.chmod(0o444)
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _segments(base: Path, video: str) -> list[dict]:
    manifest_path = base / f"draw-{video}" / "manifest.json"
    manifest = _read(manifest_path)["videos"][0]
    return _read(manifest_path.parent / manifest["transcriptPath"])["segments"]


def cmd_compare(args) -> None:
    videos, inputs = {}, {}
    for v in args.videos:
        order = {s["id"]: n for n, s in enumerate(_segments(args.base, v))}
        paths = {"a": args.base / f"labels-{v}" / "diagnostic-t2-initial.json",
                 "b": args.base / f"labels-b-{v}" / "diagnostic-t2-initial.json"}
        layers = _read(args.base / f"draw-{v}" / "draw-record.json")["layers"]
        rows = compare_cases(_read(paths["a"])["cases"], _read(paths["b"])["cases"], order)
        for r in rows:
            r["layer"] = layers[r["target"]]
        videos[v] = rows
        inputs[v] = {k: hashlib.sha256(p.read_bytes()).hexdigest() for k, p in paths.items()}
    summary = {}
    for v, rows in videos.items():
        for r in rows:
            key = f"{r['layer']}:{r['status']}"
            summary[key] = summary.get(key, 0) + 1
    _write_once(args.output, {"format": "j1-panel-comparison-v1", "inputs": inputs, "summary": summary, "videos": videos})
    print(json.dumps(summary, ensure_ascii=False))


def _rationales(base: Path, folder: str, video: str, target: str) -> dict:
    t1 = next(c for c in _read(base / folder / "t1-ai-draft.json")["labels"] if c["targetSegmentId"] == target)
    t2 = next(c for c in _read(base / folder / "t2-initial.json")["labels"] if c["targetSegmentId"] == target)
    return {"t1": t1.get("t1Rationale"), "t2": t2.get("necessityRationale")}


def _clock(ms: int) -> str:
    return f"{ms // 60000:02d}:{ms // 1000 % 60:02d}"


def cmd_show(args) -> None:
    comparison = _read(args.comparison)
    lines, n = [], 0
    for v, rows in comparison["videos"].items():
        segments = _segments(args.base, v)
        by_id = {s["id"]: s for s in segments}
        for r in (r for r in rows if r["status"] == "DISAGREEMENT"):
            n += 1
            start = by_id[r["target"]]["startMs"]
            lines += [f"## {n}. {v}/{r['target']} ({_clock(start)})", ""]
            for s in segments:
                if abs(s["startMs"] - start) <= WINDOW_MS:
                    text = f"**{s['text']}**" if s["id"] == r["target"] else s["text"]
                    lines.append(f"- {_clock(s['startMs'])} {text}")
            for side, folder in (("A", f"labels-{v}"), ("B", f"labels-b-{v}")):
                why = _rationales(args.base, folder, v, r["target"]) if r[side.lower()] else {"t1": None, "t2": None}
                lines += ["", f"**{side}: {r[side.lower()] or '무효'}** — {why['t1'] or ''} / 맥락: {why['t2'] or ''}"]
            lines.append("")
    _write_once(args.output, "\n".join(lines))
    print(json.dumps({"disagreements": n}))


def cmd_finalize(args) -> None:
    comparison = _read(args.comparison)
    choices = None if str(args.choices) == "-" else _read(args.choices)
    videos = {}
    for v, rows in comparison["videos"].items():
        mine = None if choices is None else {k.split("/", 1)[1]: c for k, c in choices.items() if k.split("/", 1)[0] == v}
        videos[v] = final_labels(rows, mine)
    counts = {}
    for rows in videos.values():
        for r in rows:
            counts[r["source"]] = counts.get(r["source"], 0) + 1
    _write_once(args.output, {"format": "j1-final-labels-v1", "rules": "j1-baseline-v2",
                              "comparisonSha256": hashlib.sha256(args.comparison.read_bytes()).hexdigest(),
                              "humanCheck": "SKIPPED" if choices is None else "DONE", "counts": counts, "videos": videos})
    print(json.dumps(counts))


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="mode", required=True)
    c = sub.add_parser("compare")
    c.add_argument("base", type=Path)
    c.add_argument("output", type=Path)
    c.add_argument("videos", nargs="+")
    s = sub.add_parser("show")
    s.add_argument("base", type=Path)
    s.add_argument("comparison", type=Path)
    s.add_argument("output", type=Path)
    f = sub.add_parser("finalize")
    f.add_argument("comparison", type=Path)
    f.add_argument("choices", type=Path)
    f.add_argument("output", type=Path)
    args = parser.parse_args(argv)
    {"compare": cmd_compare, "show": cmd_show, "finalize": cmd_finalize}[args.mode](args)


if __name__ == "__main__":
    main()
