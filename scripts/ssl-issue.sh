#!/bin/bash
# SSL 인증서 발급 (Let's Encrypt)
# 사용: bash scripts/ssl-issue.sh yourdomain.com your@email.com

DOMAIN=$1
EMAIL=$2

if [ -z "$DOMAIN" ] || [ -z "$EMAIL" ]; then
  echo "사용법: bash scripts/ssl-issue.sh yourdomain.com your@email.com"
  exit 1
fi

echo "=== SSL 인증서 발급: $DOMAIN ==="

# api + auth 인증서 (SAN으로 하나로 묶음)
sudo certbot certonly --standalone \
  -d "api.${DOMAIN}" \
  -d "auth.${DOMAIN}" \
  --email "$EMAIL" \
  --agree-tos \
  --non-interactive

# monitor 인증서 (별도)
sudo certbot certonly --standalone \
  -d "monitor.${DOMAIN}" \
  --email "$EMAIL" \
  --agree-tos \
  --non-interactive

echo ""
echo "SSL 발급 완료!"
echo "   api/auth 인증서: /etc/letsencrypt/live/api.${DOMAIN}/"
echo "   monitor 인증서:  /etc/letsencrypt/live/monitor.${DOMAIN}/"
