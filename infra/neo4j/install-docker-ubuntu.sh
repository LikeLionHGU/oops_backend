#!/usr/bin/env bash
# Run on the Ubuntu server: sudo bash install-docker-ubuntu.sh
set -euo pipefail

if [ "$EUID" -ne 0 ]; then
  printf '%s\n' '서버에서 sudo bash install-docker-ubuntu.sh 로 실행하세요.'
  exit 1
fi

. /etc/os-release
if [ "$ID" != ubuntu ] || [ "$VERSION_ID" != 22.04 ]; then
  printf '%s\n' '이 설치 도구는 Ubuntu 22.04용입니다. 현재 운영체제에는 실행하지 않습니다.'
  exit 1
fi

if command -v docker >/dev/null 2>&1; then
  docker info >/dev/null
  docker compose version
  printf '%s\n' 'Docker가 이미 준비되어 있습니다. 재설치하지 않습니다.'
  exit 0
fi

# Do not uninstall a runtime that another service may already be using.
for OOPS_PACKAGE in docker.io docker-compose docker-compose-v2 docker-doc docker-buildx podman-docker containerd runc; do
  OOPS_PACKAGE_STATUS="$(dpkg-query -W -f='${Status}' "$OOPS_PACKAGE" 2>/dev/null || true)"
  if [ "$OOPS_PACKAGE_STATUS" = 'install ok installed' ]; then
    printf '기존 패키지 %s 가 있습니다. 자동 제거하지 않습니다. 설치 상태를 먼저 확인하세요.\n' "$OOPS_PACKAGE"
    exit 1
  fi
done

apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y ca-certificates curl openssl unzip
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc

cat > /etc/apt/sources.list.d/docker.sources <<EOF
Types: deb
URIs: https://download.docker.com/linux/ubuntu
Suites: jammy
Components: stable
Architectures: $(dpkg --print-architecture)
Signed-By: /etc/apt/keyrings/docker.asc
EOF

apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable --now docker
docker info >/dev/null
docker compose version
printf '%s\n' 'Docker 설치가 완료되었습니다. sudo bash start-server.sh 로 데이터를 적재하세요.'
