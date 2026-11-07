"""Write the pre-discovery criticism reason for every target of a prepared experiment plan.

/opt/anaconda3/envs/oops-analysis/bin/python tools/query_reasons.py PLAN.json NEW_OUT_DIR \
    --instructions docs/experiments/label-instructions/Query-reason-v1.md [--model gpt-6-luna] [--max-calls N]
Paid OpenAI call; run it yourself. Each target is sent once with the context IDs the plan froze.
Invalid output is recorded as INVALID and API errors as ERROR; neither is retried (SDK retries off).
Each result is appended to results.jsonl as it arrives, so a crash keeps what was paid for.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from ai_labels import USD_PER_1M, Model, instruction_body, parse_output

REASON_MAX = 300


def build_input(target: dict, segments: dict[str, dict], context_ids: list[str]) -> str:
    keep = set(context_ids) | {target["id"]}
    return "\n".join(("[대상] " if sid == target["id"] else "") + seg["text"] for sid, seg in segments.items() if sid in keep)


def check_query(doc: dict) -> str:
    if "reason" not in doc:
        return "INVALID"
    reason = doc["reason"]
    if reason is None:
        return "NULL"
    if not isinstance(reason, str) or not reason.strip() or len(reason) > REASON_MAX:
        return "INVALID"
    return "OK"


def _write_once(path: Path, value) -> None:
    with path.open("x", encoding="utf-8") as out:
        json.dump(value, out, ensure_ascii=False, indent=1)
    path.chmod(0o444)


def write_reasons(targets: list[dict], transcripts: dict[int, dict[str, dict]], model: Model, system: str,
                  out: Path, max_calls: int) -> dict:
    unique = {}
    for t in targets:
        unique.setdefault(f"{t['videoId']}/{t['targetId']}", t)
    if len(unique) > max_calls:
        raise RuntimeError(f"{len(unique)} calls needed, cap is {max_calls}")
    out.mkdir()
    results, usage = {}, []
    with (out / "results.jsonl").open("x", encoding="utf-8") as log:
        for key, t in unique.items():
            segments = transcripts[t["videoId"]]
            error = None
            try:
                text, meta = model(system, build_input(segments[t["targetId"]], segments, t["contextIds"]))
            except Exception as exc:  # recorded and not retried, like INVALID output
                text, meta, error = "", {}, f"{type(exc).__name__}: {exc}"
            usage.append(meta.get("usage") or {})
            try:
                doc = parse_output(text)
            except ValueError:
                doc = {}
            status = "ERROR" if error else check_query(doc)
            results[key] = {"reason": doc["reason"].strip() if status == "OK" else None, "status": status}
            log.write(json.dumps({"key": key, **results[key], "error": error, "usage": meta.get("usage")},
                                 ensure_ascii=False) + "\n")
            log.flush()
    prompt = sum(u.get("prompt_tokens", 0) for u in usage)
    output = sum(u.get("completion_tokens", 0) for u in usage)
    _write_once(out / "query-reasons.json", results)
    _write_once(out / "run-record.json", {"calls": len(unique), "promptTokens": prompt, "outputTokens": output,
                                          "estimatedUsd": round((prompt * USD_PER_1M["input"] + output * USD_PER_1M["output"]) / 1e6, 4)})
    return results


def load_transcripts(plan: dict) -> dict[int, dict[str, dict]]:
    base = Path(plan["specPath"]).parent
    out = {}
    for t in plan["spec"]["transcripts"]:
        rows = json.loads((base / t["path"]).read_text(encoding="utf-8"))["segments"]
        out[t["videoId"]] = {r["id"]: r for r in rows}
    return out


def main(argv: list[str] | None = None) -> None:
    from ai_labels import openai_model
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("plan", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--instructions", type=Path, required=True)
    parser.add_argument("--model", default="gpt-6-luna")
    parser.add_argument("--reasoning-effort", default="high")
    parser.add_argument("--max-calls", type=int, required=True)
    args = parser.parse_args(argv)
    plan = json.loads(args.plan.read_text(encoding="utf-8"))
    system = instruction_body(args.instructions.read_text(encoding="utf-8"))
    results = write_reasons(plan["targets"], load_transcripts(plan), openai_model(args.model, args.reasoning_effort, max_retries=0),
                            system, args.output, args.max_calls)
    print(json.dumps({s: sum(r["status"] == s for r in results.values()) for s in ("OK", "NULL", "INVALID", "ERROR")}))


if __name__ == "__main__":
    main()
