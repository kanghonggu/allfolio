# ALLFOLIO 배포 가이드 (AWS t3.medium + Vercel)

## 아키텍처

```
사용자
  │
  ├─▶ Vercel (Next.js)              — 무료
  │
  └─▶ AWS EC2 t3.medium 서울        — 약 52,000원/월
        │
        └─▶ Nginx (80/443)
              ├─▶ api.allfolio.me      →  Spring Boot:8090
              ├─▶ auth.allfolio.me     →  Keycloak:8080
              └─▶ monitor.allfolio.me  →  Grafana:3000
```

---

## 1단계 — AWS EC2 생성

1. AWS 콘솔 → EC2 → 인스턴스 시작
2. **AMI**: Ubuntu Server 22.04 LTS (64비트 x86)
3. **인스턴스 유형**: t3.medium
4. **스토리지**: 30GB gp3
5. **보안 그룹** 인바운드 규칙:

| 유형 | 포트 | 소스 |
|------|------|------|
| SSH | 22 | 내 IP |
| HTTP | 80 | 0.0.0.0/0 |
| HTTPS | 443 | 0.0.0.0/0 |

6. 키 페어 생성 후 `.pem` 파일 저장
7. **탄력적 IP** 연결 (EC2 → 탄력적 IP → 할당 후 인스턴스에 연결)

---

## 2단계 — DNS 설정 (allfolio.me)

도메인 구매처(가비아/Cloudflare 등) DNS 관리에서 A 레코드 3개 추가:

| 호스트명 | 유형 | 값 |
|----------|------|-----|
| `api` | A | EC2 탄력적 IP |
| `auth` | A | EC2 탄력적 IP |
| `monitor` | A | EC2 탄력적 IP |

> TTL은 300초(5분)로 설정하면 빠르게 전파됨

DNS 전파 확인:
```bash
dig api.allfolio.me +short
dig auth.allfolio.me +short
dig monitor.allfolio.me +short
# → 모두 EC2 IP가 나와야 함
```

---

## 3단계 — 서버 초기 세팅

```bash
# EC2 SSH 접속
ssh -i your-key.pem ubuntu@EC2_IP

# 레포 클론
git clone https://github.com/hong9/allfolio.git ~/allfolio
cd ~/allfolio

# 초기 세팅 (Docker, Java 21, Certbot, UFW, 2GB Swap)
bash server-setup-aws.sh

# 재접속 (docker 그룹 적용)
exit && ssh -i your-key.pem ubuntu@EC2_IP
```

---

## 4단계 — SSL 인증서 발급

> ⚠️ DNS가 EC2 IP로 전파된 후 실행 (2단계 확인 필수)

```bash
cd ~/allfolio
bash scripts/ssl-issue.sh allfolio.me goguma249@gmail.com
```

발급 결과:
- `api.allfolio.me` + `auth.allfolio.me` → `/etc/letsencrypt/live/api.allfolio.me/`
- `monitor.allfolio.me` → `/etc/letsencrypt/live/monitor.allfolio.me/`

---

## 5단계 — 환경변수 설정

```bash
cd ~/allfolio
cp .env.prod.example .env.prod
vi .env.prod
```

`.env.prod` 작성 내용:
```env
POSTGRES_PASSWORD=매우_강력한_비밀번호
KEYCLOAK_ADMIN_PASSWORD=keycloak_관리자_비밀번호
KEYCLOAK_HOSTNAME=auth.allfolio.me
DOCKER_IMAGE_BACKEND=ghcr.io/hong9/allfolio-backend:latest
DOMAIN=allfolio.me
FRONTEND_DOMAIN=allfolio-app.vercel.app
GRAFANA_ADMIN_PASSWORD=grafana_비밀번호
```

---

## 6단계 — 첫 배포

```bash
cd ~/allfolio

# GitHub Actions 세팅 전 첫 배포는 로컬 빌드
cd allfolio-backend && ./gradlew :backend-app:bootJar -x test && cd ..
docker build -t allfolio-backend:latest allfolio-backend/

# 서비스 시작
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d

# 상태 확인 (모든 컨테이너 healthy 확인)
docker compose -f docker-compose.prod.yml ps
```

접속 확인:
- `https://api.allfolio.me/actuator/health` → `{"status":"UP"}`
- `https://auth.allfolio.me` → Keycloak 로그인 화면
- `https://monitor.allfolio.me` → Grafana 로그인 화면

---

## 7단계 — Vercel 배포 (프론트엔드)

1. [vercel.com](https://vercel.com) → GitHub 레포 import
2. **Root Directory**: `frontend/allfolio_app`
3. **Environment Variables** 추가:

| 변수명 | 값 |
|--------|-----|
| `NEXT_PUBLIC_KEYCLOAK_URL` | `https://auth.allfolio.me` |
| `NEXT_PUBLIC_KEYCLOAK_REALM` | `allfolio` |
| `NEXT_PUBLIC_KEYCLOAK_CLIENT_ID` | `allfolio-frontend` |
| `NEXT_PUBLIC_API_BASE_URL` | `https://api.allfolio.me` |

4. Deploy 클릭 → 배포 완료
5. Vercel 도메인 확인 후 `.env.prod`의 `FRONTEND_DOMAIN` 업데이트

> **Keycloak redirect URI 업데이트 필요**: Keycloak Admin → Clients → allfolio-frontend → Valid redirect URIs에 `https://allfolio-app.vercel.app/*` 추가

---

## 8단계 — GitHub Actions 자동 배포

GitHub 레포 → Settings → Secrets and variables → Actions → New repository secret:

| Secret | 값 |
|--------|-----|
| `EC2_HOST` | EC2 탄력적 IP |
| `EC2_SSH_KEY` | `.pem` 파일 전체 내용 (`-----BEGIN RSA PRIVATE KEY-----` ~ `-----END` 포함) |

이후 `main` 브랜치 push → 자동 빌드 → EC2 자동 배포

---

## 유지보수

```bash
# 로그 확인
docker compose -f docker-compose.prod.yml logs -f backend
docker compose -f docker-compose.prod.yml logs -f keycloak

# 수동 재배포
cd ~/allfolio && bash deploy.sh

# SSL 갱신 테스트 (certbot 90일마다 자동 갱신됨)
sudo certbot renew --dry-run

# 전체 서비스 재시작
docker compose -f docker-compose.prod.yml --env-file .env.prod restart
```

---

## 월 비용 요약

| 항목 | 비용 |
|------|------|
| EC2 t3.medium 서울 | ~$34 |
| EBS 30GB gp3 | ~$2.4 |
| 탄력적 IP (인스턴스 연결 시) | 무료 |
| 데이터 전송 (~1GB/월) | 무료 |
| allfolio.me 도메인 | ~$12/년 |
| Vercel 프론트엔드 | 무료 |
| **합계** | **~$36/월 ≈ 52,000원** |
