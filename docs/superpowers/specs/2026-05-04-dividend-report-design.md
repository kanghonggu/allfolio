# 배당금 보고서 설계

**날짜:** 2026-05-04  
**상태:** 승인됨

---

## 요약

기존 `StockTrade`의 `DIVIDEND` 거래 타입으로 기록된 배당 수령 내역을 집계·시각화하는 보고서. 백엔드 신규 서비스 + 프론트엔드 신규 페이지로 구성하며, 기존 보고서(performance, risk 등)와 동일한 아키텍처 패턴을 따른다.

---

## 데이터 소스

테이블: `ua_stock_trades`  
필터: `trade_type = 'DIVIDEND' AND user_id = ?`  
주요 컬럼:
- `traded_at` — 배당 수령일
- `stock_name` — 종목명
- `symbol` — 티커 (nullable)
- `total_amount` — 실제 수령 배당금 (주당 × 수량 = 총액)
- `memo` — 메모

---

## 기간 필터

| 탭 | 조건 |
|----|------|
| YTD | `traded_at >= 올해 1월 1일` |
| 1Y | `traded_at >= today - 365일` |
| 전체 | 필터 없음 |

기본값: `YTD`

---

## 백엔드

### 신규 파일

**`allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/DividendReportService.kt`**

데이터 클래스:

```kotlin
data class DividendReport(
    val userId: UUID,
    val period: String,
    val generatedAt: LocalDateTime,
    val totalDividend: BigDecimal,       // 기간 내 총 수령액
    val receiptCount: Int,               // 수령 횟수
    val monthlyAvg: BigDecimal,          // 월 평균 = totalDividend / max(1, 기간 내 경과 개월수)
    val annualProjected: BigDecimal,     // 연환산 예상 = 항상 최근 12개월 SUM (period 탭과 무관)
    val monthlySeries: List<MonthlyDividend>,
    val bySymbol: List<SymbolDividend>,
    val recentHistory: List<DividendEntry>,
)

data class MonthlyDividend(
    val month: String,          // "2025-04"
    val amount: BigDecimal,
)

data class SymbolDividend(
    val stockName: String,
    val symbol: String?,
    val totalAmount: BigDecimal,
    val receiptCount: Int,
    val lastReceivedAt: LocalDate,
    val pct: BigDecimal,        // totalAmount / totalDividend * 100
)

data class DividendEntry(
    val tradedAt: LocalDate,
    val stockName: String,
    val symbol: String?,
    val amount: BigDecimal,
    val memo: String?,
)
```

SQL 쿼리 구성 (JdbcTemplate):
1. **집계**: `SUM(total_amount)`, `COUNT(*)` — KPI용
2. **월별**: `GROUP BY TO_CHAR(traded_at, 'YYYY-MM')` — 바차트용
3. **종목별**: `GROUP BY stock_name, symbol ORDER BY SUM(total_amount) DESC` — 테이블용
4. **이력**: `ORDER BY traded_at DESC LIMIT 30` — 타임라인용
5. **연환산**: 별도 쿼리로 최근 12개월 `SUM(total_amount)` 조회

### 수정 파일

**`ReportController.kt`**

```kotlin
@GetMapping("/dividend")
fun dividend(
    @RequestHeader("X-User-Id") userId: UUID,
    @RequestParam(defaultValue = "YTD") period: String,
): DividendReport = dividendSvc.report(userId, period)
```

생성자에 `DividendReportService` 의존성 추가.

---

## 프론트엔드

### 신규 파일

**`frontend/allfolio_app/types/dividend.ts`**

```typescript
export interface DividendReport {
  userId: string
  period: string
  generatedAt: string
  totalDividend: number
  receiptCount: number
  monthlyAvg: number
  annualProjected: number
  monthlySeries: MonthlyDividend[]
  bySymbol: SymbolDividend[]
  recentHistory: DividendEntry[]
}

export interface MonthlyDividend {
  month: string
  amount: number
}

export interface SymbolDividend {
  stockName: string
  symbol: string | null
  totalAmount: number
  receiptCount: number
  lastReceivedAt: string
  pct: number
}

export interface DividendEntry {
  tradedAt: string
  stockName: string
  symbol: string | null
  amount: number
  memo: string | null
}
```

**`frontend/allfolio_app/app/unified/reports/dividend/page.tsx`**

페이지 레이아웃 (기존 performance 페이지 패턴):

```
← 보고서   배당금 보고서          [YTD] [1Y] [전체]

┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐
│ 총 수령액 │ │ 수령 횟수 │ │  월 평균  │ │ 연환산 예상│
└──────────┘ └──────────┘ └──────────┘ └──────────┘

┌─ 월별 수령액 (BarChart) ─────────────────────────┐
│  ████ ██ ███ ██ ████ ...                          │
└───────────────────────────────────────────────────┘

┌─ 종목별 배당 ─────────────────────────────────────┐
│  종목명  심볼  횟수  총액  비중  최근 수령일        │
└───────────────────────────────────────────────────┘

┌─ 최근 수령 이력 ──────────────────────────────────┐
│  2025-04-15  삼성전자  +₩40,000  메모             │
└───────────────────────────────────────────────────┘
```

빈 상태: 데이터 없을 때 "아직 배당 내역이 없습니다. 거래 내역 입력 시 유형을 '배당'으로 선택하세요." 안내.

### 수정 파일

**`frontend/allfolio_app/lib/report-api.ts`**
- `DividendReport` 타입 import 추가
- `dividend(period?: string): Promise<DividendReport>` 메서드 추가

**`frontend/allfolio_app/app/unified/reports/page.tsx`**
- 배당금 보고서 카드 추가

```typescript
{
  href: '/unified/reports/dividend',
  title: '배당금 보고서',
  desc: '수령 배당금 합계, 월별 추이, 종목별 배당 이력',
  color: 'border-yellow-700 hover:border-yellow-500',
  badge: '💰',
}
```

---

## 변경 파일 목록

| 파일 | 작업 |
|------|------|
| `unified-asset/.../DividendReportService.kt` | 신규 생성 |
| `unified-asset/.../ReportController.kt` | `dividend` 엔드포인트 추가 |
| `frontend/.../types/dividend.ts` | 신규 생성 |
| `frontend/.../reports/dividend/page.tsx` | 신규 생성 |
| `frontend/.../lib/report-api.ts` | `dividend()` 메서드 추가 |
| `frontend/.../reports/page.tsx` | 보고서 허브 카드 추가 |

---

## 범위 외 (이번 구현에서 제외)

- 배당 수익률(YoC) 계산 — BUY 거래와의 join 필요, 별도 태스크로 분리
- 배당 성장률 차트 — 데이터 누적 후 추가
- 외화 배당 환산 — FX 연동 필요
