"""Type R2-B cases and split their targets under the frozen r2b-semantic-v1 rules.

python3 tools/r2b_types.py EXPERIMENT_DIR NEW_OUTPUT.json 4001 4002 4003
Reads nominations-<ID>/manifest.json, the transcript it names and labels-<ID>/diagnostic-t2-initial.json per video.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
from pathlib import Path

RULES = Path(__file__).resolve().parents[1] / "docs/experiments/retrieval-rules/r2b-semantic-v1.json"
RULES_SHA256 = "7600935c9b611eca00b4fdc9bd43c6459be08d7a5c12d8f21b6215cdea301f36"
PREVIEW_TARGET_BEFORE_MS = 180_000
PREVIEW_GOLD_AFTER_MS = 600_000
NEAR_GAP_MS = 60_000
SPLIT_MARKS = re.compile(r"(?<=[?.!…])\s+")
LENGTH_MIN_CODE_POINTS = 30
LENGTH_MIN_WORDS = 4
LENGTH_PIECE_CODE_POINTS = 30
LENGTH_MAX_PIECES = 4


def _gap(a: dict, b: dict) -> int:
    return max(0, b["startMs"] - a["endMs"], a["startMs"] - b["endMs"])


def _outside(case: dict) -> list[str]:
    a0 = set(case["a0ContextSegmentIds"])
    return [g for g in case["goldContextSegmentIds"] if g not in a0]


def classify(case: dict, segments: dict[str, dict]) -> str:
    if case["exportStatus"] != "VALID":
        return "INVALID"
    if not case["contextNecessary"]:
        return "NOT_CONTEXT_NECESSARY"
    outside = _outside(case)
    if not outside:
        return "NEAR"
    target = segments[case["targetSegmentId"]]
    golds = [segments[g] for g in outside]
    if target["startMs"] < PREVIEW_TARGET_BEFORE_MS and all(
            g["startMs"] - target["startMs"] >= PREVIEW_GOLD_AFTER_MS for g in golds):
        return "PREVIEW"
    if all(_gap(target, g) <= NEAR_GAP_MS for g in golds):
        return "A0_PAGE_BOUNDARY"
    return "LONG_RANGE_REFERENCE"


def far_direction(case: dict, segments: dict[str, dict]) -> str:
    target = segments[case["targetSegmentId"]]
    far = [segments[g] for g in _outside(case) if _gap(target, segments[g]) > NEAR_GAP_MS]
    sides = {"BEFORE" if g["startMs"] < target["startMs"] else "AFTER" for g in far}
    return "BOTH" if len(sides) == 2 else sides.pop()


def split_v2(text: str) -> tuple[list[str], str]:
    pieces = [p.strip() for p in SPLIT_MARKS.split(text) if p.strip()]
    if len(pieces) >= 2:
        return pieces, "PUNCTUATION"
    stripped = text.strip()
    words = stripped.split()
    if len(stripped) < LENGTH_MIN_CODE_POINTS or len(words) < LENGTH_MIN_WORDS:
        return [stripped], "UNSPLIT"
    k = min(LENGTH_MAX_PIECES, math.ceil(len(stripped) / LENGTH_PIECE_CODE_POINTS))
    size, extra = divmod(len(words), k)
    chunks, start = [], 0
    for n in range(k):
        end = start + size + (1 if n < extra else 0)
        chunks.append(" ".join(words[start:end]))
        start = end
    return chunks, "LENGTH"


def load_cases(base: Path, video_id: str) -> list[dict]:
    manifest_path = base / f"nominations-{video_id}/manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    transcript = manifest_path.parent / manifest["videos"][0]["transcriptPath"]
    segments = {s["id"]: s for s in json.loads(transcript.read_text(encoding="utf-8"))["segments"]}
    diagnostic = json.loads((base / f"labels-{video_id}/diagnostic-t2-initial.json").read_text(encoding="utf-8"))
    labelled = {c["targetSegmentId"]: c for c in diagnostic["cases"]}
    rows = []
    for target in manifest["videos"][0]["targets"]:
        case = labelled.get(target)
        if case is None:
            rows.append({"videoId": int(video_id), "target": target, "type": "MISSING_LABEL"})
            continue
        kind = classify(case, segments)
        pieces, split = split_v2(segments[target]["text"])
        row = {"videoId": int(video_id), "target": target, "type": kind, "split": split, "pieces": len(pieces),
               "gold": case["goldContextSegmentIds"], "outsideA0": _outside(case) if kind != "INVALID" else [],
               "h1h2Eligible": kind not in ("INVALID", "NOT_CONTEXT_NECESSARY") and len(pieces) >= 2,
               "longRangeEligible": kind == "LONG_RANGE_REFERENCE"}
        if kind == "LONG_RANGE_REFERENCE":
            row["farGoldDirection"] = far_direction(case, segments)
        rows.append(row)
    return rows


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("base", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("videos", nargs="+")
    args = parser.parse_args(argv)
    rules_sha = hashlib.sha256(RULES.read_bytes()).hexdigest()
    if rules_sha != RULES_SHA256:
        raise ValueError("Rules file is not r2b-semantic-v1 as frozen")
    rows = [r for v in args.videos for r in load_cases(args.base, v)]
    counts = {}
    for r in rows:
        counts[r["type"]] = counts.get(r["type"], 0) + 1
    output = {"format": "r2b-types-v1", "rulesSha256": rules_sha, "nominated": len(rows),
              "validLabels": sum(r["type"] not in ("INVALID", "MISSING_LABEL") for r in rows),
              "typeCounts": counts, "h1h2Eligible": sum(r.get("h1h2Eligible", False) for r in rows),
              "longRangeEligible": sum(r.get("longRangeEligible", False) for r in rows), "cases": rows,
              "note": "split LENGTH is a length proxy, not a detected utterance boundary; LONG_RANGE_REFERENCE is "
                      "distance-based and needs a separate semantic review before it is called a past re-reference"}
    with args.output.open("x", encoding="utf-8") as out:
        json.dump(output, out, ensure_ascii=False, indent=1)
    args.output.chmod(0o444)
    print(json.dumps({k: output[k] for k in ("nominated", "validLabels", "typeCounts", "h1h2Eligible",
                                             "longRangeEligible")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
