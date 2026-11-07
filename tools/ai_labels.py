"""Run the external-AI label drafts (T1, Topic, then T2) for one handoff directory and freeze them.

Each role is its own API call with only its instructions and packet, so the labellers stay blind to each other.
T1 is frozen first and its SHA-256 is filled into the T2 instructions. Outputs are AI drafts, never gold.
Paid OpenAI call; run it yourself with the oops-analysis environment and the root .env.

/opt/anaconda3/envs/oops-analysis/bin/python tools/ai_labels.py HANDOFF_DIR NEW_OUT_DIR \
    --manifest MANIFEST --a0 A0_PROVIDED [--model gpt-6-luna] [--reasoning-effort high]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import time
from pathlib import Path
from typing import Callable

ROOT = Path(__file__).resolve().parents[1]
DECISIONS = {"PASS", "REVIEW_REQUIRED", "UNCERTAIN"}
T1_SHA_PLACEHOLDER = "<T1 파일 SHA-256>"
USD_PER_1M = {"input": 0.10, "cached": 0.01, "output": 0.50}
Model = Callable[[str, str], tuple[str, dict]]


def instruction_body(markdown: str) -> str:
    parts = re.split(r"^---\s*$", markdown, maxsplit=1, flags=re.M)
    if len(parts) != 2:
        raise ValueError("Instructions need a '---' line before the text given to the model")
    return parts[1].strip()


def parse_output(text: str) -> dict:
    body = text.strip()
    fenced = re.fullmatch(r"```(?:json)?\s*(.*?)\s*```", body, flags=re.S)
    try:
        value = json.loads(fenced.group(1) if fenced else body)
    except json.JSONDecodeError as error:
        raise ValueError(f"Model output is not JSON: {error}") from error
    if not isinstance(value, dict):
        raise ValueError("Model output is not a JSON object")
    return value


def check_t1(t1: dict, packet: dict) -> list[str]:
    errors = []
    if t1.get("role") != "T1" or not t1.get("annotatorId") or t1.get("aiDraft") is not True \
            or t1.get("labelVersion") != packet.get("labelVersion"):
        errors.append("T1_HEADER")
    labels = t1.get("labels", [])
    if [(l.get("videoId"), l.get("targetSegmentId")) for l in labels] != \
            [(c["videoId"], c["targetSegmentId"]) for c in packet["cases"]]:
        errors.append("T1_ORDER")
    for label in labels:
        target = label.get("targetSegmentId")
        if label.get("targetDecisionT1") not in DECISIONS:
            errors.append(f"T1_DECISION:{target}")
        if not str(label.get("t1Rationale") or "").strip():
            errors.append(f"T1_RATIONALE:{target}")
    return errors


def check_topics(topics: dict, video_id: int, segments: list[dict]) -> list[str]:
    errors = []
    if topics.get("role") != "TOPIC" or not topics.get("annotatorId") or topics.get("aiDraft") is not True:
        errors.append("TOPIC_HEADER")
    position = {s["id"]: n for n, s in enumerate(segments)}
    starts = topics.get("episodeStarts", {}).get(str(video_id), [])
    if any(s not in position for s in starts):
        errors.append("TOPIC_UNKNOWN_ID")
    known = [position[s] for s in starts if s in position]
    if known != sorted(set(known)):
        errors.append("TOPIC_NOT_ASCENDING")
    if segments and segments[0]["id"] in starts:
        errors.append("TOPIC_FIRST_SEGMENT")
    if set(topics.get("episodeLabels", {}).get(str(video_id), {})) != set(starts):
        errors.append("TOPIC_LABELS_MISMATCH")
    return errors


def check_t2(t2: dict, packet: dict, t1_sha: str) -> list[str]:
    errors = []
    if t2.get("role") != "T2" or not t2.get("annotatorId") or t2.get("aiDraft") is not True:
        errors.append("T2_HEADER")
    if t2.get("t1FileSha256") != t1_sha:
        errors.append("T2_T1_SHA")
    planned = [c["targetSegmentId"] for c in packet["cases"]]
    labelled = [l.get("targetSegmentId") for l in t2.get("labels", [])]
    errors += [f"T2_MISSING:{t}" for t in planned if t not in labelled]
    errors += [f"T2_DUPLICATE:{t}" for t in sorted({t for t in labelled if labelled.count(t) > 1})]
    errors += [f"T2_UNPLANNED:{t}" for t in sorted(set(labelled) - set(planned))]
    return errors


def fill_t1_sha(instructions: str, sha: str) -> str:
    if instructions.count(T1_SHA_PLACEHOLDER) != 1:
        raise ValueError("T2 instructions need exactly one T1 SHA placeholder")
    return instructions.replace(T1_SHA_PLACEHOLDER, sha)


def _write_once(path: Path, data: bytes) -> str:
    with path.open("xb") as out:
        out.write(data)
    os.chmod(path, 0o444)
    return hashlib.sha256(data).hexdigest()


def _json_bytes(value: dict) -> bytes:
    return json.dumps(value, ensure_ascii=False, indent=1).encode("utf-8")


def _call(model: Model, step: str, system: str, packet_path: Path, out: Path, record: dict) -> dict:
    started = time.monotonic()
    text, meta = model(system, packet_path.read_text(encoding="utf-8"))
    _write_once(out / "raw" / f"{step}.txt", text.encode("utf-8"))
    record["calls"].append({"step": step, "responseId": meta.get("id"), "model": meta.get("model"),
                            "systemFingerprint": meta.get("system_fingerprint"), "usage": meta.get("usage"),
                            "elapsedSec": round(time.monotonic() - started, 3),
                            "instructionSha256": hashlib.sha256(system.encode("utf-8")).hexdigest(),
                            "packetSha256": hashlib.sha256(packet_path.read_bytes()).hexdigest()})
    return parse_output(text)


def run_labels(handoff: Path, out: Path, model: Model, settings: dict) -> dict:
    out.mkdir()
    (out / "raw").mkdir()
    read = lambda name: (handoff / name).read_text(encoding="utf-8")
    t1_packet = json.loads(read("t1-packet.json"))
    video = json.loads(read("topic-packet.json"))["videos"][0]
    record = {"schemaVersion": "ai-labels-run-1", "aiDraft": True, "isGold": False, "handoff": str(handoff),
              "settings": settings, "calls": [], "files": {}}
    try:
        t1 = _call(model, "t1", instruction_body(read("T1-instructions.md")), handoff / "t1-packet.json", out, record)
        if errors := check_t1(t1, t1_packet):
            raise ValueError(f"T1 check failed: {errors}")
        t1_sha = _write_once(out / "t1-ai-draft.json", _json_bytes(t1))
        record["files"]["t1-ai-draft.json"] = t1_sha

        topics = _call(model, "topic", instruction_body(read("Topic-instructions.md")), handoff / "topic-packet.json",
                       out, record)
        if errors := check_topics(topics, video["videoId"], video["segments"]):
            raise ValueError(f"Topic check failed: {errors}")
        record["files"]["topics-ai-draft.json"] = _write_once(out / "topics-ai-draft.json", _json_bytes(topics))

        t2_instructions = fill_t1_sha(read("T2-instructions.md"), t1_sha)
        record["files"]["T2-instructions-filled.md"] = _write_once(out / "T2-instructions-filled.md",
                                                                   t2_instructions.encode("utf-8"))
        t2 = _call(model, "t2", instruction_body(t2_instructions), handoff / "t2-packet.json", out, record)
        if errors := check_t2(t2, json.loads(read("t2-packet.json")), t1_sha):
            raise ValueError(f"T2 check failed: {errors}")
        record["files"]["t2-initial.json"] = _write_once(out / "t2-initial.json", _json_bytes(t2))
        record["state"] = "COMPLETE"
    except Exception:
        record["state"] = "FAILED"
        raise
    finally:
        usage = [c.get("usage") or {} for c in record["calls"]]
        prompt = sum(u.get("prompt_tokens", 0) for u in usage)
        cached = sum((u.get("prompt_tokens_details") or {}).get("cached_tokens", 0) for u in usage)
        output = sum(u.get("completion_tokens", 0) for u in usage)
        record["estimatedUsd"] = round(((prompt - cached) * USD_PER_1M["input"] + cached * USD_PER_1M["cached"]
                                        + output * USD_PER_1M["output"]) / 1e6, 4)
        _write_once(out / "run-record.json", _json_bytes(record))
    return record


def openai_model(name: str, reasoning_effort: str) -> Model:
    from dotenv import dotenv_values
    from openai import OpenAI

    env = dotenv_values(ROOT / ".env")
    if not env.get("OPENAI_API_KEY"):
        raise ValueError("OPENAI_API_KEY is required in the root .env")
    client = OpenAI(api_key=env["OPENAI_API_KEY"], organization=env.get("OPENAI_ORG_ID") or None,
                    project=env.get("OPENAI_PROJECT_ID") or None, timeout=600)

    def call(system: str, user: str) -> tuple[str, dict]:
        response = client.chat.completions.create(
            model=name, reasoning_effort=reasoning_effort, response_format={"type": "json_object"},
            messages=[{"role": "system", "content": system}, {"role": "user", "content": user}])
        meta = {"id": response.id, "model": response.model, "system_fingerprint": response.system_fingerprint,
                "usage": response.usage.model_dump() if response.usage else None}
        return response.choices[0].message.content or "", meta

    return call


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("handoff", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--a0", type=Path, required=True)
    parser.add_argument("--model", default="gpt-6-luna")
    parser.add_argument("--reasoning-effort", default="high")
    args = parser.parse_args(argv)
    settings = {"model": args.model, "reasoningEffort": args.reasoning_effort, "responseFormat": "json_object",
                "api": "chat.completions", "messages": "system=instruction text after '---', user=packet JSON"}
    record = run_labels(args.handoff, args.output, openai_model(args.model, args.reasoning_effort), settings)
    from label_packets import diagnose
    out = args.output
    report = diagnose(args.manifest, out / "t1-ai-draft.json", out / "t2-initial.json", out / "topics-ai-draft.json",
                      args.a0, out / "diagnostic-t2-initial.json")
    print(json.dumps({"state": record["state"], "estimatedUsd": record["estimatedUsd"],
                      "files": record["files"], "diagnosticFileErrors": report.get("fileErrors")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
