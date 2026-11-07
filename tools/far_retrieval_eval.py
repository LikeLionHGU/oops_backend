"""Evaluate A2/A3 retrieval on the frozen Far challenge cases under far-challenge-retrieval-r1.

Inputs per case: the frozen transcript, its BGE-M3 vector file (tools/embed_segments.py), the A0 provided set and the
label diagnostic (gold context). Only target text builds queries; gold text never does. Runs locally on CPU in the
repository .venv; no paid calls. Run from the repository root:
HF_HUB_OFFLINE=1 .venv/bin/python tools/far_retrieval_eval.py CASES.json NEW_OUTPUT.json [--rules r2b]
--rules r2b swaps in the split-v2 rule of r2b-semantic-v1; every run reports A3 top exposure at the largest K.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
from pathlib import Path
from typing import Callable, Sequence

sys.path.insert(0, str(Path(__file__).resolve().parent))
from embed_segments import ordered_segments
from rerank_segments import _distance, _dot

ROOT = Path(__file__).resolve().parents[1]
RULES = ROOT / "docs/experiments/retrieval-rules/far-challenge-r1.json"
RULES_SHA256 = "da6f541cf44a78a5277be399dec34d26829d1b0a2d20be1259069bf9eaf57208"
EMBED_REVISION = "5617a9f61b028005a4858fdac845db406aefb181"
RERANK_REVISION = "953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e"
METHODS = ("Q0_RAW", "MAX", "RRF", "TOPN_EQUAL", "UNION_A3")
BUDGETS = (20, 50, 100)
RRF_K = 60
EXPOSURE_CUTS = (5, 10, 20)
R2B_RULES = ROOT / "docs/experiments/retrieval-rules/r2b-semantic-v1.json"
R2B_RULES_SHA256 = "7600935c9b611eca00b4fdc9bd43c6459be08d7a5c12d8f21b6215cdea301f36"
Scorer = Callable[[Sequence[tuple[str, str]]], Sequence[float]]


def split_pieces(text: str) -> list[str]:
    pieces = [p.strip() for p in re.split(r"(?<=[?.!])\s+", text) if p.strip()]
    return pieces if len(pieces) >= 2 else [text]


def _pool(segments: list[dict], target: dict, past_only: bool) -> tuple[list[dict], dict[str, int]]:
    index = {s["id"]: i for i, s in enumerate(segments)}
    pool = [s for s in segments if s["id"] != target["id"] and (not past_only or s["startMs"] < target["startMs"])]
    return pool, index


def _ranked(scores: dict[str, float], pool: list[dict], target: dict, index: dict[str, int]) -> list[str]:
    return [s["id"] for s in sorted(pool, key=lambda s: (-scores[s["id"]], _distance(target, s), index[s["id"]]))]


def _round_robin(rankings: list[list[str]], k: int) -> list[str]:
    taken, seen = [], set()
    for position in range(max(len(r) for r in rankings)):
        for ranking in rankings:
            if position < len(ranking) and ranking[position] not in seen:
                seen.add(ranking[position])
                taken.append(ranking[position])
                if len(taken) == k:
                    return taken
    return taken


def candidates(method: str, piece_scores: list[dict[str, float]], segments: list[dict], target: dict, k: int,
               past_only: bool = False) -> list[str]:
    pool, index = _pool(segments, target, past_only)
    if method == "Q0_RAW" or len(piece_scores) == 1:
        return _ranked(piece_scores[0], pool, target, index)[:k]
    rankings = [_ranked(scores, pool, target, index) for scores in piece_scores]
    if method == "MAX":
        best = {s["id"]: max(scores[s["id"]] for scores in piece_scores) for s in pool}
        return _ranked(best, pool, target, index)[:k]
    if method == "RRF":
        fused = {s["id"]: 0.0 for s in pool}
        for ranking in rankings:
            for rank, doc in enumerate(ranking, start=1):
                fused[doc] += 1 / (RRF_K + rank)
        return _ranked(fused, pool, target, index)[:k]
    if method == "TOPN_EQUAL":
        return _round_robin(rankings, k)
    if method == "UNION_A3":
        share = math.ceil(k / len(rankings))
        return _round_robin([r[:share] for r in rankings], k)
    raise ValueError(f"Unknown method {method}")


def rerank(candidate_ids: list[str], query_texts: list[str], segments: list[dict], target: dict,
           scorer: Scorer) -> list[str]:
    by_id = {s["id"]: s for s in segments}
    index = {s["id"]: i for i, s in enumerate(segments)}
    pairs = [(q, by_id[c]["text"]) for c in candidate_ids for q in query_texts]
    logits = list(scorer(pairs)) if pairs else []
    width = len(query_texts)
    best = {c: max(logits[n * width:(n + 1) * width]) for n, c in enumerate(candidate_ids)}
    return sorted(candidate_ids, key=lambda c: (-best[c], _distance(target, by_id[c]), index[c]))


def case_metrics(gold: list[str], ranked: list[str], a0_ids: list[str]) -> dict:
    position = {doc: n for n, doc in enumerate(ranked, start=1)}
    found = [g for g in gold if g in position]
    outside = [g for g in gold if g not in set(a0_ids)]
    return {"recall": len(found) / len(gold),
            "recallOutsideA0": None if not outside else sum(g in position for g in outside) / len(outside),
            "allContext": len(found) == len(gold), "ranks": {g: position.get(g) for g in gold}}


def top_exposure(gold: list[str], ranked: list[str]) -> dict[str, float]:
    return {str(cut): sum(g in set(ranked[:cut]) for g in gold) / len(gold) for cut in EXPOSURE_CUTS}


def evaluate_case(case: dict, segments: list[dict], embeddings: dict, encode, scorer: Scorer,
                  split: Callable[[str], list[str]] = split_pieces) -> dict:
    target = next(s for s in segments if s["id"] == case["target"])
    pieces = split(target["text"])
    docs = embeddings["documentVectors"]
    q0 = {s["id"]: _dot(embeddings["queryVectors"][target["id"]], docs[s["id"]]) for s in segments}
    piece_vectors = encode(pieces) if len(pieces) > 1 else [embeddings["queryVectors"][target["id"]]]
    piece_scores = [{s["id"]: _dot(v, docs[s["id"]]) for s in segments} for v in piece_vectors]
    gold, a0 = case["gold"], case["a0ContextSegmentIds"]
    result = {"videoId": case["videoId"], "target": target["id"], "type": case["type"], "pieces": pieces,
              "unsplit": len(pieces) == 1, "gold": gold, "a0Included": set(gold) <= set(a0), "scopes": {}}
    for scope, past_only in (("FULL_VIDEO", False), ("PAST_ONLY", True)):
        available = [g for g in gold if
                     next(s for s in segments if s["id"] == g)["startMs"] < target["startMs"] or not past_only]
        scope_result = {"goldAvailable": available, "methods": {}}
        for method in METHODS:
            scores = [q0] if method == "Q0_RAW" else piece_scores
            queries = [target["text"]] if method == "Q0_RAW" else pieces
            full = candidates(method, scores, segments, target, len(segments), past_only)
            entry = {"fullRanks": case_metrics(gold, full, a0)["ranks"], "atK": {}}
            for k in BUDGETS:
                top = candidates(method, scores, segments, target, k, past_only)
                a3 = rerank(top, queries, segments, target, scorer)
                entry["atK"][str(k)] = {**case_metrics(gold, top, a0), "a3Ranks": case_metrics(gold, a3, a0)["ranks"]}
                if k == max(BUDGETS):
                    entry["atK"][str(k)]["topExposure"] = {"a2": top_exposure(gold, top), "a3": top_exposure(gold, a3)}
            scope_result["methods"][method] = entry
        result["scopes"][scope] = scope_result
    return result


def summarize(results: list[dict]) -> dict:
    summary = {}
    for group in sorted({r["type"] for r in results}) + ["ALL_TYPES"]:
        members = [r for r in results if group in (r["type"], "ALL_TYPES")]
        for scope in ("FULL_VIDEO", "PAST_ONLY"):
            for method in METHODS:
                for k in map(str, BUDGETS):
                    cells = [r["scopes"][scope]["methods"][method]["atK"][k] for r in members]
                    outside = [c["recallOutsideA0"] for c in cells if c["recallOutsideA0"] is not None]
                    summary.setdefault(group, {}).setdefault(scope, {}).setdefault(method, {})[k] = {
                        "cases": len(cells), "meanRecall": round(sum(c["recall"] for c in cells) / len(cells), 3),
                        "meanRecallOutsideA0": round(sum(outside) / len(outside), 3) if outside else None,
                        "outsideA0NotApplicable": len(cells) - len(outside),
                        "allContextRecall": f"{sum(c['allContext'] for c in cells)}/{len(cells)}"}
    return summary


def _sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main(argv: Sequence[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("cases", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--rules", choices=("r1", "r2b"), default="r1")
    args = parser.parse_args(argv)
    if _sha(RULES) != RULES_SHA256:
        raise ValueError("Retrieval rules file differs from the frozen r1 file")
    split = split_pieces
    if args.rules == "r2b":
        if _sha(R2B_RULES) != R2B_RULES_SHA256:
            raise ValueError("Rules file differs from the frozen r2b-semantic-v1 file")
        from r2b_types import split_v2
        split = lambda text: split_v2(text)[0]
    from embed_segments import bge_m3_encoder
    from rerank_segments import reranker_scorer
    encode, _ = bge_m3_encoder(EMBED_REVISION)
    scorer, _ = reranker_scorer(RERANK_REVISION)
    spec = json.loads(args.cases.read_text(encoding="utf-8"))
    base = args.cases.parent
    results, inputs = [], []
    for case in spec["cases"]:
        transcript, vectors = base / case["transcript"], base / case["embeddings"]
        segments = ordered_segments(json.loads(transcript.read_text(encoding="utf-8"))["segments"])
        embeddings = json.loads(vectors.read_text(encoding="utf-8"))
        results.append(evaluate_case(case, segments, embeddings, encode, scorer, split))
        inputs.append({"videoId": case["videoId"], "target": case["target"], "transcriptSha256": _sha(transcript),
                       "vectorFileSha256": _sha(vectors)})
    output = {"format": "far-retrieval-eval-v1", "rules": args.rules, "rulesSha256": RULES_SHA256,
              "r2bRulesSha256": R2B_RULES_SHA256 if args.rules == "r2b" else None, "casesSha256": _sha(args.cases),
              "inputs": inputs, "summary": summarize(results), "cases": results,
              "note": "At the same K, A3 reorders the same candidate set, so its recall equals A2's; A3 effect is in a3Ranks."}
    with args.output.open("x", encoding="utf-8") as out:
        json.dump(output, out, ensure_ascii=False, indent=1)
    args.output.chmod(0o444)
    print(json.dumps(output["summary"]["ALL_TYPES"]["FULL_VIDEO"]["Q0_RAW"], ensure_ascii=False))


if __name__ == "__main__":
    main()
