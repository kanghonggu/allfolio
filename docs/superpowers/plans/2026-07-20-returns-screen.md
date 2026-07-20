# 수익률 보고서 화면 (SCR-RPT-04) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 임의 기간 수익률 분석 API(`GET /api/reports/returns`) + TWR·MWR·입출금 분해 화면(`/unified/reports/returns`) + 입출금 기록 UI.

**Architecture:** BE는 `GetReturnsAnalysisUseCase`가 NavHistorySource·CashFlowRepository를 로드해 ReturnsCalculator를 호출하고 구조화 DTO를 반환, 기존 `ReportController`에 엔드포인트 추가. FE는 기존 관례(react-query·useApi 훅·recharts·Tailwind 다크) 그대로 — 프리셋이 from/to를 바꾸면 쿼리 재실행. 워터폴은 recharts 투명 베이스 스택.

**Tech Stack:** Kotlin/Spring (unified-asset) · Next.js 14 App Router · @tanstack/react-query · recharts 2 · Tailwind

**Spec:** `docs/superpowers/specs/2026-07-20-returns-screen-design.md`

---

### Task 1: BE — GetReturnsAnalysisUseCase (TDD) + 엔드포인트

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/GetReturnsAnalysisUseCase.kt`
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/api/ReportController.kt` (returns 엔드포인트 + InsufficientData 핸들러)
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/GetReturnsAnalysisUseCaseTest.kt`

- [x] **Step 1: 실패하는 테스트 (RED)** — 케이스: ①정상 분석(TWR·navSeries·asOf) ②관측 부족 → InsufficientDataException ③from>to → IllegalArgumentException

테스트는 ReturnsReportGeneratorTest의 FakeNavSource/FakeCashFlowRepo 패턴 재사용:

```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.returns.NavPoint
import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class GetReturnsAnalysisUseCaseTest {

    private val userId = UUID.randomUUID()

    private class FakeNavSource(private val points: List<NavPoint>) : NavHistorySource {
        override fun navSeries(userId: UUID, from: LocalDate, to: LocalDate) =
            points.filter { it.date in from..to }
    }

    private class FakeCashFlowRepo(private val flows: List<CashFlow> = emptyList()) : CashFlowRepository {
        override fun save(cashFlow: CashFlow) = cashFlow
        override fun findById(id: UUID): CashFlow? = null
        override fun findByUserIdAndPeriod(userId: UUID, from: LocalDate, to: LocalDate) =
            flows.filter { it.flowDate in from..to }
        override fun findByUserId(userId: UUID) = flows
        override fun delete(id: UUID) {}
    }

    private fun nav(day: Int, v: String) = NavPoint(LocalDate.of(2026, 6, day), BigDecimal(v))

    @Test
    fun `analyzes arbitrary range`() {
        val useCase = GetReturnsAnalysisUseCase(
            FakeNavSource(listOf(nav(1, "1000"), nav(30, "1100"))), FakeCashFlowRepo(),
        )
        val result = useCase.analyze(userId, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30))

        assertEquals(LocalDate.of(2026, 6, 30), result.asOfDate)
        assertEquals(2, result.navSeries.size)
        assertEquals(0, BigDecimal("0.1").compareTo(result.summary.twr!!.setScale(1)))
    }

    @Test
    fun `insufficient observations throw`() {
        val useCase = GetReturnsAnalysisUseCase(FakeNavSource(listOf(nav(1, "1000"))), FakeCashFlowRepo())
        assertThrows(InsufficientDataException::class.java) {
            useCase.analyze(userId, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30))
        }
    }

    @Test
    fun `from after to is rejected`() {
        val useCase = GetReturnsAnalysisUseCase(FakeNavSource(emptyList()), FakeCashFlowRepo())
        assertThrows(IllegalArgumentException::class.java) {
            useCase.analyze(userId, LocalDate.of(2026, 7, 2), LocalDate.of(2026, 7, 1))
        }
    }
}
```

- [x] **Step 2: 구현 (GREEN)**

```kotlin
// GetReturnsAnalysisUseCase.kt
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.returns.Flow
import com.allfolio.report.domain.returns.NavPoint
import com.allfolio.report.domain.returns.PeriodReturns
import com.allfolio.report.domain.returns.ReturnsCalculator
import com.allfolio.unifiedasset.application.port.CashFlowRepository
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.UUID

data class ReturnsAnalysis(
    val from: LocalDate,
    val to: LocalDate,
    val asOfDate: LocalDate,
    val summary: PeriodReturns,
    val navSeries: List<NavPoint>,
)

/** SCR-RPT-04 인터랙티브 분석: 임의 기간 TWR/MWR — 아카이브 없이 on-the-fly */
@Service
class GetReturnsAnalysisUseCase(
    private val navSource: NavHistorySource,
    private val cashFlowRepository: CashFlowRepository,
) {
    fun analyze(userId: UUID, from: LocalDate, to: LocalDate): ReturnsAnalysis {
        require(!from.isAfter(to)) { "조회 시작일이 종료일 이후일 수 없습니다" }
        val series = navSource.navSeries(userId, from, to)
        if (series.size < 2) {
            throw InsufficientDataException(
                "수익률 계산에 필요한 NAV 스냅샷이 부족합니다 (기간 내 ${series.size}건, 최소 2건)"
            )
        }
        val flows = cashFlowRepository.findByUserIdAndPeriod(userId, from, to)
            .map { Flow(it.flowDate, it.signedKrw()) }
        val sorted = series.sortedBy { it.date }
        return ReturnsAnalysis(
            from = from,
            to = to,
            asOfDate = sorted.last().date,
            summary = ReturnsCalculator.calculate(sorted, flows, from, to),
            navSeries = sorted,
        )
    }
}
```

ReportController에 추가 (기존 on-the-fly 리포트 옆):

```kotlin
@GetMapping("/returns")
fun returns(
    @RequestHeader("X-User-Id") userId: UUID,
    @RequestParam from: java.time.LocalDate,
    @RequestParam to: java.time.LocalDate,
): ReturnsAnalysis = returnsAnalysis.analyze(userId, from, to)

@ExceptionHandler(InsufficientDataException::class)
fun insufficientData(e: InsufficientDataException): org.springframework.http.ResponseEntity<Map<String, String>> =
    org.springframework.http.ResponseEntity.badRequest().body(mapOf("error" to (e.message ?: "insufficient data")))
```

(생성자에 `private val returnsAnalysis: GetReturnsAnalysisUseCase` 추가. LocalDate 파라미터는 ISO yyyy-MM-dd 자동 바인딩 확인 — 안 되면 `@DateTimeFormat(iso = DateTimeFormat.ISO.DATE)`)

- [x] **Step 3: 테스트 + 빌드 + Commit**

```bash
./gradlew :unified-asset:test --tests '*GetReturnsAnalysisUseCaseTest*' && ./gradlew :unified-asset:build
git add allfolio-backend/unified-asset
git commit -m "feat(returns): 임의 기간 수익률 분석 API GET /api/reports/returns"
```

### Task 2: FE — 타입 + API 클라이언트

**Files:**
- Create: `frontend/allfolio_app/types/returns.ts`
- Modify: `frontend/allfolio_app/lib/report-api.ts` (returns 추가)
- Create: `frontend/allfolio_app/lib/cashflow-api.ts`
- Modify: `frontend/allfolio_app/lib/useApi.ts` (useCashFlowApi 추가)

- [x] **Step 1: 타입 정의**

```typescript
// types/returns.ts
export interface PeriodSummary {
  twr: number | null
  mwr: number | null
  startNav: number | null
  endNav: number | null
  netFlow: number
  investmentPnl: number | null
}

export interface NavSeriesPoint { date: string; nav: number }

export interface ReturnsAnalysis {
  from: string
  to: string
  asOfDate: string
  summary: PeriodSummary
  navSeries: NavSeriesPoint[]
}

export type FlowType = 'DEPOSIT' | 'WITHDRAWAL'

export interface CashFlowItem {
  id: string
  accountId: string | null
  flowDate: string
  flowType: FlowType
  amount: number
  currency: string
  amountKrw: number
  memo: string | null
}

export interface RecordCashFlowRequest {
  accountId?: string | null
  flowDate: string
  flowType: FlowType
  amount: number
  currency: string
  memo?: string | null
}
```

- [x] **Step 2: API 클라이언트**

report-api.ts에 (import 추가: `import type { ReturnsAnalysis } from '@/types/returns'`):

```typescript
returns: async (from: string, to: string): Promise<ReturnsAnalysis> =>
  (await api.get<ReturnsAnalysis>('/returns', { params: { from, to } })).data,
```

```typescript
// lib/cashflow-api.ts
import axios from 'axios'
import type { CashFlowItem, RecordCashFlowRequest } from '@/types/returns'

const BASE_URL = `${process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8090'}/api/cashflows`

export function createCashFlowApi(accessToken: string) {
  const api = axios.create({
    baseURL: BASE_URL,
    timeout: 15_000,
    headers: { Authorization: `Bearer ${accessToken}` },
  })

  return {
    list: async (from?: string, to?: string): Promise<CashFlowItem[]> =>
      (await api.get<CashFlowItem[]>('', { params: from && to ? { from, to } : {} })).data,

    record: async (req: RecordCashFlowRequest): Promise<CashFlowItem> =>
      (await api.post<CashFlowItem>('', req)).data,

    remove: async (id: string): Promise<void> => { await api.delete(`/${id}`) },
  }
}
```

useApi.ts에 (import `createCashFlowApi` 추가):

```typescript
export function useCashFlowApi() {
  const { accessToken } = useAuth()
  return useMemo(
    () => (accessToken ? createCashFlowApi(accessToken) : null),
    [accessToken],
  )
}
```

- [x] **Step 3: Commit**

```bash
git add frontend/allfolio_app
git commit -m "feat(returns-fe): 수익률 분석·현금흐름 API 클라이언트 + 타입"
```

### Task 3: FE — 수익률 보고서 화면

**Files:**
- Create: `frontend/allfolio_app/app/unified/reports/returns/page.tsx`

- [x] **Step 1: 페이지 구현** — 스펙 §3 구성 그대로. 구조: 기간 선택바(프리셋 세그먼트+커스텀 date input) → 요약 카드 4 → TWR vs MWR 패널(해석 문구) → 누적 곡선(LineChart+입출금 ReferenceDot) → 워터폴(투명 베이스 스택 BarChart) → 입출금 그리드(기록 모달·삭제). 400 응답이면 스냅샷 부족 안내 + 그리드는 유지. 스타일은 performance/page.tsx의 다크 클래스 재사용.

- [x] **Step 2: 허브 카드 추가** — `app/unified/reports/page.tsx` REPORTS 배열 '수익률 분석' 다음에:

```typescript
{
  href:  '/unified/reports/returns',
  title: '수익률 보고서 (TWR·MWR)',
  desc:  '기관급 수익률 — 입출금 왜곡 제거(TWR), 체감 수익률(MWR), 입출금 효과 분해',
  color: 'border-lime-700 hover:border-lime-500',
  badge: '📉',
},
```

- [x] **Step 3: 타입체크·빌드 + Commit**

```bash
cd frontend/allfolio_app && npx tsc --noEmit && npm run build
git add frontend/allfolio_app
git commit -m "feat(returns-fe): 수익률 보고서 화면 — TWR·MWR·워터폴·입출금 기록"
```

### Task 4: 스모크 + 마무리

- [x] **Step 1: 로컬 스모크** — 도커 PG(+DDL)·백엔드 기동, `npm run dev` 프론트 기동, 브라우저로: 로그인 → NAV 시드 → 화면 진입 → 프리셋 전환 → 입출금 기록 → 곡선 마커·워터폴·카드 수치 일치 확인
- [x] **Step 2: push, PR 생성, 노션 #34 진행 업데이트**
