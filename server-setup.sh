#!/bin/bash
# OCI Ubuntu 22.04 ARM VM 초기 세팅 스크립트
# VM에 SSH 접속 후 실행: bash server-setup.sh

set -e

echo "🔧 시스템 업데이트..."
sudo apt-get update -q && sudo apt-get upgrade -y -q

echo "🐳 Docker 설치..."
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER

echo "🔧 Docker Compose 설치..."
sudo apt-get install -y docker-compose-plugin

echo "🛠 유틸리티 설치..."
sudo apt-get install -y git jq

echo "🔥 방화벽 포트 오픈 (ufw)..."
sudo ufw allow 22/tcp    # SSH
sudo ufw allow 3000/tcp  # Next.js
sudo ufw allow 8090/tcp  # Spring Boot API
sudo ufw allow 8180/tcp  # Keycloak
sudo ufw --force enable

echo ""
echo "✅ 세팅 완료!"
echo "⚠️  docker 그룹 적용을 위해 다시 SSH 접속하세요."
echo ""
echo "다음 단계:"
echo "  1. 로컬에서 프로젝트 업로드: rsync -avz --exclude node_modules --exclude .git --exclude build --exclude .gradle . ubuntu@<IP>:~/allfolio/"
echo "  2. VM에서: cd ~/allfolio && cp .env.prod.example .env.prod && nano .env.prod"
echo "  3. VM에서: ./deploy.sh <서버IP>"
