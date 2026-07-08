# ALLFOLIO

> **Bloomberg Terminal 수준의 기관투자자 지표를 일반 금융 소비자가 쉽게 볼 수 있도록**
>
> 멀티 증권사·거래소 데이터를 통합하고, 실시간 시세와 수익률을 한 화면에서 관리하는 개인 통합 자산 관리 시스템

---

## 목표

개인 투자자는 주식 계좌·암호화폐·부동산·현금 자산이 여러 플랫폼에 분산되어 있어 전체 포트폴리오를 한눈에 보기 어렵습니다. ALLFOLIO는 이 문제를 해결하기 위해 만들었습니다.

- 여러 증권사·거래소 데이터를 **하나의 화면**으로 통합
- 단순 잔고 조회가 아닌 **수익률·리스크·자산배분 분석**까지
- 기관 투자자가 쓰는 지표(Sharpe, MDD, VaR, HHI)를 **일반인도 이해할 수 있는 언어**로

---

## 주요 기능

### 자산 통합
- **멀티 브로커 연동** — Binance, KIS(한국투자증권), 키움증권, 삼성증권, 토스증권, Upbit, Bithumb, Coinone, Bybit, OKX
- **수동 자산 등록** — 부동산(소유/전세/월세/분양권), 자동차, 금, 현금, 비상장주식 등
- **국내 주식 거래내역 직접 입력** — 종목 자동완성(Yahoo Finance 검색), 평균단가 자동계산
- **CSV 임포트** — 증권사 거래내역 파일 일괄 등록

### 실시간 시세
- **WebSocket 연동** — Binance, Upbit, Bithumb, Coinone, Bybit, OKX, KIS 실시간 체결가
- **Yahoo Finance** — 국내주식(KOSPI/KOSDAQ) 및 해외주식 현재가 자동 조회
- **SSE(Server-Sent Events)** — 브라우저에 실시간 PnL 스트리밍

### 수익률 계산
- **가중평균법(Weighted Average Cost)** 기반 평균 매입단가
- **FIFO** 포지션 계산 지원
- ILLIQUID 자산(부동산·차량 등)은 총액 기준, LIQUID 자산은 단가×수량 기준으로 분리 계산

### 보고서 (Bloomberg Phase 1~3)
| 보고서 | 주요 지표 |
|--------|----------|
| 포트폴리오 요약 | NAV, 총 매입원가, 미실현손익, 수익률, 자산 유형별 비중 |
| 자산 배분 | 유형별·통화별 파이차트, HHI 집중도, 상위 보유 자산 |
| 수익률 분석 | 기간별 수익률(1W/1M/3M/YTD/1Y), 누적 수익률 시계열, TWR |
| 포지션 & 손익 | 자산별 평균매입가·현재가·미실현손익·수익률, 정렬·필터 |
| 리스크 분석 | 변동성(일/연환산), VaR 95%, MDD, Sharpe Ratio, Calmar Ratio |
| 벤치마크 비교 | 내 포트폴리오 vs S&P 500 / BTC / KOSPI 초과수익(알파) |

### 이벤트 기반 인프라
- **Outbox Pattern** — 거래 저장 + 이벤트 발행을 단일 트랜잭션으로 보장
- **Kafka + DLQ** — 비동기 이벤트 처리, 최대 5회 재시도 후 Dead Letter 보존
- **Redis Cache** — FX 환율 캐시(1분 갱신), 포지션 캐시
- **멱등성** — brokerType + externalTradeId 복합 유니크 인덱스로 중복 방지

---

## 기술 스택

### Backend
| 항목 | 내용 |
|------|------|
| 언어/프레임워크 | Kotlin / Spring Boot 3 |
| 데이터베이스 | PostgreSQL 16 |
| 캐시 | Redis 7 |
| 메시지 큐 | Apache Kafka 7.6 |
| 인증 | Allfolio JWT (BCrypt + Refresh Token) |
| HTTP 클라이언트 | WebClient (WebFlux), OkHttp |
| 빌드 | Gradle (멀티모듈) |

### Frontend
| 항목 | 내용 |
|------|------|
| 프레임워크 | Next.js 15 (App Router) |
| 언어 | TypeScript |
| 스타일 | Tailwind CSS |
| 상태 관리 | TanStack Query v5 |
| 차트 | Recharts |
| 인증 | Allfolio Auth API |

---

## 아키텍처

```
┌─────────────────────────────────────────────────────┐
│                    Frontend (Next.js)                │
│  /unified  /accounts  /reports  /reports/summary ... │
└────────────────────┬────────────────────────────────┘
                     │ REST / SSE
┌────────────────────▼────────────────────────────────┐
│                  backend-app                         │
│  ┌──────────┐  ┌──────────┐  ┌───────────────────┐  │
│  │  Broker  │  │  Market  │  │    Dashboard /     │  │
│  │  OAuth   │  │  WebSocket│  │    Report API     │  │
│  │  Adapters│  │  + SSE   │  │                   │  │
│  └──────────┘  └──────────┘  └───────────────────┘  │
│  ┌──────────────────────────────────────────────┐    │
│  │  Outbox → Kafka → DLQ → 재처리               │    │
│  └──────────────────────────────────────────────┘    │
└────────────────────┬────────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────────┐
│                 unified-asset                        │
│  Account → SyncAdapter → Asset 생성/갱신             │
│  ReportService (Summary / Allocation / Performance   │
│                 Risk / Positions / Benchmark)        │
└───────────┬────────────────────────────────────────┘
            │
    ┌───────┴────────┐
    │  PostgreSQL 16  │  Redis 7  │  Kafka  │  JWT Auth
    └────────────────┘
```

### 멀티모듈 구조

```
allfolio-backend/
├── backend-app       # 진입점 (브로커 연동, Kafka, WebSocket, SSE, FX, 대시보드)
├── unified-asset     # 통합 자산 관리 (계좌, 자산 CRUD, 동기화, 보고서)
├── trade             # 거래 도메인 (TradeRaw, Outbox Event)
├── snapshot          # 스냅샷 계산 (Position, Performance, Risk 일별 집계)
├── market-data       # 시세 어댑터 (Binance/KIS WebSocket)
├── portfolio         # 포트폴리오 도메인
├── asset             # 자산 정의 도메인
├── common            # 공통 (BaseEntity, Money)
├── risk              # 리스크 계산 엔진
├── report            # 보고서 도메인
├── benchmark         # 벤치마크 (S&P 500, BTC, KOSPI)
└── esg               # ESG 스코어 (예정)
```

---

## 브로커 연동 현황

| 브로커 | 유형 | 연동 방식 | 상태 |
|--------|------|----------|------|
| Binance | 해외 거래소 | REST API + WebSocket | 완료 |
| KIS (한국투자증권) | 국내 증권사 | OAuth2 + WebSocket | 완료 |
| 키움증권 | 국내 증권사 | OAuth2 | 완료 |
| 삼성증권 | 국내 증권사 | OAuth2 | 완료 |
| 토스증권 | 국내 증권사 | OAuth2 | 완료 |
| Upbit | 국내 거래소 | WebSocket | 완료 |
| Bithumb | 국내 거래소 | WebSocket | 완료 |
| Coinone | 국내 거래소 | WebSocket | 완료 |
| Bybit | 해외 거래소 | WebSocket | 완료 |
| OKX | 해외 거래소 | WebSocket | 완료 |

---

## API 목록

```
# 계좌 & 자산
POST   /api/auth/register                 # 회원가입
POST   /api/auth/login                    # 로그인
POST   /api/auth/refresh                  # access token 갱신
POST   /api/auth/logout                   # refresh token 폐기
GET    /api/auth/me                       # 내 계정 정보

POST   /api/unified/accounts                # 계좌 생성
GET    /api/unified/accounts                # 계좌 목록
DELETE /api/unified/accounts/{id}           # 계좌 삭제
POST   /api/unified/accounts/{id}/sync      # 계좌 동기화 (자산 갱신)
GET    /api/unified/accounts/{id}/assets    # 보유 자산 목록
POST   /api/unified/accounts/{id}/assets    # 수동 자산 추가
POST   /api/unified/accounts/{id}/csv       # CSV 임포트

# 국내 주식 거래내역
GET    /api/unified/accounts/{id}/stock-trades       # 거래내역 조회
POST   /api/unified/accounts/{id}/stock-trades       # 거래내역 추가
DELETE /api/unified/accounts/{id}/stock-trades/{tid} # 거래내역 삭제

# 종목 검색 (Yahoo Finance 프록시)
GET    /api/unified/stocks/search?q={query}

# 포트폴리오
GET    /api/unified/portfolio               # 통합 포트폴리오 조회
GET    /api/unified/dashboard               # 대시보드 KPI

# 보고서
GET    /api/reports/summary
GET    /api/reports/allocation
GET    /api/reports/performance?period=1M
GET    /api/reports/risk
GET    /api/reports/positions
GET    /api/reports/benchmark?period=YTD

# 실시간 시세
GET    /api/prices/stream                   # SSE 가격 스트림
GET    /api/pnl/stream                      # SSE 실시간 PnL

# 브로커 OAuth
GET    /api/broker/{broker}/authorize
GET    /api/broker/{broker}/callback

# 거래 (Outbox 기반)
POST   /api/trades
GET    /api/portfolios/{id}/positions
```

---

## 프론트엔드 페이지

```
/                                      # 랜딩 (로그인 시 /unified 리다이렉트)
/login                                 # 로그인 / 회원가입
/unified                               # 통합 자산 대시보드 (KPI 카드, 자산 요약)
/unified/accounts                      # 계좌 목록
/unified/accounts/new                  # 계좌 추가
/unified/accounts/{id}                 # 계좌 상세 (자산별 매입가·현재가·수익률)
/unified/accounts/{id}/trades          # 국내주식 거래내역 (종목 자동완성)
/unified/accounts/{id}/csv             # CSV 일괄 임포트
/unified/reports                       # 보고서 허브
/unified/reports/summary               # 포트폴리오 요약
/unified/reports/allocation            # 자산 배분 (파이차트, HHI)
/unified/reports/performance           # 수익률 분석 (기간별, 시계열)
/unified/reports/risk                  # 리스크 지표 (VaR, MDD, Sharpe)
/unified/reports/positions             # 포지션 & 손익
/unified/reports/benchmark             # 벤치마크 비교 (S&P 500, BTC, KOSPI)
```

---

## 실행 방법

### Lightweight Free Deployment

Kafka/Redis 없이 MVP를 무료/경량 배포하려면 `docs/DEPLOY_FREE.md`를 참고하세요. 추천 조합은 Vercel frontend, Render backend, Neon Postgres입니다.

프로덕션 비밀값을 GitHub Secrets에서 Render로 동기화하려면 `docs/GITHUB_SECRETS_ENV.md`를 참고하세요.

### 사전 준비
- Docker, Java 21, Node.js 20+

### 인프라 실행
```bash
cd allfolio-backend
cp ../.env.example ../.env
docker compose --env-file ../.env up -d   # PostgreSQL, Redis, Kafka
```

### 백엔드 실행
```bash
cd allfolio-backend
./gradlew :backend-app:bootJar -x test
java -jar backend-app/build/libs/backend-app-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=local
# → http://localhost:8090
```

### 프론트엔드 실행
```bash
cd frontend/allfolio_app
npm install
npm run dev
# → http://localhost:3000
```

### 환경 변수

민감정보는 실제 env 파일에만 넣고 커밋하지 않습니다.

```bash
cp .env.example .env                 # 로컬 Docker/backend
cp .env.prod.example .env.prod       # self-hosted production
cp .env.render.example .env.render   # Render dashboard 입력용 참고
cp frontend/allfolio_app/.env.local.example frontend/allfolio_app/.env.local
```

**backend** (`.env`, `.env.prod`, Render dashboard)
```
ALLFOLIO_JWT_SECRET=dev-only-change-me-dev-only-change-me
APP_ENCRYPTION_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=
ACCESS_TOKEN_MINUTES=15
REFRESH_TOKEN_DAYS=30
ALLOWED_ORIGINS=http://localhost:3000
BINANCE_API_KEY=...
BINANCE_API_SECRET=...
KIS_APP_KEY=...
KIS_APP_SECRET=...
KAFKA_BOOTSTRAP_SERVERS=localhost:9092   # kafka.enabled=false 로 비활성화 가능
```

**frontend** (`.env.local`)
```
NEXT_PUBLIC_API_BASE_URL=http://localhost:8090
```

---

## 구현 현황

| 항목 | 상태 |
|------|------|
| 멀티 브로커 OAuth 연동 (KIS, 키움, 삼성, 토스) | 완료 |
| 암호화폐 거래소 WebSocket (Binance, Upbit, Bithumb, Coinone, Bybit, OKX) | 완료 |
| Outbox Pattern + Kafka + DLQ | 완료 |
| JWT 인증 (Allfolio 자체 인증) | 완료 |
| 통합 자산 관리 (계좌, 자산 CRUD, Sync) | 완료 |
| 국내주식 거래내역 입력 + 평균단가 계산 | 완료 |
| Yahoo Finance 실시간 시세 (KOSPI/KOSDAQ/해외) | 완료 |
| SSE 실시간 PnL 스트리밍 | 완료 |
| 보고서 6종 (Summary/Allocation/Performance/Risk/Positions/Benchmark) | 완료 |
| Bloomberg Dashboard (KPI 카드, 수익률·배분·리스크 시각화) | 완료 |
| 계좌 상세 매입가·현재가·수익률 표시 | 완료 |
| ILLIQUID 자산 수익률 분리 계산 | 완료 |
| CSV 임포트 | 완료 |
| 순자산 추이 보고서 | 예정 |
| 세금 계산기 (양도세, 금투세, 배당세) | 예정 |
| 목표 자산 트래커 | 예정 |
| 배당금 보고서 | 예정 |
| ESG 스코어 | 예정 |

## 성능 실측

2026-07-08, 운영 배포 환경(Render Free — 0.1 vCPU / 512MB / 단일 인스턴스, 싱가포르 리전)에 대해
[k6 부하 테스트](k6/README.md)를 실행한 결과다. 원시 결과 JSON은 실행 시 `k6/results/`에
생성된다(저장소에는 커밋하지 않음).

| 엔드포인트 | 최대 VU | 지속 TPS | p50 | p95 | 에러율 |
|---|---:|---:|---:|---:|---:|
| `GET /api/reports/allocation` | 800 | 21.0 | 13.4s | 42.8s | 3.8% |
| `GET /api/portfolios/{id}/positions` (Redis 캐시) | 250 | 20.3 | 2.6s | 26.0s | 0% |
| `GET /api/unified/dashboard` | 100 | 19.1 | 1.0s | 3.3s | 0% |

- 처리량이 엔드포인트·동시성과 무관하게 **약 20 req/s로 수렴** — 병목은 코드 경로가 아니라
  무료 플랜 인스턴스(0.1 vCPU)다. 저부하 구간 응답 지연은 130~190ms.
- 위 수치는 무료 플랜 실측값이며, 더 높은 처리량 검증은 유료 플랜 또는 수평 확장 환경에서
  재실측이 필요하다.
