"""Casebook criticism reasons for the reason-vector experiment.

python3 tools/casebook_reasons.py items RUNTIME.json INCIDENTS_DIR NEW_OUT_DIR [--quote-overrides FILE]
/opt/anaconda3/envs/oops-analysis/bin/python tools/casebook_reasons.py draft ITEMS.json NEW_OUT_DIR \
    --instructions docs/experiments/label-instructions/Casebook-reason-v1.md [--model gpt-6.1-sol] [--max-calls 40]
python3 tools/casebook_reasons.py show ITEMS.json DRAFT.json NEW_CARDS.md
python3 tools/casebook_reasons.py finalize ITEMS.json DRAFT.json CORRECTIONS.json|- NEW_REASONS.json
draft is a paid OpenAI call (run it yourself); it covers only statements that are runtime examples.
Quotes come from knownPoint.text unless --quote-overrides replaces them; statements without a quote are listed as excluded.
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

from ai_labels import USD_PER_1M, Model, instruction_body, parse_output

FIRST_VIDEO_ID = 6001
QUOTE = re.compile(r"‘([^’]{2,}?)’|'([^']{2,}?)'|“([^”]{2,}?)”|\"([^\"]{2,}?)\"")


def extract_quotes(text: str) -> list[str]:
    out: list[str] = []
    for match in QUOTE.finditer(text or ""):
        quote = next(g for g in match.groups() if g).strip()
        if len(quote) >= 2 and quote not in out:
            out.append(quote)
    return out


def build_items(incidents: list[dict], runtime: dict) -> list[dict]:
    runtime_ids = {e["id"] for e in runtime.get("examples", [])}
    items = []
    for inc in incidents:
        for s in inc["statements"]:
            card = s["legacyCard"]
            known = card["knownPoint"]["text"]
            items.append({"statementId": s["id"], "familyId": inc["incidentId"], "mechanismIds": list(s["mechanismIds"]),
                          "inRuntime": s["id"] in runtime_ids,
                          "sourceText": "\n".join([known, *card["controversy"]["boundaries"]]),
                          "quotes": extract_quotes(known), "quoteSource": "EXTRACTED"})
    return sorted(items, key=lambda i: i["statementId"])


def apply_quote_overrides(items: list[dict], overrides: dict[str, list[str]]) -> list[dict]:
    known = {i["statementId"] for i in items}
    unknown = sorted(set(overrides) - known)
    if unknown:
        raise ValueError(f"Overrides name unknown statements: {unknown}")
    return [{**i, "quotes": list(overrides[i["statementId"]]), "quoteSource": "HUMAN_OVERRIDE"}
            if i["statementId"] in overrides else i for i in items]


def _write_once(path: Path, value) -> None:
    with path.open("x", encoding="utf-8") as out:
        json.dump(value, out, ensure_ascii=False, indent=1)
    path.chmod(0o444)


def write_items(items: list[dict], out: Path) -> None:
    out.mkdir()
    (out / "transcripts").mkdir()
    kept = [i for i in items if i["quotes"]]
    families, targets = {}, []
    for n, item in enumerate(kept):
        video = FIRST_VIDEO_ID + n
        families[str(video)] = item["familyId"]
        segments = [{"id": f"stt-index-{i}", "startMs": i * 3000, "endMs": i * 3000 + 2500, "text": q}
                    for i, q in enumerate(item["quotes"])]
        _write_once(out / "transcripts" / f"{video}.json", {"segments": segments})
        targets += [{"videoId": video, "targetId": s["id"], "statementId": item["statementId"]} for s in segments]
    _write_once(out / "casebook-items-v1.json", {
        "format": "casebook-items-v1", "items": items,
        "excluded": [{"statementId": i["statementId"], "reason": "NO_QUOTE"} for i in items if not i["quotes"]]})
    _write_once(out / "families.json", families)
    _write_once(out / "targets.json", targets)


REASON_MIN, REASON_MAX = 40, 200


def check_reason(doc: dict) -> list[str]:
    reason = doc.get("reason")
    if not isinstance(reason, str):
        return ["reason must be a string"]
    if not REASON_MIN <= len(reason.strip()) <= REASON_MAX:
        return [f"reason must be {REASON_MIN}-{REASON_MAX} characters"]
    return []


def draft(items: list[dict], model: Model, system: str, out: Path, max_calls: int) -> dict:
    todo = [i for i in items if i["inRuntime"]]
    if len(todo) > max_calls:
        raise RuntimeError(f"{len(todo)} calls needed, cap is {max_calls}")
    out.mkdir()
    results, usage = {}, []
    for item in todo:
        text, meta = model(system, json.dumps({"statementId": item["statementId"], "sourceText": item["sourceText"]},
                                              ensure_ascii=False))
        usage.append(meta.get("usage") or {})
        try:
            doc = parse_output(text)
            results[item["statementId"]] = {"reason": doc.get("reason"), "errors": check_reason(doc)}
        except ValueError as error:
            results[item["statementId"]] = {"reason": None, "errors": [str(error)]}
    prompt = sum(u.get("prompt_tokens", 0) for u in usage)
    output = sum(u.get("completion_tokens", 0) for u in usage)
    _write_once(out / "reasons-draft.json", results)
    _write_once(out / "run-record.json", {"calls": len(todo), "promptTokens": prompt, "outputTokens": output,
                                          "estimatedUsd": round((prompt * USD_PER_1M["input"] + output * USD_PER_1M["output"]) / 1e6, 4)})
    return results


def finalize(items: list[dict], drafted: dict, corrections: dict | None) -> dict:
    corrections = corrections or {}
    unknown = sorted(set(corrections) - set(drafted))
    if unknown:
        raise ValueError(f"Corrections name statements without a draft: {unknown}")
    reasons = {}
    for sid, d in drafted.items():
        if sid in corrections:
            errors = check_reason({"reason": corrections[sid]})
            if errors:
                raise ValueError(f"{sid}: {errors}")
            reasons[sid] = {"reason": corrections[sid].strip(), "source": "HUMAN_EDITED"}
        elif d["errors"]:
            raise ValueError(f"{sid} draft has errors and no correction: {d['errors']}")
        else:
            reasons[sid] = {"reason": d["reason"].strip(), "source": "DRAFT_CONFIRMED"}
    expected = {i["statementId"] for i in items if i["inRuntime"]}
    if set(reasons) != expected:
        raise ValueError("Reasons must cover exactly the runtime statements")
    return {"format": "casebook-reasons-v1", "reasons": reasons}


def _read(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def cmd_items(args) -> None:
    incidents = [_read(p) for p in sorted(args.incidents.glob("*.json"))]
    items = build_items(incidents, _read(args.runtime))
    if args.quote_overrides:
        items = apply_quote_overrides(items, _read(args.quote_overrides))
    write_items(items, args.output)
    print(json.dumps({"statements": len(items), "withQuotes": sum(bool(i["quotes"]) for i in items),
                      "inRuntime": sum(i["inRuntime"] for i in items)}))


def cmd_draft(args) -> None:
    from ai_labels import openai_model
    items = _read(args.items)["items"]
    system = instruction_body(args.instructions.read_text(encoding="utf-8"))
    results = draft(items, openai_model(args.model, args.reasoning_effort), system, args.output, args.max_calls)
    print(json.dumps({"drafted": len(results), "withErrors": sum(bool(r["errors"]) for r in results.values())}))


def cmd_show(args) -> None:
    items = {i["statementId"]: i for i in _read(args.items)["items"]}
    lines = []
    for n, (sid, d) in enumerate(_read(args.draft).items(), 1):
        quotes = " / ".join(items[sid]["quotes"]) or "(인용 없음)"
        lines += [f"{n}. {sid}", f"   인용: {quotes}", f"   이유: {d['reason']}"]
        if d["errors"]:
            lines.append(f"   오류: {d['errors']}")
    with args.output.open("x", encoding="utf-8") as out:
        out.write("\n".join(lines) + "\n")


def cmd_finalize(args) -> None:
    corrections = None if str(args.corrections) == "-" else _read(args.corrections)
    result = finalize(_read(args.items)["items"], _read(args.draft), corrections)
    _write_once(args.output, result)
    print(json.dumps({s: sum(r["source"] == s for r in result["reasons"].values()) for s in ("DRAFT_CONFIRMED", "HUMAN_EDITED")}))


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="mode", required=True)
    i = sub.add_parser("items")
    i.add_argument("runtime", type=Path)
    i.add_argument("incidents", type=Path)
    i.add_argument("output", type=Path)
    i.add_argument("--quote-overrides", type=Path, help='{"statementId": ["quote", ...]}; [] drops the statement')
    d = sub.add_parser("draft")
    d.add_argument("items", type=Path)
    d.add_argument("output", type=Path)
    d.add_argument("--instructions", type=Path, required=True)
    d.add_argument("--model", default="gpt-6.1-sol")
    d.add_argument("--reasoning-effort", default="high")
    d.add_argument("--max-calls", type=int, default=40)
    sh = sub.add_parser("show")
    sh.add_argument("items", type=Path)
    sh.add_argument("draft", type=Path)
    sh.add_argument("output", type=Path)
    f = sub.add_parser("finalize")
    f.add_argument("items", type=Path)
    f.add_argument("draft", type=Path)
    f.add_argument("corrections", type=Path)
    f.add_argument("output", type=Path)
    args = parser.parse_args(argv)
    {"items": cmd_items, "draft": cmd_draft, "show": cmd_show, "finalize": cmd_finalize}[args.mode](args)


if __name__ == "__main__":
    main()
