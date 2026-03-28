# ALLFOLIO

멀티 증권사 데이터를 통합하여 포트폴리오를 계산하고 시각화하는 **이벤트 기반 금융 리포트 시스템**

---

## 🚀 Overview

ALLFOLIO는 Binance, 증권사 API 데이터를 통합하여
실시간에 가까운 포트폴리오 상태와 수익률(PnL)을 계산하는 시스템입니다.

단순 CRUD가 아닌 **Event-driven architecture 기반 금융 계산 엔진**을 목표로 설계되었습니다.

---

## 🧩 Key Features

* 멀티 브로커 연동 (Binance, 증권사 API)
* 실시간 Snapshot 기반 포트폴리오 계산
* FIFO / AvgCost 포지션 계산 지원
* KRW / USDT 통화 통합 (자동 환율 적용)
* Outbox Pattern + Kafka 이벤트 처리
* DLQ 기반 장애 복구 및 재처리
* Redis Cache-Aside 전략 적용
* 프론트 대시보드를 통한 데이터 검증

---

## 🏗️ Architecture

```text
Broker API
  → TradeRaw
  → Outbox (DB)
      → AFTER_COMMIT
          → Snapshot
          → Kafka Publish
              → Consumer (확장 가능)

+ Redis Cache (Position / FX)
+ DLQ (Redis + Kafka)
```

---

## ⚙️ Tech Stack

### Backend

* Kotlin / Spring Boot
* PostgreSQL
* Redis
* Kafka

### Frontend

* Next.js
* TypeScript
* Tailwind CSS
* TanStack Query

---

## 📊 Performance

* Trade 처리: **3,000+ TPS**
* Snapshot 생성: **100K / 555ms**
* Redis 기반 캐싱으로 DB 부하 최소화

---

## 💡 Core Design

### 1. Event-driven Architecture

* Outbox Pattern 기반 이벤트 처리
* AFTER_COMMIT으로 데이터 정합성 보장

### 2. Financial Calculation Engine

* FIFO / AvgCost 동시 지원
* 포지션 lot 기반 계산 구조

### 3. Fault Tolerance

* DLQ + Retry 구조
* Kafka + DB 이중 안전 구조

### 4. Currency Normalization

* USDT → KRW 자동 환산
* Redis 기반 환율 캐싱

---

## 📡 API Examples

```http
GET /portfolio/summary
GET /portfolio/{id}/positions
GET /trades
```

---

## 🖥️ Frontend

간단한 대시보드를 통해 다음을 확인할 수 있습니다:

* 총 자산 / 수익률
* 자산 비중 (차트)
* 거래 내역

---

## 📌 What I Focused On

* 금융 데이터 정합성 (PnL, 평균단가)
* 고성능 처리 (TPS, 캐싱)
* 확장 가능한 구조 (멀티 브로커)
* 장애 대응 (DLQ, 재처리)

---

## 🔥 Lessons Learned

* 단순 CRUD가 아닌 이벤트 기반 설계의 중요성
* 금융 데이터에서 계산 정확도의 중요성
* 캐시 전략이 성능에 미치는 영향
* 분산 시스템에서의 장애 대응 설계

---

## 📷 Screenshots

> (여기에 실제 UI 캡처 추가)

---

## 🛠️ Getting Started

```bash
# backend
./gradlew bootRun

# frontend
cd frontend/allfolio_app
npm install
npm run dev
```

---

## 📈 Future Work

* 실시간 시세 WebSocket 연동
* 실시간 PnL 계산
* 알림 시스템
* 세금 계산 로직

---

## 👨‍💻 Author

* ALLFOLIO 프로젝트 개발
