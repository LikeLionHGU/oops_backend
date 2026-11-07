"""Casebook criticism reasons for the reason-vector experiment.

python3 tools/casebook_reasons.py items RUNTIME.json INCIDENTS_DIR NEW_OUT_DIR
Quotes come from knownPoint.text unless --quote-overrides replaces them; statements without a quote are listed as excluded.
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

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


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="mode", required=True)
    i = sub.add_parser("items")
    i.add_argument("runtime", type=Path)
    i.add_argument("incidents", type=Path)
    i.add_argument("output", type=Path)
    i.add_argument("--quote-overrides", type=Path, help='{"statementId": ["quote", ...]}; [] drops the statement')
    args = parser.parse_args(argv)
    {"items": cmd_items}[args.mode](args)


if __name__ == "__main__":
    main()
