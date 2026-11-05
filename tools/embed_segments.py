"""Freeze BGE-M3 dense vectors for one transcript, for the A2-T selector (EmbeddingContextSelector).

Input JSON: {"segments": [{"id", "startMs", "endMs", "text"}, ...]} from the frozen ReviewInput.
Query = the target segment's raw text; document = each segment's raw text (target-raw-text-v1).
Runs locally on CPU; transcript text never leaves the machine. Downloads model files only when
they are not already cached. Run from the repository root:
python tools/embed_segments.py INPUT.json NEW_OUTPUT.json --revision <40-hex commit>
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import platform
import re
import struct
from importlib import metadata
from pathlib import Path
from typing import Callable, Sequence

FORMAT = "segment-embeddings-v1"
INPUT_VERSION = "target-raw-text-v1"
MODEL_ID = "BAAI/bge-m3"
NORM_TOLERANCE = 1e-3
BATCH_SIZE = 16
LIBRARIES = ("sentence-transformers", "transformers", "torch", "huggingface-hub", "numpy")

Encoder = Callable[[Sequence[str]], Sequence[Sequence[float]]]


def ordered_segments(segments: list[dict]) -> list[dict]:
    """Same order as Java ReviewInput: startMs, endMs, then id."""
    return sorted(segments, key=lambda s: (s["startMs"], s["endMs"], s["id"]))


def transcript_sha256(ordered: list[dict]) -> str:
    joined = "".join(f"{s['id']}\0{s['text']}\0" for s in ordered)
    return hashlib.sha256(joined.encode("utf-8")).hexdigest()


def query_text(segment: dict) -> str:
    return segment["text"]


def document_text(segment: dict) -> str:
    return segment["text"]


def build_file(segments: list[dict], encoder: Encoder, meta: dict) -> dict:
    if not re.fullmatch(r"[0-9a-f]{40}", meta["revision"]):
        raise ValueError("revision must be a pinned 40-hex commit")
    ordered = ordered_segments(segments)
    ids = [s["id"] for s in ordered]
    if len(set(ids)) != len(ids):
        raise ValueError("Duplicate segment IDs")
    for s in ordered:
        if not s["id"] or "\0" in s["id"] or not isinstance(s["text"], str) or "\0" in s["text"]:
            raise ValueError(f"Invalid segment: {s['id']!r}")
    documents = _vectors(ids, encoder([document_text(s) for s in ordered]))
    queries = _vectors(ids, encoder([query_text(s) for s in ordered]))
    dimensions = {len(v) for v in [*documents.values(), *queries.values()]}
    if len(dimensions) != 1:
        raise ValueError("Inconsistent vector dimensions")
    return {
        "format": FORMAT,
        "modelId": meta["modelId"],
        "revision": meta["revision"],
        "modelArtifactsHash": meta["modelArtifactsHash"],
        "transcriptSha256": transcript_sha256(ordered),
        "embeddingInputVersion": INPUT_VERSION,
        "vectorDimension": dimensions.pop(),
        "vectorDtype": "float32",
        "normalized": True,
        "generationLibraryVersions": meta["generationLibraryVersions"],
        "documentVectors": documents,
        "queryVectors": queries,
    }


def _vectors(ids: list[str], encoded: Sequence[Sequence[float]]) -> dict[str, list[float]]:
    if len(encoded) != len(ids):
        raise ValueError("Encoder returned the wrong number of vectors")
    result = {}
    for segment_id, vector in zip(ids, encoded):
        values = [struct.unpack("f", struct.pack("f", float(v)))[0] for v in vector]
        if not all(math.isfinite(v) for v in values):
            raise ValueError(f"Non-finite vector: {segment_id}")
        if abs(math.sqrt(sum(v * v for v in values)) - 1) > NORM_TOLERANCE:
            raise ValueError(f"Vector not normalized: {segment_id}")
        result[segment_id] = values
    return result


def write_file(content: dict, path: Path) -> str:
    """Write once; returns the vectorFileSha256 that the Java selector records."""
    data = json.dumps(content, ensure_ascii=False, sort_keys=True).encode("utf-8")
    with path.open("xb") as out:
        out.write(data)
    return hashlib.sha256(data).hexdigest()


def artifacts_hash(model_dir: Path) -> str:
    digest = hashlib.sha256()
    for file in sorted(p for p in model_dir.rglob("*") if p.is_file()):
        digest.update(file.relative_to(model_dir).as_posix().encode("utf-8") + b"\0")
        digest.update(hashlib.sha256(file.read_bytes()).hexdigest().encode("ascii") + b"\0")
    return digest.hexdigest()


def library_versions() -> dict[str, str]:
    return {"python": platform.python_version(), **{name: metadata.version(name) for name in LIBRARIES}}


def bge_m3_encoder(revision: str) -> tuple[Encoder, str]:
    from huggingface_hub import snapshot_download
    from sentence_transformers import SentenceTransformer
    import torch

    torch.manual_seed(0)
    torch.use_deterministic_algorithms(True)
    model_dir = Path(snapshot_download(MODEL_ID, revision=revision))
    model = SentenceTransformer(str(model_dir), device="cpu")

    def encode(texts: Sequence[str]) -> list[list[float]]:
        return model.encode(list(texts), batch_size=BATCH_SIZE, normalize_embeddings=True,
                            convert_to_numpy=True).astype("float32").tolist()

    return encode, artifacts_hash(model_dir)


def main(argv: Sequence[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--revision", required=True)
    args = parser.parse_args(argv)
    segments = json.loads(args.input.read_text(encoding="utf-8"))["segments"]
    encoder, artifacts = bge_m3_encoder(args.revision)
    meta = {"modelId": MODEL_ID, "revision": args.revision, "modelArtifactsHash": artifacts,
            "generationLibraryVersions": library_versions()}
    digest = write_file(build_file(segments, encoder, meta), args.output)
    print(f"vectorFileSha256={digest}")


if __name__ == "__main__":
    main()
