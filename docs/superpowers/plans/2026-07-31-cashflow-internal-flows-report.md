# R-06 환전/이체 Phase 2 — 리포트 전용 섹션 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 현금흐름 보고서(R-06)에 환전/계좌간이체 전용 섹션(linkId 페어 집계)을 추가한다.

**Architecture:** 순수 `InternalFlowCalculator`가 period flows의 내부유형을 linkId로 페어 그룹핑 → `CashflowReportGenerator` body `internalFlows` → FE 섹션.

**Tech Stack:** Kotlin/Spring(unified-asset), JUnit, Next.js/React/TS.

Spec: `docs/superpowers/specs/2026-07-31-cashflow-internal-flows-report-design.md`
**Base branch: `feat/cashflow-internal-flows` (Phase 1 #62 위 스택)**. gradle은 `/Users/hong9/IdeaProjects/allfolio/allfolio-backend`.

---

## Task 1: InternalFlowCalculator (순수 + 테스트)

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/InternalFlowCalculator.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/InternalFlowCalculatorTest.kt`

- [ ] **Step 1: 실패 테스트** — `InternalFlowCalculatorTest.kt`. `CashFlow.transferPair`/`fxPair`로 페어 생성(둘 다 List로 펼침):

```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.domain.cashflow.FlowType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class InternalFlowCalculatorTest {
    private val user = UUID.randomUUID()
    private val a1 = UUID.randomUUID(); private val a2 = UUID.randomUUID()
    private val names = mapOf(a1 to "한투", a2 to "미래에셋")
    private fun d(day: Int) = LocalDate.of(2026, 6, day)

    @Test
    fun `이체 페어는 계좌간이체로 from-to 계좌명과 함께 집계된다`() {
        val (out, inn) = CashFlow.transferPair(user, a1, a2, d(10), BigDecimal("500"), "KRW", BigDecimal("500"), "이체")
        val r = InternalFlowCalculator.build(listOf(out, inn), names).single()
        assertThat(r.kind).isEqualTo("계좌간이체")
        assertThat(r.fromAccount).isEqualTo("한투")
        assertThat(r.toAccount).isEqualTo("미래에셋")
        assertThat(r.amountKrw).isEqualByComparingTo("500")
        assertThat(r.fromCurrency).isNull()
    }

    @Test
    fun `환전 페어는 환전으로 통화-금액과 함께 집계된다`() {
        val (out, inn) = CashFlow.fxPair(user, a1, d(11),
            BigDecimal("1300000"), "KRW", BigDecimal("1300000"),
            BigDecimal("1000"), "USD", BigDecimal("1300000"), "환전")
        val r = InternalFlowCalculator.build(listOf(out, inn), names).single()
        assertThat(r.kind).isEqualTo("환전")
        assertThat(r.fromCurrency).isEqualTo("KRW")
        assertThat(r.toCurrency).isEqualTo("USD")
        assertThat(r.fromAmount).isEqualByComparingTo("1300000")
        assertThat(r.toAmount).isEqualByComparingTo("1000")
        assertThat(r.amountKrw).isEqualByComparingTo("1300000")
    }

    @Test
    fun `여러 그룹은 날짜 내림차순-외부유형 제외-고아 레그 스킵`() {
        val (o1, i1) = CashFlow.transferPair(user, a1, a2, d(5), BigDecimal("100"), "KRW", BigDecimal("100"), null)
        val (o2, i2) = CashFlow.transferPair(user, a1, a2, d(20), BigDecimal("200"), "KRW", BigDecimal("200"), null)
        val deposit = CashFlow.create(user, a1, d(9), FlowType.DEPOSIT, BigDecimal("1"), "KRW", BigDecimal("1"), null)
        val flows = listOf(o1, i1, o2, i2, deposit, o1.let {  // 고아: IN 없는 OUT 하나 추가
            CashFlow.create(user, a1, d(1), FlowType.FX_OUT, BigDecimal("1"), "KRW", BigDecimal("1"), null, UUID.randomUUID())
        })
        val list = InternalFlowCalculator.build(flows, names)
        assertThat(list.map { it.date }).containsExactly(d(20), d(5))  // 내림차순, deposit·고아 제외
    }
}
```
주의: `CashFlow.create`의 시그니처는 `(userId, accountId, flowDate, type, amount, currency, amountKrw, memo, linkId=null)`. 고아 레그 테스트는 linkId를 명시(UUID.randomUUID())해 페어 없는 단일 내부 레그를 만든다.

- [ ] **Step 2: 실패 확인** — Run: `./gradlew :unified-asset:test --tests "*InternalFlowCalculatorTest*"` → Expected: compile FAIL.

- [ ] **Step 3: 구현** — `InternalFlowCalculator.kt`(spec §3.1 코드). import: `com.allfolio.unifiedasset.domain.cashflow.CashFlow`, `com.allfolio.unifiedasset.domain.cashflow.FlowType`, BigDecimal, LocalDate, UUID.

- [ ] **Step 4: 통과 확인** — Run: `./gradlew :unified-asset:test --tests "*InternalFlowCalculatorTest*"` → Expected: PASS.

- [ ] **Step 5: 커밋**
```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/InternalFlowCalculator.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/InternalFlowCalculatorTest.kt
git commit -m "feat(cashflow): add InternalFlowCalculator pairing internal legs by linkId (TDD)"
```

---

## Task 2: CashflowReportGenerator 통합 + 테스트

**Files:**
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/CashflowReportGenerator.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/CashflowReportGeneratorTest.kt`

- [ ] **Step 1: 실패 테스트 확장** — 기존 스타일(JUnit5). 내부유형 flow 포함 시 body.internalFlows에 페어 표기 검증. 기존 `flowOn`/`generator` 헬퍼 재사용:
```kotlin
    @Test
    fun `body_internalFlows에 환전-이체 페어가 표기된다`() {
        val flows = listOf(
            deposit(3, "1000000"),
            flowOn(LocalDate.of(2026,6,10), FlowType.TRANSFER_OUT, "500000"),
            flowOn(LocalDate.of(2026,6,10), FlowType.TRANSFER_IN,  "500000"),
        )
        // 주의: flowOn이 linkId를 세팅하지 않으면 페어 그룹핑이 안 됨.
        // → 이 테스트는 transferPair로 만든 레그를 넣도록 별도 헬퍼가 필요.
        // 아래 Step 1-b 참고.
    }
```
- [ ] **Step 1-b: 페어 픽스처** — 기존 `flowOn`은 linkId=null이라 페어 그룹핑 불가. 테스트에 페어 헬퍼 추가:
```kotlin
    private fun transferLegs(day: Int, krw: String) =
        CashFlow.transferPair(userId, UUID.randomUUID(), UUID.randomUUID(),
            LocalDate.of(2026, 6, day), BigDecimal(krw), "KRW", BigDecimal(krw), null).toList()
```
그리고 실제 테스트:
```kotlin
    @Test
    fun `body_internalFlows에 이체 페어가 표기되고 외부 집계는 불변`() {
        val flows = listOf(deposit(3, "1000000"), withdrawal(5, "200000")) + transferLegs(10, "500000")
        val body = mapper.readTree(generator(flows).generate(userId, period).bodyJson)
        val internal = body["internalFlows"]
        assertEquals(1, internal.size())
        assertEquals("계좌간이체", internal[0]["kind"].asText())
        assertEquals(500000.0, internal[0]["amountKrw"].asDouble(), 0.01)
        // 외부 집계 불변
        val types = body["byType"].associate { it["type"].asText() to it["amount"].asDouble() }
        assertEquals(1000000.0, types["입금"] ?: 0.0, 0.01)
    }
```
(import에 `com.allfolio.unifiedasset.domain.cashflow.CashFlow`, `java.util.UUID` 필요할 수 있음 — 파일에 이미 있으면 재사용. assertion/헬퍼 스타일은 기존 파일과 일치.)

- [ ] **Step 2: 실패 확인** — Run: `./gradlew :unified-asset:test --tests "*CashflowReportGeneratorTest*"` → Expected: FAIL(internalFlows 노드 없음).

- [ ] **Step 3: 구현** — `CashflowReportGenerator.generate`: `special` 산출 근처(또는 body 직전)에:
```kotlin
        val internalFlows = InternalFlowCalculator.build(flows, acctNames)
```
`body = mapOf(...)`에 적절한 위치(예: specialTransactions 다음)에 추가:
```kotlin
            "internalFlows" to internalFlows.map { mapOf(
                "date" to it.date.toString(), "kind" to it.kind,
                "fromAccount" to it.fromAccount, "toAccount" to it.toAccount,
                "fromCurrency" to it.fromCurrency, "toCurrency" to it.toCurrency,
                "fromAmount" to it.fromAmount, "toAmount" to it.toAmount,
                "amountKrw" to it.amountKrw) },
```
KDoc 갱신(spec §3.2).

- [ ] **Step 4: 통과 확인** — Run: `./gradlew :unified-asset:test --tests "*CashflowReportGeneratorTest*" --tests "*InternalFlowCalculatorTest*"` → Expected: PASS.

- [ ] **Step 5: 커밋**
```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/CashflowReportGenerator.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/CashflowReportGeneratorTest.kt
git commit -m "feat(cashflow): surface internal flows (transfer/fx) section in report body"
```

---

## Task 3: 백엔드 회귀
- [ ] **Step 1:** Run: `./gradlew :unified-asset:test` → BUILD SUCCESSFUL.
- [ ] **Step 2:** (실패 시) 수정 후 재실행.

---

## Task 4: Frontend — 타입 + 컴포넌트 + 페이지

**Files:**
- Modify: `frontend/allfolio_app/types/cashflow-report.ts`
- Create: `frontend/allfolio_app/components/cashflow-report/InternalFlows.tsx`
- Modify: `frontend/allfolio_app/app/unified/reports/cashflow-report/[id]/page.tsx`

- [ ] **Step 1: 타입** — `types/cashflow-report.ts`에 추가:
```ts
export interface CashflowInternalFlow {
  date: string
  kind: string
  fromAccount: string | null
  toAccount: string | null
  fromCurrency: string | null
  toCurrency: string | null
  fromAmount: number | null
  toAmount: number | null
  amountKrw: number
}
```
그리고 `CashflowReportBody`에 `internalFlows?: CashflowInternalFlow[]`(옵셔널) 추가.

- [ ] **Step 2: 컴포넌트** — `components/cashflow-report/InternalFlows.tsx`. 같은 폴더 `SpecialTransactions.tsx`/`CashflowDetails.tsx` 룩앤필 참고(fmtKrw from `@/lib/report-format`). 골격:
```tsx
import { fmtKrw } from '@/lib/report-format'
import type { CashflowInternalFlow } from '@/types/cashflow-report'

export function InternalFlows({ rows }: { rows: CashflowInternalFlow[] }) {
  if (!rows || rows.length === 0) return null
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">환전·계좌간이체</h2>
      <p className="text-xs text-gray-500">내부이동은 외부 유입/유출에서 제외되어 별도 표기됩니다.</p>
      <div className="overflow-x-auto rounded-xl border border-gray-800 bg-gray-900">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="p-3">날짜</th><th className="p-3">유형</th>
              <th className="p-3">내용</th><th className="p-3 text-right">금액(KRW)</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={`${r.date}-${i}`} className="border-b border-gray-800 last:border-b-0">
                <td className="p-3 tabular-nums text-gray-400">{r.date}</td>
                <td className="p-3"><span className="rounded bg-gray-800 px-2 py-0.5 text-xs text-gray-300">{r.kind}</span></td>
                <td className="p-3 text-gray-300">
                  {r.kind === '환전'
                    ? `${r.fromCurrency} ${r.fromAmount?.toLocaleString()} → ${r.toCurrency} ${r.toAmount?.toLocaleString()}`
                    : `${r.fromAccount} → ${r.toAccount}`}
                </td>
                <td className="p-3 text-right tabular-nums text-gray-100">{fmtKrw(r.amountKrw)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}
```
(import 경로·클래스는 같은 폴더 기존 컴포넌트와 일치시킬 것.)

- [ ] **Step 3: 페이지** — `[id]/page.tsx`에 import 후 SpecialTransactions/Reconciliation 근처에:
```tsx
{body.internalFlows && body.internalFlows.length > 0 && <InternalFlows rows={body.internalFlows} />}
```
(실제 body 변수명 확인 후 맞춤.)

- [ ] **Step 4: 타입체크** — Run: `cd /Users/hong9/IdeaProjects/allfolio/frontend/allfolio_app && npx tsc --noEmit` → no errors.

- [ ] **Step 5: 커밋**
```bash
git add frontend/allfolio_app/types/cashflow-report.ts \
        frontend/allfolio_app/components/cashflow-report/InternalFlows.tsx \
        "frontend/allfolio_app/app/unified/reports/cashflow-report/[id]/page.tsx"
git commit -m "feat(cashflow-fe): render internal flows (transfer/fx) section"
```

---

## Self-Review 체크
- [ ] 이체/환전 페어 그룹핑·날짜정렬·고아 스킵·외부유형 제외 커버.
- [ ] `InternalFlowEntry`/`build` 시그니처 Task1↔Task2↔FE 일치.
- [ ] FE `internalFlows?` 옵셔널 → 구 아카이브 안전.
- [ ] 외부 집계 불변(Phase 1 단언 유지).

## Rollout
- 스키마 변경 없음. **#62 머지 후** 이 PR을 main으로 머지(그 전엔 #62 브랜치 스택).
