#!/bin/bash
# ALLFOLIO 서버 배포 스크립트
# 서버에서 실행: bash deploy.sh
set -e

echo "=== ALLFOLIO 배포 시작 ==="

# .env.prod 확인
if [ ! -f ".env.prod" ]; then
  echo "❌ .env.prod 파일이 없습니다. .env.prod.example을 복사해서 만들어주세요."
  exit 1
fi

# 최신 코드 pull
echo "[1/4] 코드 업데이트..."
git pull origin main

# 백엔드 이미지 — GitHub Actions가 빌드한 이미지 사용 or 직접 빌드
DOCKER_IMAGE=$(grep DOCKER_IMAGE_BACKEND .env.prod | cut -d= -f2)

if echo "$DOCKER_IMAGE" | grep -q "ghcr.io"; then
  echo "[2/4] GitHub Container Registry에서 이미지 pull..."
  docker pull "$DOCKER_IMAGE"
else
  echo "[2/4] 백엔드 로컬 빌드..."
  cd allfolio-backend
  ./gradlew :backend-app:bootJar -x test -q
  cd ..
  docker build -t allfolio-backend:latest allfolio-backend/
fi

echo "[3/4] 컨테이너 재시작..."
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --remove-orphans

echo "[4/4] 헬스체크..."
sleep 10
docker compose -f docker-compose.prod.yml ps

DOMAIN_VAL=$(grep '^DOMAIN=' .env.prod | cut -d= -f2)
echo ""
echo "=== 배포 완료! ==="
echo "  API:      https://api.${DOMAIN_VAL}"
echo "  Keycloak: https://auth.${DOMAIN_VAL}"
echo "  Monitor:  https://monitor.${DOMAIN_VAL}"
