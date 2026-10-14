"""Archive obsolete dictionary files only after canonical build verification."""
import argparse
import hashlib
from pathlib import Path
from build_incident_dataset import DEFAULT_ROOT, verify
from build_review_guidelines import write_private
from review_context_pilot import read_bytes, require

FILES = ("drafts/cards.json", "drafts/context-dictionary.json", "guidelines/plan.json",
         "guidelines/patterns.json", "guidelines/context-examples.json", "catalog.json",
         "research/context-dictionary-plan.json")


def archive(root):
    verified = verify(root)
    destination = root / "legacy/pre-incident-build"
    pending = []
    for name in FILES:
        source, target = root / name, destination / name
        if not source.exists():
            continue
        require(not source.is_symlink() and not target.exists(), "ARCHIVE_TARGET_CONFLICT:" + name)
        pending.append((source, target, name, hashlib.sha256(read_bytes(source)).hexdigest()))
    for source, target, _, _ in pending:
        target.parent.mkdir(parents=True, exist_ok=True)
        source.rename(target)
    if pending:
        write_private(destination / "archive-manifest.json",
                      {"schemaVersion": "legacy-dictionary-archive-1",
                       "verifiedRuntimeSha256": verified["runtimeSha256"],
                       "files": {name: sha for _, _, name, sha in pending}})
    verify(root)
    return [name for _, _, name, _ in pending]


if __name__ == "__main__":
    import json
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-root", type=Path, default=DEFAULT_ROOT)
    args = parser.parse_args()
    try:
        print(json.dumps({"archived": archive(args.dataset_root)}, ensure_ascii=False, indent=2))
    except Exception as error:
        parser.exit(2, f"{type(error).__name__}: {error}\n")
