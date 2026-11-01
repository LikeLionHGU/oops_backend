#!/usr/bin/env bash
# Run on the AWS server: sudo bash start-server.sh
set -euo pipefail
OOPS_KIT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$OOPS_KIT_DIR"

if [ "$EUID" -ne 0 ]; then
  printf '%s\n' '서버에서 sudo bash start-server.sh 로 실행하세요.'
  exit 1
fi
if ! command -v docker >/dev/null 2>&1; then
  printf '%s\n' '먼저 sudo bash install-docker-ubuntu.sh 를 실행하세요.'
  exit 1
fi
docker info >/dev/null
docker compose version

# The persisted server file is the only password source for this deployment.
unset OOPS_DB_PASSWORD
if [ ! -e .env ]; then
  if docker volume inspect oops-neo4j-aws_neo4j_data >/dev/null 2>&1; then
    printf '%s\n' '기존 AWS Neo4j 볼륨이 있지만 .env가 없습니다. 기존 접속 설정을 복원하세요. 새 비밀번호를 만들지 않습니다.'
    exit 1
  fi
  if ! command -v openssl >/dev/null 2>&1; then
    printf '%s\n' 'openssl이 필요합니다. sudo apt-get install openssl 로 설치하세요.'
    exit 1
  fi
  umask 077
  OOPS_NEW_PASSWORD="$(openssl rand -hex 24)"
  printf 'OOPS_DB_PASSWORD=%s\n' "$OOPS_NEW_PASSWORD" > .env
  unset OOPS_NEW_PASSWORD
fi
chmod 600 .env
docker compose config --quiet

# Initial headroom includes the database, importer and some host overhead.
# Skip this initial-start check when this deployment is already running.
OOPS_RUNNING_ID="$(docker compose ps --status running -q neo4j)"
if [ -z "$OOPS_RUNNING_ID" ] && [ -r /proc/meminfo ]; then
  OOPS_AVAILABLE_KIB="$(awk '/^MemAvailable:/ {print $2}' /proc/meminfo)"
  if [ -z "$OOPS_AVAILABLE_KIB" ] || [ "$OOPS_AVAILABLE_KIB" -lt 2097152 ]; then
    printf '%s\n' '초기 시작에 필요한 여유 메모리 2GiB를 확인하지 못했습니다. 기존 백엔드를 중단하지 않고 설치를 멈춥니다.'
    free -h
    exit 1
  fi
fi

printf '%s\n' '1/3 데이터 적재 도구를 준비합니다.'
docker compose build importer
printf '%s\n' '2/3 Neo4j를 시작합니다. 컨테이너 메모리 상한은 1.5GiB입니다.'
if ! docker compose up -d --wait --wait-timeout 300 neo4j; then
  printf '%s\n' 'Neo4j 준비에 실패했습니다. sudo docker compose logs --tail 80 neo4j 로 상태를 확인하세요.'
  exit 1
fi
printf '%s\n' '3/3 사례·기준·독립 점검 규칙을 적재하고 검증합니다.'
docker compose run --rm --no-deps importer
printf '%s\n' '데이터 적재·검증 완료. 호스트에서 실행하는 백엔드의 주소: bolt://127.0.0.1:7687'
printf '%s\n' '사용자 이름: neo4j / DB 이름: neo4j'
printf '%s\n' '비밀번호는 서버의 .env에 보관됩니다. 채팅이나 공개 저장소에 공유하지 마세요.'
printf '%s\n' '기존 JAR 분석 코드와의 연결은 별도로 구현·확인해야 합니다.'
docker compose ps
