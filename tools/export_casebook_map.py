#!/usr/bin/env python3
"""Export a local, git-ignored casebook snapshot for the read-only HTML map."""
import hashlib
import json
import os
import tempfile
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "datasets" / "controversy"
OUT = DATA / "visualizations" / "casebook-snapshot.js"
LOCAL_VIEW = DATA / "visualizations" / "casebook-map-local.json"


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


def write_private_text(path, text):
    """Created 0600 and swapped in atomically, so the snapshot is never briefly world-readable."""
    descriptor, temporary = tempfile.mkstemp(prefix=".snapshot-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            stream.write(text)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def main():
    runtime_path = DATA / "guidelines" / "runtime.json"
    runtime = load(runtime_path)
    incidents = [{**load(p), "_mapFile": str(p.relative_to(DATA))}
                 for p in sorted((DATA / "incidents").glob("*.json"))]
    units = [{**load(p), "_mapFile": str(p.relative_to(DATA))}
             for p in sorted((DATA / "context-units").glob("*.json"))]
    corrections = [load(p) for p in sorted((DATA / "research").glob("*-correction-proposal-*.json"))]
    local_view = load(LOCAL_VIEW) if LOCAL_VIEW.is_file() else {}
    snapshot = {"createdAt": datetime.now(timezone.utc).isoformat(),
                "runtimeSha256": hashlib.sha256(runtime_path.read_bytes()).hexdigest(),
                "runtime": runtime, "incidents": incidents, "units": units,
                "researchCorrectionProposals": corrections,
                "familyNames": local_view.get("familyNames", {}),
                "exampleView": local_view.get("exampleView")}
    OUT.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(snapshot, ensure_ascii=False, separators=(",", ":"))
    write_private_text(OUT, "window.CASEBOOK_SNAPSHOT=" + payload + ";\n")
    print(f"Local snapshot: {OUT}; examples={len(runtime['examples'])}; "
          f"incidents={len(incidents)}; units={len(units)}")


if __name__ == "__main__":
    main()
