# R-04 비용 인사이트 (사실형) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 비용 보고서(R-04)에 기존 집계값의 사실형 하이라이트(연환산 TER·비용률·최대 비용 처·비용 구성·투자손익 대비 비용)를 추가한다. 조언/추천 없음.

**Architecture:** 순수 `CostInsightCalculator`가 이미 계산된 값들로 `List<CostInsight>`를 만들고, `CostReportGenerator`가 body에 `insights`로 직렬화. FE는 옵셔널 필드로 카드 섹션 렌더.

**Tech Stack:** Kotlin/Spring(unified-asset), JUnit, Next.js/React/TypeScript.

Spec: `docs/superpowers/specs/2026-07-31-cost-report-insights-design.md`

---

## Task 1: CostInsightCalculator (순수 계산기 + 테스트)

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/CostInsightCalculator.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/CostInsightCalculatorTest.kt`

- [ ] **Step 1: 실패 테스트 작성** — `CostInsightCalculatorTest.kt`:

```kotlin
package com.allfolio.unifiedasset.application.usecase

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CostInsightCalculatorTest {
    private fun bd(s: String) = BigDecimal(s)

    @Test
    fun `TER는 bp로 환산되고 costRatio-투자손익대비-최대비용처-비용구성이 모두 포함된다`() {
        val r = CostInsightCalculator.build(
            totalCost = bd("10000"), brokerFee = bd("7000"), tradingTax = bd("3000"),
            costRatio = bd("0.15"), annualizedTer = bd("1.80"), costVsProfit = bd("12.50"),
            topBrokerName = "KIS", topBrokerWeight = bd("60.00"),
        )
        val byLabel = r.associateBy { it.label }
        assertThat(byLabel["연환산 TER"]!!.value).isEqualTo("180bp")
        assertThat(byLabel["기간 비용률"]!!.value).contains("0.15%").contains("15bp")
        assertThat(byLabel["최대 비용 처"]!!.value).isEqualTo("KIS")
        assertThat(byLabel["최대 비용 처"]!!.detail).contains("60.00")
        assertThat(byLabel["비용 구성"]!!.value).contains("매매수수료 70").contains("거래세 30")
        assertThat(byLabel["투자손익 대비 비용"]!!.value).isEqualTo("12.50%")
    }

    @Test
    fun `costRatio-ter-costVsProfit null이면 해당 인사이트는 생략된다`() {
        val r = CostInsightCalculator.build(
            totalCost = bd("10000"), brokerFee = bd("10000"), tradingTax = bd("0"),
            costRatio = null, annualizedTer = null, costVsProfit = null,
            topBrokerName = "KIS", topBrokerWeight = bd("100.00"),
        )
        val labels = r.map { it.label }
        assertThat(labels).doesNotContain("연환산 TER", "기간 비용률", "투자손익 대비 비용")
        assertThat(labels).contains("최대 비용 처", "비용 구성")
    }

    @Test
    fun `topBroker null이거나 totalCost 0이면 최대비용처-비용구성 생략`() {
        val r = CostInsightCalculator.build(
            totalCost = bd("0"), brokerFee = bd("0"), tradingTax = bd("0"),
            costRatio = null, annualizedTer = null, costVsProfit = null,
            topBrokerName = null, topBrokerWeight = null,
        )
        assertThat(r).isEmpty()
    }

    @Test
    fun `순서는 TER-비용률-최대비용처-비용구성-투자손익대비`() {
        val r = CostInsightCalculator.build(
            totalCost = bd("100"), brokerFee = bd("60"), tradingTax = bd("40"),
            costRatio = bd("0.10"), annualizedTer = bd("1.00"), costVsProfit = bd("5.00"),
            topBrokerName = "KIS", topBrokerWeight = bd("100.00"),
        )
        assertThat(r.map { it.label }).containsExactly(
            "연환산 TER", "기간 비용률", "최대 비용 처", "비용 구성", "투자손익 대비 비용",
        )
    }
}
```

- [ ] **Step 2: 테스트 실패 확인** — Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests "*CostInsightCalculatorTest*"` → Expected: compile FAIL (CostInsightCalculator 미정의).

- [ ] **Step 3: 최소 구현** — `CostInsightCalculator.kt`:

```kotlin
package com.allfolio.unifiedasset.application.usecase

import java.math.BigDecimal
import java.math.RoundingMode

/** 비용 보고서 사실형 하이라이트(조언 아님). 기존 집계값의 파생만 수행. */
data class CostInsight(val label: String, val value: String, val detail: String?)

object CostInsightCalculator {
    private val mc = java.math.MathContext(20)

    /** 비용률 %(0~100 스케일) → bp 정수 문자열. 예: 0.15% → "15". */
    private fun bp(pct: BigDecimal): String =
        pct.multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP).toPlainString()

    /** a/b × 100, 2자리(b<=0 → 0). */
    private fun pct(a: BigDecimal, b: BigDecimal): BigDecimal =
        if (b <= BigDecimal.ZERO) BigDecimal.ZERO.setScale(2)
        else a.divide(b, mc).multiply(BigDecimal(100), mc).setScale(2, RoundingMode.HALF_UP)

    fun build(
        totalCost: BigDecimal,
        brokerFee: BigDecimal,
        tradingTax: BigDecimal,
        costRatio: BigDecimal?,
        annualizedTer: BigDecimal?,
        costVsProfit: BigDecimal?,
        topBrokerName: String?,
        topBrokerWeight: BigDecimal?,
    ): List<CostInsight> {
        val out = mutableListOf<CostInsight>()
        if (annualizedTer != null) {
            out += CostInsight("연환산 TER", "${bp(annualizedTer)}bp", "비용률 연환산")
        }
        if (costRatio != null) {
            out += CostInsight("기간 비용률", "${costRatio.toPlainString()}% (${bp(costRatio)}bp)", null)
        }
        if (topBrokerName != null) {
            val d = topBrokerWeight?.let { "전체 비용의 ${it.toPlainString()}%" }
            out += CostInsight("최대 비용 처", topBrokerName, d)
        }
        if (totalCost > BigDecimal.ZERO) {
            out += CostInsight(
                "비용 구성",
                "매매수수료 ${pct(brokerFee, totalCost).toPlainString()}% · 거래세 ${pct(tradingTax, totalCost).toPlainString()}%",
                null,
            )
        }
        if (costVsProfit != null) {
            out += CostInsight("투자손익 대비 비용", "${costVsProfit.toPlainString()}%", null)
        }
        return out
    }
}
```

- [ ] **Step 4: 테스트 통과 확인** — Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests "*CostInsightCalculatorTest*"` → Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/CostInsightCalculator.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/CostInsightCalculatorTest.kt
git commit -m "feat(cost): add factual CostInsightCalculator (TDD)"
```

---

## Task 2: CostReportGenerator 통합 + 테스트

**Files:**
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/CostReportGenerator.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/CostReportGeneratorTest.kt`

- [ ] **Step 1: 실패 테스트 확장** — 기존 `CostReportGeneratorTest`에 케이스 추가(기존 픽스처 재사용). body에 `insights` 배열이 있고 "비용 구성"/"최대 비용 처" label을 포함하는지 검증:

```kotlin
    @Test
    fun `body에 사실형 insights가 포함된다`() {
        // 기존 테스트와 동일한 방식으로 generator.generate(userId, period) 호출
        val report = generator.generate(userId, period)
        val body = mapper.readTree(report.bodyJson)
        val insights = body.get("insights")
        assertThat(insights).isNotNull
        val labels = insights.map { it.get("label").asText() }
        assertThat(labels).contains("최대 비용 처", "비용 구성")
    }
```
(기존 테스트의 `generator`/`userId`/`period`/`mapper` 셋업을 그대로 사용. 없으면 파일 상단 셋업 참고해 동일 패턴으로 작성.)

- [ ] **Step 2: 테스트 실패 확인** — Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests "*CostReportGeneratorTest*"` → Expected: FAIL (insights 노드 없음 → null).

- [ ] **Step 3: 생성기 통합** — `CostReportGenerator.generate` 내 `byBroker` 산출 뒤, `body` 맵 생성 전에 추가:

```kotlin
        val topBroker = byBroker.firstOrNull()
        val insights = CostInsightCalculator.build(
            totalCost = totalCost, brokerFee = brokerFee, tradingTax = tradingTax,
            costRatio = costRatio, annualizedTer = ter, costVsProfit = costVsProfit,
            topBrokerName = topBroker?.get("broker") as String?,
            topBrokerWeight = topBroker?.get("weight") as BigDecimal?,
        )
```
그리고 `body` 맵에 `"details" to details` 앞/뒤 아무 곳에 필드 추가:
```kotlin
            "insights" to insights.map { mapOf("label" to it.label, "value" to it.value, "detail" to it.detail) },
```
KDoc의 "v1 제외: ...인사이트" 문구에서 인사이트를 제거하고 "사실형 인사이트 포함(조언 아님). 후속: 개인화 룰·bp 벤치마크." 로 갱신.

- [ ] **Step 4: 테스트 통과 확인** — Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests "*CostReportGeneratorTest*" --tests "*CostInsightCalculatorTest*"` → Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/CostReportGenerator.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/CostReportGeneratorTest.kt
git commit -m "feat(cost): surface factual insights in cost report body"
```

---

## Task 3: 백엔드 회귀

- [ ] **Step 1: unified-asset 전체 테스트** — Run: `cd allfolio-backend && ./gradlew :unified-asset:test` → Expected: BUILD SUCCESSFUL.
- [ ] **Step 2: (실패 시) 수정 후 재실행.** 없으면 커밋 불필요.

---

## Task 4: Frontend — 타입 + 컴포넌트 + 페이지

**Files:**
- Modify: `frontend/allfolio_app/types/cost-report.ts`
- Create: `frontend/allfolio_app/components/cost-report/CostInsights.tsx`
- Modify: `frontend/allfolio_app/app/unified/reports/cost-report/[id]/page.tsx`

- [ ] **Step 1: 타입 추가** — `types/cost-report.ts`에 추가(정확한 파일명/경로는 기존 cost 리포트 타입 파일 확인; 없으면 report body 타입이 정의된 곳에 추가):

```ts
export interface CostInsight {
  label: string
  value: string
  detail: string | null
}
```
그리고 CostReportBody 인터페이스에 `insights?: CostInsight[]` (옵셔널 — 구 아카이브 호환) 추가.

- [ ] **Step 2: 컴포넌트 생성** — `components/cost-report/CostInsights.tsx`:

```tsx
import type { CostInsight } from '@/types/cost-report'

export function CostInsights({ items }: { items: CostInsight[] }) {
  if (!items || items.length === 0) return null
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">비용 인사이트</h2>
      <p className="text-xs text-gray-500">기존 집계의 사실형 하이라이트입니다(투자·재무 조언 아님).</p>
      <div className="grid grid-cols-2 gap-3 md:grid-cols-3">
        {items.map((it, i) => (
          <div key={`${it.label}-${i}`} className="rounded-xl border border-gray-800 bg-gray-900 p-4">
            <div className="text-xs text-gray-500">{it.label}</div>
            <div className="mt-1 text-lg font-semibold text-gray-100">{it.value}</div>
            {it.detail && <div className="mt-0.5 text-xs text-gray-400">{it.detail}</div>}
          </div>
        ))}
      </div>
    </section>
  )
}
```

- [ ] **Step 3: 페이지 렌더** — `app/unified/reports/cost-report/[id]/page.tsx`에 import 추가 후, 요약 섹션 근처에 삽입:

```tsx
{body.insights && body.insights.length > 0 && <CostInsights items={body.insights} />}
```
(cost-report 상세 페이지의 실제 경로/변수명 `body`는 기존 코드 확인 후 맞춤.)

- [ ] **Step 4: 타입체크** — Run: `cd frontend/allfolio_app && npx tsc --noEmit` → Expected: no errors.

- [ ] **Step 5: 커밋**

```bash
git add frontend/allfolio_app/types/cost-report.ts \
        frontend/allfolio_app/components/cost-report/CostInsights.tsx \
        frontend/allfolio_app/app/unified/reports/cost-report/[id]/page.tsx
git commit -m "feat(cost-fe): render factual cost insights section"
```

---

## Self-Review 체크
- [ ] Spec 항목(TER bp·비용률·최대비용처·비용구성·투자손익대비·null 생략) 모두 Task 1 테스트로 커버.
- [ ] `CostInsight`/`build` 시그니처가 Task1↔Task2에서 일치.
- [ ] FE `insights?` 옵셔널 → 구 아카이브 호환.
- [ ] 조언/추천 문구 없음(사실형만).

## Rollout
- 스키마 변경 없음 → 마이그레이션 불필요. main 병합 시 배포.
