#!/usr/bin/env bash
set -euo pipefail
OOPS_KIT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$OOPS_KIT_DIR"
if [ "$EUID" -ne 0 ]; then
  printf '%s\n' '서버에서 sudo bash verify-server.sh 로 실행하세요.'
  exit 1
fi
unset OOPS_DB_PASSWORD
docker compose config --quiet
docker compose run --rm --no-deps --entrypoint python importer verify_graph.py
docker compose ps
