#!/bin/bash
set -e

# Allfolio 배포 스크립트
# 사용법: ./deploy.sh <서버공인IP>

SERVER_IP="${1:-$(grep SERVER_IP .env.prod | cut -d= -f2)}"

if [ -z "$SERVER_IP" ]; then
  echo "❌ 서버 IP를 입력하세요: ./deploy.sh <IP>"
  exit 1
fi

echo "🚀 Allfolio 배포 시작 (서버: $SERVER_IP)"

# 1. Spring Boot JAR 빌드
echo "📦 백엔드 빌드 중..."
cd allfolio-backend
./gradlew :backend-app:bootJar -q
cd ..

# 2. Keycloak realm에 서버 IP 추가 (기존 localhost는 유지)
echo "🔑 Keycloak realm 업데이트 중..."
REALM_FILE="allfolio-backend/infra/keycloak/allfolio-realm.json"
# redirectUris에 서버 IP 추가 (중복 방지)
if ! grep -q "http://${SERVER_IP}:3000" "$REALM_FILE"; then
  # jq로 redirectUris 배열에 추가
  TMP=$(mktemp)
  jq --arg ip "http://${SERVER_IP}:3000/*" \
     --arg origin "http://${SERVER_IP}:3000" \
     '(.clients[] | select(.clientId == "allfolio-frontend") | .redirectUris) += [$ip] |
      (.clients[] | select(.clientId == "allfolio-frontend") | .webOrigins) += [$origin]' \
     "$REALM_FILE" > "$TMP" && mv "$TMP" "$REALM_FILE"
  echo "  ✓ redirectUri 추가: http://${SERVER_IP}:3000/*"
fi

# 3. 백엔드 Docker 이미지 빌드
echo "🐳 백엔드 이미지 빌드 중..."
docker build -t allfolio-backend:latest allfolio-backend/

# 4. 프론트엔드 Docker 이미지 빌드
echo "🐳 프론트엔드 이미지 빌드 중..."
docker build -t allfolio-frontend:latest frontend/allfolio_app/

# 5. 배포
echo "🚢 컨테이너 배포 중..."
SERVER_IP="$SERVER_IP" docker compose -f docker-compose.prod.yml --env-file .env.prod up -d

echo ""
echo "✅ 배포 완료!"
echo "   프론트엔드: http://${SERVER_IP}:3000"
echo "   백엔드 API: http://${SERVER_IP}:8090"
echo "   Keycloak:   http://${SERVER_IP}:8180"
