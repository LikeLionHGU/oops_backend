#!/usr/bin/env bash
set -euo pipefail
if [ "${EUID}" -ne 0 ]; then
  echo 'Run with sudo -n bash versions/0.4.1-draft/run-server.sh --dry-run|--apply|--verify' >&2
  exit 1
fi
case "${1:-}" in --dry-run|--apply|--verify) ;; *) echo 'Expected --dry-run, --apply or --verify' >&2; exit 1 ;; esac
version_dir=$(cd -- "$(dirname -- "$0")" && pwd)
infra_dir=$(cd -- "$version_dir/../.." && pwd)
cd "$infra_dir"
test -s .env
python3 - "$version_dir" <<'PY'
import hashlib,json,sys
from pathlib import Path
p=Path(sys.argv[1]); source=json.loads((p/'SOURCE.json').read_text())
for name, metadata in source['files'].items():
    if hashlib.sha256((p/name).read_bytes()).hexdigest()!=metadata['sha256']:
        raise SystemExit('Source JSON checksum mismatch: '+name)
print('Source commit:',source['source_commit'])
PY
# Reuse the deployed importer image and existing secret; no image rebuild or service restart.
docker compose --profile tools run --rm --no-deps \
  --volume "$infra_dir/app/import_ruleset_version.py:/version-import.py:ro" \
  --volume "$version_dir:/version-data" \
  --entrypoint python importer /version-import.py --directory /version-data "$1"
