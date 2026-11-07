"""Choose casebook references by reason vectors and score retrieval against keyword selection and chance.

/opt/anaconda3/envs/oops-analysis/bin/python tools/reason_vectors.py select ITEMS.json REASONS.json FAMILIES.json \
    QUERY_REASONS.json NEW_OUT_DIR --revision <40-hex bge-m3 commit>
python3 tools/reason_vectors.py retrieval ITEMS.json FAMILIES.json TARGETS.json QUERY_REASONS.json \
    VECTOR_SELECTIONS.json KEYWORD_RESULTS.jsonl NEW_OUT.json
Local bge-m3 only (no API). Documents are confirmed reasons of runtime statements; a casebook query never sees
documents of its own family. Keyword selections come from repeat 0 of the KEYWORD run (discoveryReferenceIds).
"""
from __future__ import annotations

import argparse
import json
from math import comb
from pathlib import Path

K = 3
FIRST_CASEBOOK_VIDEO = 6001


def select_top(queries: dict[str, list[float]], docs: dict[str, list[float]], doc_family: dict[str, str],
               query_family: dict[str, str | None], k: int = K) -> dict[str, list[str]]:
    out = {}
    for key, family in query_family.items():
        vector = queries.get(key)
        if vector is None:
            out[key] = []
            continue
        scored = [(-sum(a * b for a, b in zip(vector, dv)), did) for did, dv in docs.items() if doc_family[did] != family]
        out[key] = [did for _, did in sorted(scored)[:k]]
    return out


def chance_hit(pool: list[str], hits: set[str], k: int = K) -> float:
    n, h = len(pool), len(hits & set(pool))
    if n <= k:
        return 1.0 if h else 0.0
    return 1 - comb(n - h, k) / comb(n, k)


def retrieval_metrics(selected: dict[str, list[str]], query_meta: dict[str, dict], doc_meta: dict[str, dict]) -> dict:
    excluded = {"NO_OTHER_FAMILY": 0, "NULL": 0, "INVALID": 0}
    hits = denominator = 0
    chance = 0.0
    by_mechanism: dict[str, dict] = {}
    for key, q in query_meta.items():
        mechanisms = set(q["mechanismIds"])
        pool = [d for d, m in doc_meta.items() if m["familyId"] != q["familyId"]]
        relevant = {d for d in pool if mechanisms & set(doc_meta[d]["mechanismIds"])}
        if not relevant:
            excluded["NO_OTHER_FAMILY"] += 1
            continue
        if q["status"] != "OK":
            excluded[q["status"]] += 1
            continue
        hit = any(d in relevant for d in selected.get(key, []))
        expected = chance_hit(pool, relevant)
        hits += hit
        denominator += 1
        chance += expected
        row = by_mechanism.setdefault(sorted(mechanisms)[0], {"denominator": 0, "hits": 0, "chanceExpectedHits": 0.0})
        row["denominator"] += 1
        row["hits"] += hit
        row["chanceExpectedHits"] += expected
    return {"k": K, "denominator": denominator, "hits": hits, "chanceExpectedHits": round(chance, 4),
            "excluded": excluded,
            "byMechanism": {m: {**r, "chanceExpectedHits": round(r["chanceExpectedHits"], 4)} for m, r in sorted(by_mechanism.items())}}


def _read(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def _write_once(path: Path, value) -> None:
    with path.open("x", encoding="utf-8") as out:
        json.dump(value, out, ensure_ascii=False, indent=1)
    path.chmod(0o444)


def cmd_select(args) -> None:
    from embed_segments import MODEL_ID, bge_m3_encoder, library_versions
    items = {i["statementId"]: i for i in _read(args.items)["items"]}
    reasons = _read(args.reasons)["reasons"]
    families = _read(args.families)
    queries = _read(args.queries)
    encode, artifacts = bge_m3_encoder(args.revision)
    doc_ids = sorted(reasons)
    doc_vectors = dict(zip(doc_ids, encode([reasons[d]["reason"] for d in doc_ids])))
    ok = sorted(k for k, q in queries.items() if q["status"] == "OK")
    query_vectors = dict(zip(ok, encode([queries[k]["reason"] for k in ok])))
    query_family = {k: families.get(k.split("/", 1)[0]) for k in queries}
    selections = select_top(query_vectors, doc_vectors, {d: items[d]["familyId"] for d in doc_ids}, query_family)
    args.output.mkdir()
    _write_once(args.output / "vector-selections-v1.json", {"format": "casebook-vector-selections-v1", "k": K,
                                                            "selections": selections})
    _write_once(args.output / "vectors-v1.json", {"modelId": MODEL_ID, "revision": args.revision,
                                                  "modelArtifactsHash": artifacts, "libraries": library_versions(),
                                                  "documents": doc_vectors, "queries": query_vectors})
    print(json.dumps({"documents": len(doc_ids), "queries": len(ok), "empty": sum(not v for v in selections.values())}))


def keyword_selections(results: Path) -> dict[str, list[str]]:
    out = {}
    for line in results.read_text(encoding="utf-8").splitlines():
        r = json.loads(line)
        if r["repeat"] == 0:
            out[f"{r['videoId']}/{r['targetId']}"] = r.get("discoveryReferenceIds") or []
    return out


def cmd_retrieval(args) -> None:
    items = {i["statementId"]: i for i in _read(args.items)["items"]}
    families = _read(args.families)
    queries = _read(args.queries)
    doc_meta = {s: {"familyId": i["familyId"], "mechanismIds": i["mechanismIds"]} for s, i in items.items() if i["inRuntime"]}
    query_meta = {}
    for t in _read(args.targets):
        key = f"{t['videoId']}/{t['targetId']}"
        if t["videoId"] >= FIRST_CASEBOOK_VIDEO:
            item = items[t["statementId"]]
            query_meta[key] = {"familyId": families[str(t["videoId"])], "mechanismIds": item["mechanismIds"],
                               "status": queries[key]["status"]}
    vector = _read(args.vector)["selections"]
    result = {"format": "casebook-retrieval-v1", "vector": retrieval_metrics(vector, query_meta, doc_meta),
              "keyword": retrieval_metrics(keyword_selections(args.keyword), query_meta, doc_meta),
              "note": "same denominator for both; keyword picks outside the casebook count as misses; "
                      "chanceExpectedHits = sum over queries of P(random 3 of the eligible pool include a same-mechanism other family)"}
    _write_once(args.output, result)
    print(json.dumps({m: {k: result[m][k] for k in ("denominator", "hits", "chanceExpectedHits")} for m in ("vector", "keyword")}))


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="mode", required=True)
    s = sub.add_parser("select")
    for name in ("items", "reasons", "families", "queries", "output"):
        s.add_argument(name, type=Path)
    s.add_argument("--revision", required=True)
    r = sub.add_parser("retrieval")
    for name in ("items", "families", "targets", "queries", "vector", "keyword", "output"):
        r.add_argument(name, type=Path)
    args = parser.parse_args(argv)
    {"select": cmd_select, "retrieval": cmd_retrieval}[args.mode](args)


if __name__ == "__main__":
    main()
