#!/usr/bin/env python3
"""Materialize a provenance-checked, label-free transcript probe from selectors."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def canonical_segment_hash(segments: list[dict[str, Any]]) -> str:
    projected = [
        {"startMs": segment["startMs"], "endMs": segment["endMs"], "text": segment["text"]}
        for segment in segments
    ]
    encoded = json.dumps(projected, ensure_ascii=False, separators=(",", ":")) + "\n"
    return sha256(encoded.encode("utf-8"))


def resolve_pointer(document: Any, pointer: str) -> Any:
    if pointer == "":
        return document
    if not pointer.startswith("/"):
        raise ValueError("JSON pointer must be empty or start with '/'")
    current = document
    for raw_part in pointer[1:].split("/"):
        part = raw_part.replace("~1", "/").replace("~0", "~")
        if isinstance(current, list):
            current = current[int(part)]
        elif isinstance(current, dict):
            current = current[part]
        else:
            raise ValueError(f"JSON pointer traverses a scalar at {part!r}")
    return current


def select_segments(
    document: Any,
    selector: dict[str, Any],
    selected_content_sha256: str | None = None,
) -> list[dict[str, Any]]:
    rows = resolve_pointer(document, selector["jsonPointer"])
    if not isinstance(rows, list):
        raise ValueError("selector must resolve to an array")

    mode = selector["mode"]
    if mode == "array-slice":
        start = selector["startIndexInclusive"]
        end = selector["endIndexExclusive"]
        if type(start) is not int or type(end) is not int or start < 0 or end < start:
            raise ValueError("invalid array slice")
        selected = rows[start:end]
        if len(selected) != end - start:
            raise ValueError("array slice exceeds source length")
    elif mode == "array-filter-by-startMs":
        lower = selector["startMsInclusive"]
        upper = selector["startMsExclusive"]
        if type(lower) is not int or type(upper) is not int or lower >= upper:
            raise ValueError("invalid startMs range")
        selected = [
            row for row in rows
            if type(row.get("startMs")) is int and lower <= row["startMs"] < upper
        ]
    else:
        raise ValueError(f"unsupported selector mode: {mode}")

    normalized = []
    for row in selected:
        if not isinstance(row, dict) or type(row.get("startMs")) is not int \
                or type(row.get("endMs")) is not int or not isinstance(row.get("text"), str):
            raise ValueError("selected transcript segment has an invalid shape")
        normalized.append({"startMs": row["startMs"], "endMs": row["endMs"], "text": row["text"]})
    if len(normalized) != selector["expectedSegmentCount"]:
        raise ValueError("selected segment count differs from the frozen selector")
    expected_hash = selected_content_sha256 or selector.get("selectedContentSha256")
    if not isinstance(expected_hash, str) or canonical_segment_hash(normalized) != expected_hash:
        raise ValueError("selected transcript content hash differs from the frozen selector")
    return normalized


def materialize(selectors: dict[str, Any], dataset_root: Path) -> dict[str, Any]:
    if selectors.get("status") != "FROZEN_TEXT_SELECTORS_NOT_RUN":
        raise ValueError("selector manifest is not in a materializable frozen state")
    if selectors.get("sourcePolicy", {}).get("expectedLabelsIncluded") is not False:
        raise ValueError("selector manifest must explicitly exclude expected labels")

    root = dataset_root.resolve(strict=True)
    output_items = []
    sample_ids = set()
    for item in selectors.get("items", []):
        sample_id = item.get("sampleId")
        if not isinstance(sample_id, str) or not sample_id or sample_id in sample_ids:
            raise ValueError("sample IDs must be non-empty and unique")
        sample_ids.add(sample_id)

        source_path = (root / item["source"]["path"]).resolve(strict=True)
        if not source_path.is_relative_to(root):
            raise ValueError("source path escapes dataset root")
        source_bytes = source_path.read_bytes()
        if sha256(source_bytes) != item["source"]["sha256"]:
            raise ValueError(f"source hash changed for {sample_id}")
        source_document = json.loads(source_bytes)
        segments = select_segments(
            source_document, item["selector"], item.get("selectedContentSha256")
        )
        output_items.append({"sampleId": sample_id, "segments": segments})

    if not output_items:
        raise ValueError("selector manifest contains no samples")
    return {
        "schemaVersion": "cross-case-development-inputs-1",
        "id": selectors["id"],
        "status": "MATERIALIZED_TEXT_ONLY_NO_LABELS",
        "items": output_items,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--selectors", type=Path, required=True)
    parser.add_argument("--dataset-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--overwrite", action="store_true")
    args = parser.parse_args()

    if args.output.exists() and not args.overwrite:
        raise SystemExit("output exists; pass --overwrite only when replacement is intended")
    selectors = json.loads(args.selectors.read_text(encoding="utf-8"))
    result = materialize(selectors, args.dataset_root)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(args.output), "samples": len(result["items"]), "labelsRead": False}))


if __name__ == "__main__":
    main()
