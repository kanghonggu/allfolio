#!/bin/bash
# AWS EC2 Ubuntu 22.04 — t3.medium 초기 세팅
# EC2 생성 후 SSH 접속 → bash server-setup-aws.sh 실행

set -e
echo "=== ALLFOLIO AWS 서버 세팅 시작 ==="

# ── 시스템 업데이트 ─────────────────────────────
echo "[1/6] 시스템 업데이트..."
sudo apt-get update -q && sudo apt-get upgrade -y -q

# ── Docker 설치 ─────────────────────────────────
echo "[2/6] Docker 설치..."
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER
sudo systemctl enable docker

# ── Docker Compose 플러그인 ──────────────────────
echo "[3/6] Docker Compose 설치..."
sudo apt-get install -y docker-compose-plugin

# ── Java 21 (백엔드 로컬 빌드 옵션) ──────────────
echo "[4/6] Java 21 설치..."
sudo apt-get install -y openjdk-21-jdk-headless
java -version

# ── Certbot (Let's Encrypt SSL) ──────────────────
echo "[5/6] Certbot 설치..."
sudo apt-get install -y certbot

# ── 방화벽 설정 ─────────────────────────────────
echo "[6/6] 방화벽(ufw) 설정..."
sudo ufw allow 22/tcp    # SSH
sudo ufw allow 80/tcp    # HTTP (Let's Encrypt + 리디렉트)
sudo ufw allow 443/tcp   # HTTPS
sudo ufw --force enable

# ── Swap 추가 (t3.medium 4GB지만 여유 확보) ──────
echo "[+] Swap 2GB 추가..."
if [ ! -f /swapfile ]; then
  sudo fallocate -l 2G /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile
  sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
fi

echo ""
echo "=== 세팅 완료! ==="
echo ""
echo "다음 단계:"
echo "  1. 새 SSH 세션으로 재접속 (docker 그룹 적용)"
echo "  2. 레포 클론: git clone https://github.com/YOUR_ID/allfolio.git"
echo "  3. .env.prod 파일 작성: cp .env.prod.example .env.prod && vi .env.prod"
echo "  4. SSL 발급: bash scripts/ssl-issue.sh your-domain.com"
echo "  5. 배포: bash deploy.sh"
