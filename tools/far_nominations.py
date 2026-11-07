"""Ask the nominator AI for Far challenge candidates in one frozen transcript, freeze them and write a manifest.

The nominator sees only the transcript. Its reasons and expected context IDs stay in nominations.json and are never
given to the labellers; the manifest carries the target IDs only. Paid OpenAI call; run it yourself.

/opt/anaconda3/envs/oops-analysis/bin/python tools/far_nominations.py TRANSCRIPT NEW_OUT_DIR \
    --video-id 2001 --cluster-id youtube-ID --label-version far-challenge-20261107
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import time
from pathlib import Path

from ai_labels import _json_bytes, _write_once, instruction_body, openai_model, parse_output

ROOT = Path(__file__).resolve().parents[1]
INSTRUCTIONS = ROOT / "docs/experiments/label-instructions/Far-nomination-v1.md"
MAX_NOMINATIONS = 20


def check_nominations(doc: dict, segments: list[dict], max_count: int) -> list[str]:
    errors = []
    if doc.get("role") != "FAR_NOMINATOR" or not doc.get("annotatorId") or doc.get("aiDraft") is not True:
        errors.append("NOMINATION_HEADER")
    nominations = doc.get("nominations", [])
    if len(nominations) > max_count:
        errors.append("NOMINATION_OVER_BUDGET")
    known, seen = {s["id"] for s in segments}, set()
    for n in nominations:
        target, context = n.get("targetSegmentId"), n.get("expectedContextSegmentIds") or []
        if target in seen:
            errors.append(f"NOMINATION_DUPLICATE:{target}")
        seen.add(target)
        if target not in known or any(c not in known for c in context):
            errors.append(f"NOMINATION_UNKNOWN_ID:{target}")
        if target in context:
            errors.append(f"NOMINATION_SELF_CONTEXT:{target}")
        if not context:
            errors.append(f"NOMINATION_NO_CONTEXT:{target}")
        if not str(n.get("reason") or "").strip():
            errors.append(f"NOMINATION_REASON:{target}")
    return errors


def nominate(transcript: Path, video_id: int, cluster_id: str, label_version: str, instructions: Path, out: Path,
             model, settings: dict) -> dict:
    out.mkdir()
    (out / "raw").mkdir()
    segments = json.loads(transcript.read_text(encoding="utf-8"))["segments"]
    packet_bytes = _json_bytes({"role": "FAR_NOMINATOR", "videos": [{"videoId": video_id, "segments": segments}]})
    system = instruction_body(instructions.read_text(encoding="utf-8"))
    record = {"schemaVersion": "far-nominations-run-1", "aiDraft": True, "isGold": False, "videoId": video_id,
              "clusterId": cluster_id, "settings": settings, "maxNominations": MAX_NOMINATIONS,
              "transcriptSha256": hashlib.sha256(transcript.read_bytes()).hexdigest(),
              "instructionsFileSha256": hashlib.sha256(instructions.read_bytes()).hexdigest(),
              "packetSha256": _write_once(out / "nomination-packet.json", packet_bytes), "files": {}}
    try:
        started = time.monotonic()
        text, meta = model(system, packet_bytes.decode("utf-8"))
        _write_once(out / "raw" / "nomination.txt", text.encode("utf-8"))
        record["call"] = {"responseId": meta.get("id"), "model": meta.get("model"), "usage": meta.get("usage"),
                          "systemFingerprint": meta.get("system_fingerprint"),
                          "elapsedSec": round(time.monotonic() - started, 3)}
        doc = parse_output(text)
        if errors := check_nominations(doc, segments, MAX_NOMINATIONS):
            raise ValueError(f"Nomination check failed: {errors}")
        record["files"]["nominations.json"] = _write_once(out / "nominations.json", _json_bytes(doc))
        record["nominations"] = len(doc["nominations"])
        if not doc["nominations"]:
            record["state"] = "COMPLETE_NO_CANDIDATES"
            return record
        position = {s["id"]: n for n, s in enumerate(segments)}
        targets = sorted((n["targetSegmentId"] for n in doc["nominations"]), key=position.__getitem__)
        manifest = {"labelVersion": label_version, "videos": [
            {"videoId": video_id, "clusterId": cluster_id,
             "transcriptPath": os.path.relpath(transcript.resolve(), out.resolve()), "targets": targets}]}
        record["files"]["manifest.json"] = _write_once(out / "manifest.json", _json_bytes(manifest))
        record["state"] = "COMPLETE"
        return record
    except Exception:
        record["state"] = "FAILED"
        raise
    finally:
        _write_once(out / "run-record.json", _json_bytes(record))


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("transcript", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--video-id", type=int, required=True)
    parser.add_argument("--cluster-id", required=True)
    parser.add_argument("--label-version", required=True)
    parser.add_argument("--model", default="gpt-6-luna")
    parser.add_argument("--reasoning-effort", default="high")
    parser.add_argument("--instructions", type=Path, default=INSTRUCTIONS)
    args = parser.parse_args(argv)
    instructions = args.instructions.resolve()
    settings = {"model": args.model, "reasoningEffort": args.reasoning_effort, "responseFormat": "json_object",
                "api": "chat.completions", "instructions": str(instructions.relative_to(ROOT))}
    record = nominate(args.transcript, args.video_id, args.cluster_id, args.label_version, instructions, args.output,
                      openai_model(args.model, args.reasoning_effort), settings)
    print(json.dumps({k: record.get(k) for k in ("state", "nominations", "files")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
