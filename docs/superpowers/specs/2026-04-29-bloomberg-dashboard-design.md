# ALLFOLIO Bloomberg 대시보드 설계

**날짜:** 2026-04-29  
**목표:** 기관투자자 수준의 포트폴리오 지표를 일반 금융 소비자가 직관적으로 이해할 수 있게 제공한다.

---

## 1. 핵심 원칙

1. **자산 분리 원칙** — 유동 금융자산(주식·코인)과 비유동 실물자산(전세·차량)을 동일 계산 단위에 섞지 않는다. 기관 지표는 유동 자산에만 적용한다.
2. **계층형 순자산 구조** — 총 순자산(Net Worth) → 투자 포트폴리오 → 실물자산 순으로 드릴다운한다.
3. **B+C 지표 UX** — 숫자 원본은 보조, 등급(★·우수/양호/주의/위험) + 벤치마크 대비 맥락 비교가 주인공이다.
4. **단계적 구현** — 수익률(Phase 1) → 자산 배분(Phase 2) → 리스크(Phase 3) 순서로 출시한다.

---

## 2. 레이아웃 — 계층형 순자산 대시보드

기존 `/` 메인 페이지를 대시보드로 교체한다.

```
/ (메인 대시보드)
├── [상단] 순자산 바 (Net Worth) — 항상 고정
│     총 순자산 = 투자자산 + 실물자산 - 부채
│     30일 전 대비 변화량·변화율 표시
│
├── [섹션 1] 투자 포트폴리오 (유동 자산)
│     ├── 지표 카드 행 (Phase별 순차 추가)
│     │     Phase 1: 수익률 YTD, MDD, Alpha vs 코스피
│     │     Phase 2: 자산군 비중, 집중도 리스크
│     │     Phase 3: Sharpe Ratio, VaR 95%, 변동성
│     └── 포지션 테이블 (종목·평가액·수익률·비중)
│
└── [섹션 2] 실물·고정 자산 (비유동 자산)
      전세·부동산·차량 카드
      만기 D-day 표시, 만기 임박 시 강조
```

---

## 3. 도메인 모델 변경 (`unified-asset` 모듈)

### 3-1. Asset 필드 추가

```kotlin
// 추가 필드
val maturityDate: LocalDate?,           // 전세 만기일, 채권 만기일 등
val liquidityType: AssetLiquidityType,  // 유동성 구분
```

```kotlin
enum class AssetLiquidityType {
    LIQUID,    // 주식·코인 — 기관 지표 계산 대상
    ILLIQUID,  // 전세·부동산·차량 — Net Worth 합산 전용
}
```

**규칙:** `LIQUID` 자산만 Sharpe·MDD·VaR 계산에 포함. `ILLIQUID`는 Net Worth 합산에만 포함.

### 3-2. AssetType 추가

```kotlin
enum class AssetType {
    STOCK, CRYPTO, REAL_ESTATE,
    JEONSE,   // 전세보증금 (반환 청구권, ILLIQUID)
    VEHICLE, GOLD, CASH, ETC,
}
```

### 3-3. DB 마이그레이션

```sql
ALTER TABLE asset ADD COLUMN maturity_date DATE;
ALTER TABLE asset ADD COLUMN liquidity_type VARCHAR(20) NOT NULL DEFAULT 'LIQUID';
```

`REAL_ESTATE`, `JEONSE`, `VEHICLE` 타입은 기본값을 `ILLIQUID`로 백필한다.

---

## 4. 백엔드 — Snapshot 모듈 확장

### 4-1. 신규 엔티티: `portfolio_metrics_daily`

```kotlin
@Entity
@Table(name = "portfolio_metrics_daily")
class PortfolioMetricsDailyEntity(
    @EmbeddedId val id: SnapshotDailyId,       // portfolioId + date
    // Phase 1: 수익률
    val returnRate1m: BigDecimal,
    val returnRate3m: BigDecimal,
    val returnYtd: BigDecimal,
    val mdd: BigDecimal,
    val alphaVsKospi: BigDecimal?,
    val alphaVsBtc: BigDecimal?,
    // Phase 2: 배분
    val topConcentration: BigDecimal,          // 최대 단일 종목 비중
    val hhiIndex: BigDecimal,                  // 허핀달 지수 (집중도)
    // Phase 3: 리스크
    val sharpeRatio: BigDecimal?,
    val var95: BigDecimal?,
    val volatility: BigDecimal?,
    // 메타
    val dataDays: Int,                         // 계산에 사용된 일수
    val calculatedAt: LocalDateTime,
)
```

### 4-2. 데이터 부족 처리

- **최소 기준 미달 시 null 반환** — 해당 지표 카드는 프론트에서 표시하지 않음.
  - 수익률·MDD·Alpha: 1일 이상
  - Sharpe·VaR·변동성: 10일 이상
- **30일 미만이면 dataWarning 포함** — 계산은 하되 `"dataWarning": "단기 데이터 기반 (12일)"` 를 함께 반환.

### 4-3. 벤치마크 데이터 수집

신규 테이블 `benchmark_daily`:

| 컬럼 | 설명 |
|---|---|
| `index_type` | `KOSPI` / `BTC` |
| `date` | 기준일 |
| `close_value` | 종가 |

- **KOSPI**: KIS API (기존 연동) → 평일 장 마감 후 수집
- **BTC**: Binance API (기존 연동) → BTCUSDT UTC 00:00 종가

### 4-4. 배치 스케줄

| 배치 | 크론 표현식 | 내용 |
|---|---|---|
| 국내 주식 스냅샷 | `0 0 16 * * MON-FRI` | 평일 오후 4시, 당일 종가 기준 |
| 코인 + 전체 지표 합산 | `0 30 0 * * *` | 매일 00:30, UTC 전일 코인 종가 기준 |
| 전세 만기 알림 | `0 0 7 * * *` | 매일 오전 7시, D-30·D-7·D-1 알림 |

### 4-5. 등급 변환 로직

```kotlin
enum class MetricGrade { EXCELLENT, GOOD, WARN, BAD }

fun sharpeToGrade(v: BigDecimal) = when {
    v >= 2.0.bd -> EXCELLENT
    v >= 1.0.bd -> GOOD
    v >= 0.0.bd -> WARN
    else        -> BAD
}

fun mddToGrade(v: BigDecimal) = when {   // v는 음수
    v >= -5.0.bd  -> EXCELLENT
    v >= -15.0.bd -> GOOD
    v >= -30.0.bd -> WARN
    else          -> BAD
}

fun concentrationToGrade(v: BigDecimal) = when {  // v는 0~1
    v <= 0.30.bd -> EXCELLENT
    v <= 0.50.bd -> GOOD
    v <= 0.70.bd -> WARN
    else         -> BAD
}
```

---

## 5. API 설계

### 5-1. 통합 대시보드 엔드포인트

```
GET /api/unified/dashboard
Authorization: Bearer {token}
```

**응답 구조:**

```json
{
  "netWorth": {
    "total": 482300000,
    "liquid": 156300000,
    "illiquid": 350000000,
    "debt": 24000000,
    "monthlyChange": 2140000,
    "monthlyChangeRate": 0.44
  },
  "portfolio": {
    "totalValue": 156300000,
    "metrics": {
      "returnYtd": {
        "value": 12.4,
        "grade": "EXCELLENT",
        "stars": 4,
        "benchmarkVsKospi": 4.3,
        "dataWarning": null
      },
      "mdd": {
        "value": -8.3,
        "grade": "GOOD",
        "stars": 3,
        "benchmarkVsKospi": 5.9,
        "dataWarning": null
      },
      "sharpe": {
        "value": 1.42,
        "grade": "EXCELLENT",
        "stars": 4,
        "dataWarning": "단기 데이터 기반 (18일)"
      },
      "var95": {
        "value": -4200000,
        "grade": "WARN",
        "stars": 2,
        "dataWarning": null
      }
    },
    "allocation": [
      { "type": "CRYPTO", "ratio": 0.731, "grade": "WARN" },
      { "type": "STOCK",  "ratio": 0.269, "grade": "GOOD" }
    ],
    "positions": [           // LIQUID 자산만 포함 (ILLIQUID는 realAssets에)
      {
        "name": "삼성전자",
        "symbol": "005930",
        "type": "STOCK",
        "currentValue": 42100000,
        "returnRate": 7.2,
        "weight": 0.269
      }
    ]
  },
  "realAssets": [
    {
      "id": "...",
      "name": "서울 마포구 전세",
      "type": "JEONSE",
      "value": 320000000,
      "maturityDate": "2026-08-31",
      "daysUntilMaturity": 489
    }
  ]
}
```

---

## 6. 프론트엔드

### 6-1. 데이터 페칭

```typescript
// 대시보드 전체 데이터 — 단일 쿼리
const { data } = useQuery({
  queryKey: ['dashboard'],
  queryFn: () => api.get('/api/unified/dashboard'),
  staleTime: 60_000,  // 1분 캐시
})

// 실시간 가격 — 기존 SSE 재활용
const prices = useLivePrices()
```

### 6-2. 지표 카드 컴포넌트 인터페이스

```typescript
interface MetricCardProps {
  label: string
  value: number | string
  grade: 'EXCELLENT' | 'GOOD' | 'WARN' | 'BAD'
  stars: number            // 1~5
  benchmarkText?: string   // "코스피 대비 +4.3%p"
  dataWarning?: string     // "단기 데이터 기반 (18일)"
}
```

### 6-3. 실물자산 카드 만기 표시

- `daysUntilMaturity > 90`: 정상 표시
- `daysUntilMaturity <= 30`: 주황색 강조
- `daysUntilMaturity <= 7`: 빨간색 강조 + "만기 임박" 배지

---

## 7. 구현 단계 (Phase)

| Phase | 내용 | 주요 변경 파일 |
|---|---|---|
| **1** | DB 마이그레이션, 도메인 모델 변경, 순자산 바, 수익률·MDD·Alpha 카드 | `Asset.kt`, `PortfolioMetricsDailyEntity`, `DashboardController`, `page.tsx` |
| **2** | 자산 배분 카드, 전세 만기 D-day, 만기 알림 스케줄러 | `MaturityAlertScheduler`, 프론트 실물자산 섹션 |
| **3** | Sharpe·VaR·변동성 카드 | `RiskMetricsCalculator` |

---

## 8. 범위 밖 (이번 스펙 제외)

- 피어 비교 (사용자 집계) — 사용자가 충분히 쌓인 후 추가
- 전세 신용위험·전세보증보험·시세 비교 — 향후 스펙
- 알림 푸시 (앱 푸시·이메일) — 현재는 UI 내 표시만
