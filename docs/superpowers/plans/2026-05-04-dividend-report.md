# 배당금 보고서 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `ua_stock_trades` 테이블의 `DIVIDEND` 거래 내역을 집계해 월별 수령 추이, 종목별 배당 합계, 최근 이력을 보여주는 보고서를 백엔드+프론트엔드에 구현한다.

**Architecture:** 기존 `ReportService` 옆에 `DividendReportService`를 신규 생성하고 `ReportController`에 엔드포인트를 추가한다. 프론트엔드는 `report-api.ts`에 메서드를 추가하고 `/unified/reports/dividend/page.tsx` 페이지를 신규 생성한다. 기존 `performance` 보고서의 패턴(기간 탭 + KPI 카드 + 차트 + 테이블)을 그대로 따른다.

**Tech Stack:** Kotlin / Spring Boot 3, JdbcTemplate, JUnit 5, Next.js 15 App Router, TanStack Query v5, Recharts, Tailwind CSS

---

## 파일 목록

| 작업 | 파일 경로 |
|------|-----------|
| 신규 생성 | `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/DividendReportService.kt` |
| 신규 생성 | `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/DividendReportServiceTest.kt` |
| 수정 | `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/api/ReportController.kt` |
| 신규 생성 | `frontend/allfolio_app/types/dividend.ts` |
| 수정 | `frontend/allfolio_app/lib/report-api.ts` |
| 신규 생성 | `frontend/allfolio_app/app/unified/reports/dividend/page.tsx` |
| 수정 | `frontend/allfolio_app/app/unified/reports/page.tsx` |

---

## Task 1: DividendReportService — 데이터 클래스 + 빈 서비스 골격

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/DividendReportService.kt`

- [ ] **Step 1: 파일 생성**

```kotlin
package com.allfolio.unifiedasset.application.usecase

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

data class DividendReport(
    val userId: UUID,
    val period: String,
    val generatedAt: LocalDateTime,
    val totalDividend: BigDecimal,
    val receiptCount: Int,
    val monthlyAvg: BigDecimal,
    val annualProjected: BigDecimal,
    val monthlySeries: List<MonthlyDividend>,
    val bySymbol: List<SymbolDividend>,
    val recentHistory: List<DividendEntry>,
)

data class MonthlyDividend(
    val month: String,       // "2025-04"
    val amount: BigDecimal,
)

data class SymbolDividend(
    val stockName: String,
    val symbol: String?,
    val totalAmount: BigDecimal,
    val receiptCount: Int,
    val lastReceivedAt: LocalDate,
    val pct: BigDecimal,     // totalAmount / totalDividend * 100
)

data class DividendEntry(
    val tradedAt: LocalDate,
    val stockName: String,
    val symbol: String?,
    val amount: BigDecimal,
    val memo: String?,
)

@Service
class DividendReportService(private val jdbc: JdbcTemplate) {

    @Transactional(readOnly = true)
    fun report(userId: UUID, period: String): DividendReport {
        val since = periodStart(period)
        TODO("implement")
    }

    private fun periodStart(period: String): LocalDate? = when (period) {
        "YTD" -> LocalDate.of(LocalDate.now().year, 1, 1)
        "1Y"  -> LocalDate.now().minusYears(1)
        else  -> null   // 전체
    }
}
```

- [ ] **Step 2: 빌드 확인**

```bash
cd allfolio-backend
./gradlew :unified-asset:compileKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/DividendReportService.kt
git commit -m "feat: add DividendReportService skeleton and data classes"
```

---

## Task 2: DividendReportService — report() 구현

**Files:**
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/DividendReportService.kt`
- Create: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/DividendReportServiceTest.kt`

- [ ] **Step 1: 테스트 디렉터리 확인 후 테스트 파일 작성**

```bash
mkdir -p allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase
```

```kotlin
// DividendReportServiceTest.kt
package com.allfolio.unifiedasset.application.usecase

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class DividendReportServiceTest {

    @Mock
    lateinit var jdbc: JdbcTemplate

    private val userId = UUID.randomUUID()

    // Mock JdbcTemplate은 Collection 반환 메서드에 기본으로 빈 리스트를 반환한다.
    // DividendReportService의 모든 query 호출은 runCatching으로 감싸여 있어
    // 예외 또는 빈 결과 모두 안전하게 처리된다.

    @Test
    fun `배당 내역 없으면 totalDividend 0 반환`() {
        val svc = DividendReportService(jdbc)
        val result = svc.report(userId, "YTD")
        assertEquals(BigDecimal.ZERO, result.totalDividend)
        assertEquals(0, result.receiptCount)
        assertTrue(result.monthlySeries.isEmpty())
        assertTrue(result.bySymbol.isEmpty())
        assertTrue(result.recentHistory.isEmpty())
    }

    @Test
    fun `period YTD - period 필드가 YTD로 설정됨`() {
        val svc = DividendReportService(jdbc)
        val result = svc.report(userId, "YTD")
        assertEquals("YTD", result.period)
    }

    @Test
    fun `period 전체 - period 필드가 전체로 설정됨`() {
        val svc = DividendReportService(jdbc)
        val result = svc.report(userId, "전체")
        assertEquals("전체", result.period)
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

```bash
cd allfolio-backend
./gradlew :unified-asset:test --tests "*.DividendReportServiceTest" 2>&1 | tail -20
```
Expected: `TODO()` 예외로 실패

- [ ] **Step 3: report() 구현**

`DividendReportService.kt`의 `report()` 메서드와 `TODO("implement")`를 아래로 교체:

```kotlin
@Transactional(readOnly = true)
fun report(userId: UUID, period: String): DividendReport {
    val since = periodStart(period)
    val sinceParam: LocalDate = since ?: LocalDate.of(2000, 1, 1)

    val whereClause = if (since != null)
        "WHERE user_id = ? AND trade_type = 'DIVIDEND' AND traded_at >= ?"
    else
        "WHERE user_id = ? AND trade_type = 'DIVIDEND'"

    // 1. KPI 집계
    data class KpiRow(val total: BigDecimal, val count: Int)
    val kpi = runCatching {
        jdbc.query(
            "SELECT COALESCE(SUM(total_amount),0) AS total, COUNT(*) AS cnt FROM ua_stock_trades $whereClause",
            { rs, _ -> KpiRow(rs.getBigDecimal("total"), rs.getInt("cnt")) },
            *if (since != null) arrayOf(userId, sinceParam) else arrayOf(userId),
        ).firstOrNull() ?: KpiRow(BigDecimal.ZERO, 0)
    }.getOrElse { KpiRow(BigDecimal.ZERO, 0) }

    // 2. 월별 합계
    val monthlySeries = runCatching {
        jdbc.query(
            """SELECT TO_CHAR(traded_at, 'YYYY-MM') AS month,
                      SUM(total_amount) AS amount
               FROM ua_stock_trades $whereClause
               GROUP BY TO_CHAR(traded_at, 'YYYY-MM')
               ORDER BY 1""",
            { rs, _ -> MonthlyDividend(rs.getString("month"), rs.getBigDecimal("amount")) },
            *if (since != null) arrayOf(userId, sinceParam) else arrayOf(userId),
        )
    }.getOrElse { emptyList() }

    // 3. 종목별 합계
    val bySymbol = runCatching {
        val rows = jdbc.query(
            """SELECT stock_name, symbol,
                      SUM(total_amount) AS total, COUNT(*) AS cnt,
                      MAX(traded_at) AS last_at
               FROM ua_stock_trades $whereClause
               GROUP BY stock_name, symbol
               ORDER BY total DESC""",
            { rs, _ -> Triple(
                rs.getString("stock_name"),
                Triple(rs.getString("symbol"), rs.getBigDecimal("total"), rs.getInt("cnt")),
                rs.getDate("last_at").toLocalDate(),
            )},
            *if (since != null) arrayOf(userId, sinceParam) else arrayOf(userId),
        )
        rows.map { (name, data, lastAt) ->
            val (sym, total, cnt) = data
            val pct = if (kpi.total > BigDecimal.ZERO)
                total.divide(kpi.total, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal(100)).setScale(1, RoundingMode.HALF_UP)
            else BigDecimal.ZERO
            SymbolDividend(name, sym, total, cnt, lastAt, pct)
        }
    }.getOrElse { emptyList() }

    // 4. 최근 이력
    val recentHistory = runCatching {
        jdbc.query(
            """SELECT traded_at, stock_name, symbol, total_amount, memo
               FROM ua_stock_trades $whereClause
               ORDER BY traded_at DESC LIMIT 30""",
            { rs, _ -> DividendEntry(
                rs.getDate("traded_at").toLocalDate(),
                rs.getString("stock_name"),
                rs.getString("symbol"),
                rs.getBigDecimal("total_amount"),
                rs.getString("memo"),
            )},
            *if (since != null) arrayOf(userId, sinceParam) else arrayOf(userId),
        )
    }.getOrElse { emptyList() }

    // 5. 연환산 예상 (항상 최근 12개월, period 탭과 무관)
    val annualProjected = runCatching {
        jdbc.query(
            """SELECT COALESCE(SUM(total_amount),0) AS total
               FROM ua_stock_trades
               WHERE user_id = ? AND trade_type = 'DIVIDEND' AND traded_at >= ?""",
            { rs, _ -> rs.getBigDecimal("total") },
            userId, LocalDate.now().minusYears(1),
        ).firstOrNull() ?: BigDecimal.ZERO
    }.getOrElse { BigDecimal.ZERO }

    // 6. 월 평균: totalDividend / 기간 개월수 (최소 1)
    val elapsedMonths = if (since != null) {
        val months = java.time.temporal.ChronoUnit.MONTHS.between(since, LocalDate.now()).coerceAtLeast(1)
        months
    } else {
        val oldest = recentHistory.minByOrNull { it.tradedAt }?.tradedAt
        if (oldest != null)
            java.time.temporal.ChronoUnit.MONTHS.between(oldest, LocalDate.now()).coerceAtLeast(1)
        else 1L
    }
    val monthlyAvg = kpi.total.divide(BigDecimal(elapsedMonths), 0, RoundingMode.HALF_UP)

    return DividendReport(
        userId = userId,
        period = period,
        generatedAt = LocalDateTime.now(),
        totalDividend = kpi.total,
        receiptCount = kpi.count,
        monthlyAvg = monthlyAvg,
        annualProjected = annualProjected,
        monthlySeries = monthlySeries,
        bySymbol = bySymbol,
        recentHistory = recentHistory,
    )
}
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

```bash
cd allfolio-backend
./gradlew :unified-asset:test --tests "*.DividendReportServiceTest" 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL, 3 tests passed

- [ ] **Step 5: 빌드 전체 확인**

```bash
cd allfolio-backend
./gradlew :unified-asset:compileKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 커밋**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/DividendReportService.kt
git add allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/DividendReportServiceTest.kt
git commit -m "feat: implement DividendReportService with monthly/symbol/history aggregation"
```

---

## Task 3: ReportController에 /dividend 엔드포인트 추가

**Files:**
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/api/ReportController.kt`

- [ ] **Step 1: ReportController 수정**

생성자에 `DividendReportService` 추가, 엔드포인트 메서드 추가:

```kotlin
@RestController
@RequestMapping("/api/reports")
class ReportController(
    private val svc: ReportService,
    private val dividendSvc: DividendReportService,   // ← 추가
) {

    // ... 기존 엔드포인트 유지 ...

    @GetMapping("/dividend")
    fun dividend(
        @RequestHeader("X-User-Id") userId: UUID,
        @RequestParam(defaultValue = "YTD") period: String,
    ): DividendReport = dividendSvc.report(userId, period)
}
```

- [ ] **Step 2: 빌드 확인**

```bash
cd allfolio-backend
./gradlew :backend-app:compileKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 로컬 서버 기동 후 curl 테스트 (서버가 이미 실행 중인 경우)**

```bash
curl -s -H "Authorization: Bearer <token>" \
  "http://localhost:8090/api/reports/dividend?period=YTD" | jq .
```
Expected: `{"userId":..., "period":"YTD", "totalDividend":0, ...}` 형태 JSON 응답

- [ ] **Step 4: 커밋**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/api/ReportController.kt
git commit -m "feat: add GET /api/reports/dividend endpoint"
```

---

## Task 4: 프론트엔드 타입 파일 생성

**Files:**
- Create: `frontend/allfolio_app/types/dividend.ts`

- [ ] **Step 1: 파일 생성**

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
  month: string       // "2025-04"
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

- [ ] **Step 2: 커밋**

```bash
git add frontend/allfolio_app/types/dividend.ts
git commit -m "feat: add DividendReport TypeScript types"
```

---

## Task 5: report-api.ts에 dividend() 메서드 추가

**Files:**
- Modify: `frontend/allfolio_app/lib/report-api.ts`

- [ ] **Step 1: report-api.ts 수정**

파일 상단 import에 `DividendReport` 추가, 반환 객체에 `dividend` 메서드 추가:

```typescript
import axios from 'axios'
import type {
  SummaryReport, AllocationReport, PerformanceReport,
  RiskReport, PositionsReport, BenchmarkReport,
  NetWorthReport, MonthlyPnlReport,
} from '@/types/report'
import type { DividendReport } from '@/types/dividend'   // ← 추가

const BASE_URL = `${process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'}/api/reports`

export function createReportApi(accessToken: string) {
  const api = axios.create({
    baseURL: BASE_URL,
    timeout: 30_000,
    headers: { Authorization: `Bearer ${accessToken}` },
  })

  return {
    summary: async (): Promise<SummaryReport> =>
      (await api.get<SummaryReport>('/summary')).data,

    allocation: async (): Promise<AllocationReport> =>
      (await api.get<AllocationReport>('/allocation')).data,

    performance: async (period = '1M'): Promise<PerformanceReport> =>
      (await api.get<PerformanceReport>('/performance', { params: { period } })).data,

    risk: async (): Promise<RiskReport> =>
      (await api.get<RiskReport>('/risk')).data,

    positions: async (): Promise<PositionsReport> =>
      (await api.get<PositionsReport>('/positions')).data,

    benchmark: async (period = 'YTD'): Promise<BenchmarkReport> =>
      (await api.get<BenchmarkReport>('/benchmark', { params: { period } })).data,

    networth: async (): Promise<NetWorthReport> =>
      (await api.get<NetWorthReport>('/networth')).data,

    monthlyPnl: async (): Promise<MonthlyPnlReport> =>
      (await api.get<MonthlyPnlReport>('/monthly-pnl')).data,

    dividend: async (period = 'YTD'): Promise<DividendReport> =>   // ← 추가
      (await api.get<DividendReport>('/dividend', { params: { period } })).data,
  }
}
```

- [ ] **Step 2: 타입 체크**

```bash
cd frontend/allfolio_app
npx tsc --noEmit 2>&1 | grep -E "error|warning" | head -20
```
Expected: 에러 없음

- [ ] **Step 3: 커밋**

```bash
git add frontend/allfolio_app/lib/report-api.ts
git commit -m "feat: add dividend() method to report-api"
```

---

## Task 6: 배당금 보고서 페이지 구현

**Files:**
- Create: `frontend/allfolio_app/app/unified/reports/dividend/page.tsx`

- [ ] **Step 1: 디렉터리 생성 확인**

```bash
mkdir -p frontend/allfolio_app/app/unified/reports/dividend
```

- [ ] **Step 2: 파일 생성**

```tsx
'use client'

import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import Link from 'next/link'
import { useReportApi } from '@/lib/useApi'
import type { MonthlyDividend, SymbolDividend, DividendEntry } from '@/types/dividend'
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid,
  Tooltip, ResponsiveContainer,
} from 'recharts'

type Period = 'YTD' | '1Y' | '전체'
const PERIODS: Period[] = ['YTD', '1Y', '전체']

function fmt(n: number) {
  return new Intl.NumberFormat('ko-KR', {
    style: 'currency', currency: 'KRW', maximumFractionDigits: 0,
  }).format(n)
}

export default function DividendPage() {
  const reportApi = useReportApi()
  const [period, setPeriod] = useState<Period>('YTD')

  const { data, isLoading, isError } = useQuery({
    queryKey: ['report', 'dividend', period],
    queryFn: () => reportApi!.dividend(period),
    enabled: !!reportApi,
  })

  if (isLoading) return <div className="h-96 animate-pulse rounded-xl bg-gray-800" />
  if (isError || !data) return (
    <div className="rounded-xl border border-red-800 bg-red-950 p-6 text-sm text-red-400">
      보고서를 불러올 수 없습니다.
    </div>
  )

  const chartData = data.monthlySeries.map((m: MonthlyDividend) => ({
    month: m.month,
    amount: Number(m.amount),
  }))

  const isEmpty = data.receiptCount === 0

  return (
    <div className="space-y-8">
      {/* 헤더 + 기간 탭 */}
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div className="flex items-center gap-3">
          <Link href="/unified/reports" className="text-sm text-gray-500 hover:text-gray-300">
            ← 보고서
          </Link>
          <h1 className="text-2xl font-bold">배당금 보고서</h1>
        </div>
        <div className="flex gap-2">
          {PERIODS.map((p) => (
            <button
              key={p}
              onClick={() => setPeriod(p)}
              className={`rounded-lg px-4 py-1.5 text-sm font-medium transition-colors ${
                period === p
                  ? 'bg-yellow-600 text-white'
                  : 'bg-gray-800 text-gray-400 hover:bg-gray-700'
              }`}
            >
              {p}
            </button>
          ))}
        </div>
      </div>

      <p className="text-xs text-gray-500">생성: {new Date(data.generatedAt).toLocaleString('ko-KR')}</p>

      {/* KPI 카드 */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <div className="rounded-xl border border-yellow-800 bg-gray-900 p-5">
          <p className="text-xs text-gray-500">총 수령액</p>
          <p className="mt-2 text-2xl font-bold tabular-nums text-yellow-400">
            {fmt(Number(data.totalDividend))}
          </p>
        </div>
        <div className="rounded-xl border border-gray-700 bg-gray-900 p-5">
          <p className="text-xs text-gray-500">수령 횟수</p>
          <p className="mt-2 text-2xl font-bold tabular-nums">{data.receiptCount}회</p>
        </div>
        <div className="rounded-xl border border-gray-700 bg-gray-900 p-5">
          <p className="text-xs text-gray-500">월 평균</p>
          <p className="mt-2 text-2xl font-bold tabular-nums">
            {fmt(Number(data.monthlyAvg))}
          </p>
        </div>
        <div className="rounded-xl border border-emerald-800 bg-gray-900 p-5">
          <p className="text-xs text-gray-500">연환산 예상</p>
          <p className="mt-2 text-2xl font-bold tabular-nums text-emerald-400">
            {fmt(Number(data.annualProjected))}
          </p>
          <p className="mt-0.5 text-xs text-gray-600">최근 12개월 기준</p>
        </div>
      </div>

      {isEmpty ? (
        <div className="flex flex-col items-center justify-center gap-3 rounded-xl border border-dashed border-gray-700 py-16 text-center">
          <p className="text-gray-400 font-medium">아직 배당 내역이 없습니다</p>
          <p className="text-sm text-gray-600">
            거래 내역 입력 시 유형을 <span className="text-gray-400 font-medium">배당</span>으로 선택하면 여기에 집계됩니다.
          </p>
          <Link
            href="/unified/accounts"
            className="mt-2 rounded-lg bg-gray-700 px-4 py-2 text-sm font-medium text-gray-300 hover:bg-gray-600 transition-colors"
          >
            계좌로 이동
          </Link>
        </div>
      ) : (
        <>
          {/* 월별 수령액 차트 */}
          <div className="rounded-xl border border-gray-700 bg-gray-900 p-6">
            <h2 className="mb-4 text-sm font-semibold text-gray-300">월별 수령액</h2>
            {chartData.length >= 1 ? (
              <ResponsiveContainer width="100%" height={260}>
                <BarChart data={chartData} margin={{ top: 5, right: 20, left: 0, bottom: 5 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="#1f2937" />
                  <XAxis
                    dataKey="month"
                    tick={{ fontSize: 11, fill: '#6b7280' }}
                    tickLine={false}
                  />
                  <YAxis
                    tickFormatter={(v) =>
                      v >= 1_000_000
                        ? `${(v / 1_000_000).toFixed(0)}M`
                        : v >= 10_000
                          ? `${(v / 10_000).toFixed(0)}만`
                          : String(v)
                    }
                    tick={{ fontSize: 11, fill: '#6b7280' }}
                    tickLine={false}
                    axisLine={false}
                    width={60}
                  />
                  <Tooltip
                    formatter={(v: number) => [fmt(v), '배당 수령액']}
                    contentStyle={{
                      background: '#111827',
                      border: '1px solid #374151',
                      borderRadius: 8,
                    }}
                    labelStyle={{ color: '#d1d5db' }}
                  />
                  <Bar dataKey="amount" name="수령액" fill="#ca8a04" radius={[4, 4, 0, 0]} />
                </BarChart>
              </ResponsiveContainer>
            ) : (
              <div className="flex h-48 items-center justify-center text-sm text-gray-500">
                차트 데이터가 부족합니다.
              </div>
            )}
          </div>

          {/* 종목별 배당 */}
          <div className="rounded-xl border border-gray-700 bg-gray-900 p-6">
            <h2 className="mb-4 text-sm font-semibold text-gray-300">종목별 배당</h2>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-xs text-gray-500 border-b border-gray-800">
                    <th className="pb-2 font-normal">종목</th>
                    <th className="pb-2 font-normal text-right">수령 횟수</th>
                    <th className="pb-2 font-normal text-right">합계</th>
                    <th className="pb-2 font-normal text-right">비중</th>
                    <th className="pb-2 font-normal text-right">최근 수령일</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-gray-800">
                  {data.bySymbol.map((s: SymbolDividend) => (
                    <tr key={`${s.stockName}-${s.symbol}`}>
                      <td className="py-2.5">
                        <span className="font-medium text-gray-200">{s.stockName}</span>
                        {s.symbol && (
                          <span className="ml-1.5 text-xs text-gray-500">{s.symbol}</span>
                        )}
                      </td>
                      <td className="py-2.5 text-right text-gray-400">{s.receiptCount}회</td>
                      <td className="py-2.5 text-right font-semibold tabular-nums text-yellow-400">
                        {fmt(Number(s.totalAmount))}
                      </td>
                      <td className="py-2.5 text-right tabular-nums text-gray-500">
                        {Number(s.pct).toFixed(1)}%
                      </td>
                      <td className="py-2.5 text-right tabular-nums text-gray-500">
                        {s.lastReceivedAt}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          {/* 최근 수령 이력 */}
          <div className="rounded-xl border border-gray-700 bg-gray-900 p-6">
            <h2 className="mb-4 text-sm font-semibold text-gray-300">최근 수령 이력</h2>
            <div className="space-y-2">
              {data.recentHistory.map((e: DividendEntry, i: number) => (
                <div
                  key={i}
                  className="flex items-center justify-between rounded-lg bg-gray-800/50 px-4 py-2.5"
                >
                  <div className="flex items-center gap-3">
                    <span className="text-xs tabular-nums text-gray-500">{e.tradedAt}</span>
                    <span className="text-sm text-gray-200">{e.stockName}</span>
                    {e.symbol && (
                      <span className="text-xs text-gray-500">{e.symbol}</span>
                    )}
                    {e.memo && (
                      <span className="text-xs text-gray-600 italic">{e.memo}</span>
                    )}
                  </div>
                  <span className="text-sm font-semibold tabular-nums text-yellow-400">
                    +{fmt(Number(e.amount))}
                  </span>
                </div>
              ))}
            </div>
          </div>
        </>
      )}
    </div>
  )
}
```

- [ ] **Step 3: 타입 체크**

```bash
cd frontend/allfolio_app
npx tsc --noEmit 2>&1 | grep "error" | head -20
```
Expected: 에러 없음

- [ ] **Step 4: 커밋**

```bash
git add frontend/allfolio_app/app/unified/reports/dividend/page.tsx
git commit -m "feat: add dividend report page with bar chart, symbol table, history"
```

---

## Task 7: 보고서 허브에 배당금 보고서 카드 추가

**Files:**
- Modify: `frontend/allfolio_app/app/unified/reports/page.tsx`

- [ ] **Step 1: REPORTS 배열에 카드 항목 추가**

`page.tsx`의 `REPORTS` 배열 마지막 항목 (`/unified/simulator`) 뒤에 추가:

```typescript
  {
    href:  '/unified/reports/dividend',
    title: '배당금 보고서',
    desc:  '수령 배당금 합계, 월별 추이, 종목별 배당 이력',
    color: 'border-yellow-700 hover:border-yellow-500',
    badge: '💰',
  },
```

- [ ] **Step 2: 타입 체크**

```bash
cd frontend/allfolio_app
npx tsc --noEmit 2>&1 | grep "error" | head -10
```
Expected: 에러 없음

- [ ] **Step 3: 커밋**

```bash
git add frontend/allfolio_app/app/unified/reports/page.tsx
git commit -m "feat: add dividend report card to reports hub"
```

---

## Task 8: 통합 검증

- [ ] **Step 1: 백엔드 전체 빌드**

```bash
cd allfolio-backend
./gradlew :backend-app:bootJar -x test 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 프론트엔드 전체 빌드**

```bash
cd frontend/allfolio_app
npm run build 2>&1 | tail -20
```
Expected: 에러 없이 빌드 완료

- [ ] **Step 3: 수동 E2E 확인 (서버 실행 중인 경우)**

1. 브라우저에서 `/unified/reports` 접속 → 배당금 보고서 카드 노출 확인
2. 카드 클릭 → `/unified/reports/dividend` 페이지 로드 확인
3. 배당 내역 없으면 빈 상태 메시지 표시 확인
4. 거래 내역에서 배당 타입 추가 후 페이지 새로고침 → KPI/차트/테이블 반영 확인
5. YTD / 1Y / 전체 탭 전환 → 데이터 변경 확인

- [ ] **Step 4: 최종 커밋**

```bash
git add -A
git status   # 미반영 파일 없는지 확인
git commit -m "feat: complete dividend report feature (backend + frontend)" --allow-empty
```
