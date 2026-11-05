"""Freeze bge-reranker-v2-m3 scores over the A2-T top-K, for the A3-T selector (RerankContextSelector).

Candidates are recomputed from the frozen A2 vector file with the same ranking as the Java selector,
which rejects any score file whose candidate set differs. Query = target raw text; passage = the
candidate's raw text (target-raw-text-v1). Scores are raw logits. Runs locally on CPU. Run from the
repository root:
python tools/rerank_segments.py INPUT.json EMBEDDINGS.json NEW_OUTPUT.json --revision <40-hex> --top-k K
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
from embed_segments import FORMAT as EMBEDDING_FORMAT, artifacts_hash, library_versions, ordered_segments, \
    transcript_sha256, write_file

FORMAT = "rerank-scores-v1"
INPUT_VERSION = "target-raw-text-v1"
MODEL_ID = "BAAI/bge-reranker-v2-m3"
MAX_LENGTH = 1024
BATCH_SIZE = 16
ALLOW_PATTERNS = (
    "config.json", "model.safetensors", "sentencepiece.bpe.model", "special_tokens_map.json",
    "tokenizer.json", "tokenizer_config.json",
)

Scorer = Callable[[Sequence[tuple[str, str]]], Sequence[float]]


def _distance(target: dict, other: dict) -> int:
    if other["startMs"] >= target["endMs"]:
        return other["startMs"] - target["endMs"]
    return max(0, target["startMs"] - other["endMs"])


def _dot(a: Sequence[float], b: Sequence[float]) -> float:
    total = 0.0
    for x, y in zip(a, b):
        total += x * y
    return total


def a2_top_k(ordered: list[dict], embeddings: dict, target_id: str, k: int) -> list[str]:
    """Same order as EmbeddingContextSelector.ranking: cosine desc, closer, then transcript order."""
    index = {s["id"]: i for i, s in enumerate(ordered)}
    target = ordered[index[target_id]]
    query = embeddings["queryVectors"][target_id]
    ranked = sorted(
        (s for s in ordered if s["id"] != target_id),
        key=lambda s: (-_dot(query, embeddings["documentVectors"][s["id"]]), _distance(target, s), index[s["id"]]),
    )
    return [s["id"] for s in ranked[:k]]


def build_score_file(segments: list[dict], embeddings: dict, vector_file_sha256: str, k: int,
                     scorer: Scorer, meta: dict) -> dict:
    if not re.fullmatch(r"[0-9a-f]{40}", meta["revision"]):
        raise ValueError("revision must be a pinned 40-hex commit")
    if k < 1:
        raise ValueError("top-k must be positive")
    if embeddings.get("format") != EMBEDDING_FORMAT:
        raise ValueError("Unsupported embedding file format")
    ordered = ordered_segments(segments)
    digest = transcript_sha256(ordered)
    if digest != embeddings["transcriptSha256"]:
        raise ValueError("Transcript does not match the embedding file")
    texts = {s["id"]: s["text"] for s in ordered}
    scores = {}
    for target in ordered:
        if target["id"] not in embeddings["queryVectors"]:
            continue
        candidates = a2_top_k(ordered, embeddings, target["id"], k)
        pairs = [(target["text"], texts[c]) for c in candidates]
        values = [float(v) for v in scorer(pairs)]
        if len(values) != len(pairs) or not all(math.isfinite(v) for v in values):
            raise ValueError(f"Invalid reranker scores for {target['id']}")
        scores[target["id"]] = dict(zip(candidates, values))
    return {
        "format": FORMAT,
        "modelId": meta["modelId"],
        "revision": meta["revision"],
        "modelArtifactsHash": meta["modelArtifactsHash"],
        "transcriptSha256": digest,
        "rerankInputVersion": INPUT_VERSION,
        "scoreType": "raw-logit",
        "generationLibraryVersions": meta["generationLibraryVersions"],
        "candidateSource": {"vectorFileSha256": vector_file_sha256, "topK": k},
        "scores": scores,
    }


def reranker_scorer(revision: str) -> tuple[Scorer, str]:
    from huggingface_hub import snapshot_download
    from sentence_transformers import CrossEncoder
    import torch

    torch.manual_seed(0)
    torch.use_deterministic_algorithms(True)
    model_dir = Path(snapshot_download(MODEL_ID, revision=revision, allow_patterns=list(ALLOW_PATTERNS)))
    model = CrossEncoder(str(model_dir), device="cpu", max_length=MAX_LENGTH)

    def score(pairs: Sequence[tuple[str, str]]) -> list[float]:
        return model.predict(list(pairs), batch_size=BATCH_SIZE, activation_fn=torch.nn.Identity(),
                             convert_to_numpy=True).astype("float32").tolist()

    return score, artifacts_hash(model_dir)


def main(argv: Sequence[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("input", type=Path)
    parser.add_argument("embeddings", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--top-k", type=int, required=True)
    args = parser.parse_args(argv)
    segments = json.loads(args.input.read_text(encoding="utf-8"))["segments"]
    embedding_bytes = args.embeddings.read_bytes()
    scorer, artifacts = reranker_scorer(args.revision)
    meta = {"modelId": MODEL_ID, "revision": args.revision, "modelArtifactsHash": artifacts,
            "generationLibraryVersions": library_versions()}
    result = build_score_file(segments, json.loads(embedding_bytes), hashlib.sha256(embedding_bytes).hexdigest(),
                              args.top_k, scorer, meta)
    print(f"scoreFileSha256={write_file(result, args.output)}")


if __name__ == "__main__":
    main()
