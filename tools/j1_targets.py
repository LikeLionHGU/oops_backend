"""Draw J1 targets, pick the cases for blind human review and write the review sheet (no paid calls).

python3 tools/j1_targets.py draw TRANSCRIPT NOMINATION_MANIFEST_OR_- NEW_OUT_DIR --video-id 5001 --cluster-id youtube-ID
python3 tools/j1_targets.py review DRAW_DIR LABEL_DIR NEW_REVIEW_DIR
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import random
import re
from pathlib import Path

RANDOM_RULE = "j1-random-v1"
SAMPLE_RULE = "j1-review-sample-v1"
RANDOM_TARGETS = 20
PASS_SAMPLE = 10
WINDOW_MS = 120_000
LABEL_VERSION = "j1-baseline-20261107"
WORD = re.compile(r"[0-9A-Za-z가-힣ㄱ-ㆎ]")


def _rng(transcript_sha: str, rule: str) -> random.Random:
    return random.Random(int(hashlib.sha256(f"{transcript_sha}:{rule}".encode()).hexdigest()[:16], 16))


def eligible(segments: list[dict]) -> list[dict]:
    return [s for s in segments if len(WORD.findall(s["text"])) >= 2]


def draw_random(segments: list[dict], transcript_sha: str, nominated: list[str]) -> dict:
    pool = eligible(segments)
    rng = _rng(transcript_sha, RANDOM_RULE)
    drawn = []
    for i in range(RANDOM_TARGETS):
        block = pool[i * len(pool) // RANDOM_TARGETS:(i + 1) * len(pool) // RANDOM_TARGETS]
        drawn.append(block[rng.randrange(len(block))]["id"])
    overlap = [t for t in drawn if t in set(nominated)]
    return {"rule": RANDOM_RULE, "eligible": len(pool), "excluded": len(segments) - len(pool), "drawn": drawn,
            "overlapWithNominated": overlap, "targets": [t for t in drawn if t not in set(overlap)]}


def review_sample(cases: list[dict], layers: dict[str, str], order: dict[str, int], transcript_sha: str,
                  pass_sample: int = PASS_SAMPLE) -> dict:
    reasons, random_pass = {}, []
    for c in cases:
        target = c["targetSegmentId"]
        if c["exportStatus"] != "VALID":
            reasons[target] = "AI_INVALID"
        elif c["t2Decision"] in ("REVIEW_REQUIRED", "UNCERTAIN"):
            reasons[target] = "AI_FLAGGED"
        elif layers[target] == "RISK_NOMINATED":
            reasons[target] = "RISK_NOMINATED"
        else:
            random_pass.append(target)
    random_pass.sort(key=order.get)
    picked = set(_rng(transcript_sha, SAMPLE_RULE).sample(random_pass, min(pass_sample, len(random_pass))))
    reasons.update({t: "RANDOM_PASS_SAMPLE" for t in picked})
    return {"rule": SAMPLE_RULE, "cases": [{"targetSegmentId": t, "reason": reasons[t]} for t in sorted(reasons, key=order.get)],
            "aiOnlyPass": [t for t in random_pass if t not in picked]}


def _write_once(path: Path, text: str) -> str:
    with path.open("x", encoding="utf-8") as out:
        out.write(text)
    path.chmod(0o444)
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _json(value: dict) -> str:
    return json.dumps(value, ensure_ascii=False, indent=1) + "\n"


def _clock(ms: int) -> str:
    return f"{ms // 60000:02d}:{ms // 1000 % 60:02d}"


def review_sheet(segments: list[dict], video_id: int, targets: list[str], out: Path, window_ms: int = WINDOW_MS) -> dict:
    out.mkdir()
    by_id = {s["id"]: s for s in segments}
    lines = [f"# 사람 검토 시트 — 영상 {video_id}", "",
             "각 대상에 대해 `human-labels.json`(템플릿 복사본)에 humanDecision(PASS / REVIEW_REQUIRED / UNCERTAIN), "
             "판정에 꼭 필요한 맥락 구간(contextSegmentIds), 위험 근거 구간(evidenceSegmentIds)을 적는다. "
             "아래 창 밖의 구간도 전사문 전체에서 골라 적을 수 있다.", ""]
    for target in targets:
        start = by_id[target]["startMs"]
        lines += [f"## {target} ({_clock(start)})", ""]
        for s in segments:
            if abs(s["startMs"] - start) <= window_ms:
                label = f"**{s['id']}**" if s["id"] == target else s["id"]
                lines.append(f"- {label} ({_clock(s['startMs'])}) {s['text']}")
        lines.append("")
    template = {"labelVersion": LABEL_VERSION, "role": "HUMAN_BLIND_REVIEW", "reviewerId": None, "cases": [
        {"videoId": video_id, "targetSegmentId": t, "humanDecision": None, "contextSegmentIds": [],
         "evidenceSegmentIds": [], "note": None} for t in targets]}
    return {"review-sheet.md": _write_once(out / "review-sheet.md", "\n".join(lines)),
            "human-labels-template.json": _write_once(out / "human-labels-template.json", _json(template))}


def _transcript(path: Path) -> tuple[list[dict], str]:
    return json.loads(path.read_text(encoding="utf-8"))["segments"], hashlib.sha256(path.read_bytes()).hexdigest()


def cmd_draw(args) -> None:
    segments, sha = _transcript(args.transcript)
    order = {s["id"]: n for n, s in enumerate(segments)}
    nominated = []
    if str(args.nominations) != "-":
        nominated = json.loads(args.nominations.read_text(encoding="utf-8"))["videos"][0]["targets"]
    record = draw_random(segments, sha, nominated)
    layers = {**{t: "RANDOM" for t in record["targets"]}, **{t: "RISK_NOMINATED" for t in nominated}}
    targets = sorted(layers, key=order.get)
    args.output.mkdir()
    manifest = {"labelVersion": LABEL_VERSION, "videos": [
        {"videoId": args.video_id, "clusterId": args.cluster_id,
         "transcriptPath": os.path.relpath(args.transcript.resolve(), args.output.resolve()), "targets": targets}]}
    files = {"manifest.json": _write_once(args.output / "manifest.json", _json(manifest))}
    draw = {"videoId": args.video_id, "transcriptSha256": sha, "random": record, "nominated": nominated,
            "layers": {t: layers[t] for t in targets}}
    files["draw-record.json"] = _write_once(args.output / "draw-record.json", _json(draw))
    print(json.dumps({"targets": len(targets), "nominated": len(nominated), "random": len(record["targets"]),
                      "overlap": len(record["overlapWithNominated"]), "files": files}))


def cmd_review(args) -> None:
    draw = json.loads((args.draw / "draw-record.json").read_text(encoding="utf-8"))
    manifest = json.loads((args.draw / "manifest.json").read_text(encoding="utf-8"))["videos"][0]
    segments, sha = _transcript(args.draw / manifest["transcriptPath"])
    if sha != draw["transcriptSha256"]:
        raise ValueError("Transcript differs from the one used for the draw")
    diagnostic = json.loads((args.labels / "diagnostic-t2-initial.json").read_text(encoding="utf-8"))
    order = {s["id"]: n for n, s in enumerate(segments)}
    sample = review_sample(diagnostic["cases"], draw["layers"], order, sha)
    files = review_sheet(segments, manifest["videoId"], [c["targetSegmentId"] for c in sample["cases"]], args.output)
    files["review-selection.json"] = _write_once(args.output / "review-selection.json", _json(sample))
    print(json.dumps({"review": len(sample["cases"]), "aiOnlyPass": len(sample["aiOnlyPass"]), "files": files}))


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="mode", required=True)
    d = sub.add_parser("draw")
    d.add_argument("transcript", type=Path)
    d.add_argument("nominations", type=Path)
    d.add_argument("output", type=Path)
    d.add_argument("--video-id", type=int, required=True)
    d.add_argument("--cluster-id", required=True)
    r = sub.add_parser("review")
    r.add_argument("draw", type=Path)
    r.add_argument("labels", type=Path)
    r.add_argument("output", type=Path)
    args = parser.parse_args(argv)
    cmd_draw(args) if args.mode == "draw" else cmd_review(args)


if __name__ == "__main__":
    main()
