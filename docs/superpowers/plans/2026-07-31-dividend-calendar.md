# R-03 배당 캘린더 (지급 이력 패턴, 사실형) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 배당·이자 보고서(R-03)에 최근 12개월 지급 이력에서 도출한 종목별 배당 지급 캘린더(주기·지급월·횟수·최근일·TTM 순수취)를 추가한다. 예측/조언 없이 사실형만.

**Architecture:** 순수 `DividendCalendarCalculator`가 기존 `ttm` 배당 이력을 종목별로 집계 → `DividendInterestReportGenerator`가 body `dividendCalendar` 직렬화 → FE 옵셔널 렌더.

**Tech Stack:** Kotlin/Spring(unified-asset), JUnit, Next.js/React/TypeScript.

Spec: `docs/superpowers/specs/2026-07-31-dividend-calendar-design.md`

---

## Task 1: DividendCalendarCalculator (순수 계산기 + 테스트)

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/DividendCalendarCalculator.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/DividendCalendarCalculatorTest.kt`

- [ ] **Step 1: 실패 테스트 작성** — `DividendCalendarCalculatorTest.kt`. **확인된 시그니처**: `DividendRecord(payDate: LocalDate, stockName: String, symbol: String?, accountName: String, provider: String, gross: BigDecimal, tax: BigDecimal)` 이고 `net`은 `gross - tax` 파생 프로퍼티(생성자 인자 아님). 따라서 헬퍼는 gross/tax만 받고 net은 계산됨. 테스트 골격:

```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.DividendRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class DividendCalendarCalculatorTest {
    // tax=0 이므로 net == gross. ttmNet 기대값은 gross 합.
    private fun rec(month: Int, name: String, sym: String?, gross: String, tax: String = "0", acct: String = "계좌", provider: String = "KIS") =
        DividendRecord(LocalDate.of(2026, month, 15), name, sym, acct, provider, BigDecimal(gross), BigDecimal(tax))

    @Test
    fun `월배당은 12회 지급-월1~12로 분류된다`() {
        val ttm = (1..12).map { rec(it, "리얼티", "O", "100") }
        val e = DividendCalendarCalculator.build(ttm).first()
        assertThat(e.cadence).isEqualTo("월배당")
        assertThat(e.payCount).isEqualTo(12)
        assertThat(e.paidMonths).containsExactly(1,2,3,4,5,6,7,8,9,10,11,12)
    }

    @Test
    fun `분기-반기-연1회-비정기 분류`() {
        assertThat(DividendCalendarCalculator.build(listOf(3,6,9,12).map { rec(it,"A","A","10") }).first().cadence).isEqualTo("분기배당")
        assertThat(DividendCalendarCalculator.build(listOf(6,12).map { rec(it,"B","B","10") }).first().cadence).isEqualTo("반기배당")
        assertThat(DividendCalendarCalculator.build(listOf(rec(5,"C","C","10"))).first().cadence).isEqualTo("연 1회/단발")
        assertThat(DividendCalendarCalculator.build(listOf(1,2,7).map { rec(it,"D","D","10") }).first().cadence).isEqualTo("비정기")
    }

    @Test
    fun `같은 종목 다계좌는 합산되고 lastPayDate는 최대-정렬은 ttmNet 내림차순`() {
        val ttm = listOf(
            rec(3, "삼성", "005930", "100", acct = "A"),
            rec(9, "삼성", "005930", "300", acct = "B"),
            rec(6, "애플", "AAPL", "500"),
        )
        val list = DividendCalendarCalculator.build(ttm)
        assertThat(list.map { it.stockName }).containsExactly("애플", "삼성")  // 500 > 400
        val samsung = list.first { it.stockName == "삼성" }
        assertThat(samsung.payCount).isEqualTo(2)
        assertThat(samsung.ttmNet).isEqualByComparingTo(BigDecimal("400"))
        assertThat(samsung.lastPayDate).isEqualTo(LocalDate.of(2026, 9, 15))
        assertThat(samsung.paidMonths).containsExactly(3, 9)
    }
}
```

- [ ] **Step 2: 테스트 실패 확인** — Run: `cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :unified-asset:test --tests "*DividendCalendarCalculatorTest*"` → Expected: compile FAIL.

- [ ] **Step 3: 최소 구현** — `DividendCalendarCalculator.kt` (spec §3.1의 코드 그대로). import: `com.allfolio.unifiedasset.application.port.DividendRecord`, `java.math.BigDecimal`, `java.time.LocalDate`.

- [ ] **Step 4: 테스트 통과 확인** — Run: `cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :unified-asset:test --tests "*DividendCalendarCalculatorTest*"` → Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/DividendCalendarCalculator.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/DividendCalendarCalculatorTest.kt
git commit -m "feat(dividend): add DividendCalendarCalculator (TDD)"
```

---

## Task 2: DividendInterestReportGenerator 통합 + 테스트

**Files:**
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/DividendInterestReportGenerator.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/DividendInterestReportGeneratorTest.kt`

- [ ] **Step 1: 실패 테스트 확장** — 기존 `DividendInterestReportGeneratorTest`를 먼저 읽어 셋업(generator·userId·period·mapper·fake ledger)과 assertion 스타일(assertj vs JUnit5)을 파악하고 **그 스타일에 맞춰** 케이스 추가. 최근 12개월 내 배당이 있는 픽스처로 generate 후 `dividendCalendar` 검증:

```kotlin
    @Test
    fun `body에 배당 캘린더(지급 이력 패턴)가 포함된다`() {
        val report = generator.generate(userId, period)   // 기존 테스트 패턴 사용
        val body = mapper.readTree(report.bodyJson)
        val cal = body.get("dividendCalendar")
        assertThat(cal).isNotNull            // 파일 스타일이 JUnit5면 assertNotNull 사용
        if (cal.size() > 0) {
            val first = cal.first()
            // cadence·paidMonths·payCount·lastPayDate·ttmNet 필드 존재
            listOf("cadence","paidMonths","payCount","lastPayDate","ttmNet").forEach {
                assertThat(first.has(it)).isTrue
            }
        }
    }
```
주의: 기존 테스트에 배당 픽스처가 period.end 기준 최근 12개월 안에 있어야 캘린더가 채워짐. 없으면 최소 1종목 배당을 추가하는 헬퍼/픽스처를 기존 방식대로 구성. 파일 스타일 혼용 금지.

- [ ] **Step 2: 테스트 실패 확인** — Run: `cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :unified-asset:test --tests "*DividendInterestReportGeneratorTest*"` → Expected: FAIL (dividendCalendar 노드 없음).

- [ ] **Step 3: 생성기 통합** — `DividendInterestReportGenerator.generate` 내 `byCountry` 산출 뒤, `body = mapOf(...)` 생성 전에:
```kotlin
        val calendar = DividendCalendarCalculator.build(ttm)
```
`body` 맵의 `"byCountry" to byCountry,` 다음에 추가:
```kotlin
            "dividendCalendar" to calendar.map {
                mapOf("symbol" to it.symbol, "stockName" to it.stockName, "cadence" to it.cadence,
                      "paidMonths" to it.paidMonths, "payCount" to it.payCount,
                      "lastPayDate" to it.lastPayDate.toString(), "ttmNet" to it.ttmNet)
            },
```
KDoc "v1 제외: ...배당 캘린더·예상" → "배당 캘린더(지급 이력 패턴, 사실형) 포함. 후속: 외부 확정 지급일·기대세율 비교."

- [ ] **Step 4: 테스트 통과 확인** — Run: `cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :unified-asset:test --tests "*DividendInterestReportGeneratorTest*" --tests "*DividendCalendarCalculatorTest*"` → Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/DividendInterestReportGenerator.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/DividendInterestReportGeneratorTest.kt
git commit -m "feat(dividend): surface dividend payment calendar in report body"
```

---

## Task 3: 백엔드 회귀

- [ ] **Step 1: unified-asset 전체 테스트** — Run: `cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :unified-asset:test` → Expected: BUILD SUCCESSFUL.
- [ ] **Step 2: (실패 시) 수정 후 재실행.**

---

## Task 4: Frontend — 타입 + 컴포넌트 + 페이지

**Files:**
- Modify: `frontend/allfolio_app/types/dividend-report.ts` (실제 파일명 확인 — 아래 Step 1)
- Create: `frontend/allfolio_app/components/dividend-report/DividendCalendar.tsx`
- Modify: `frontend/allfolio_app/app/unified/reports/dividend-report/[id]/page.tsx`

- [ ] **Step 1: 타입 추가** — dividend 리포트 타입 파일(`types/` 아래 dividend 관련 .ts, 실제명 확인)에 추가:
```ts
export interface DividendCalendarEntry {
  symbol: string | null
  stockName: string
  cadence: string
  paidMonths: number[]
  payCount: number
  lastPayDate: string
  ttmNet: number
}
```
그리고 dividend 리포트 BODY 인터페이스(summary/receipts/monthly/bySymbol/byCountry 필드 보유)에 `dividendCalendar?: DividendCalendarEntry[]`(옵셔널) 추가.

- [ ] **Step 2: 컴포넌트 생성** — `components/dividend-report/DividendCalendar.tsx`. 같은 폴더의 bySymbol/byCountry 표 컴포넌트를 읽어 룩앤필·import 경로(fmtKrw 등)를 맞출 것. 골격:
```tsx
import { fmtKrw } from '@/lib/report-format'
import type { DividendCalendarEntry } from '@/types/dividend-report'

const MONTHS = [1,2,3,4,5,6,7,8,9,10,11,12]

export function DividendCalendar({ rows }: { rows: DividendCalendarEntry[] }) {
  if (!rows || rows.length === 0) return null
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">배당 지급 캘린더</h2>
      <p className="text-xs text-gray-500">최근 12개월 지급 이력 기반 패턴이며, 향후 지급을 보장·예측하지 않습니다.</p>
      <div className="overflow-x-auto rounded-xl border border-gray-700 bg-gray-900">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
              <th className="p-3">종목</th><th className="p-3">주기</th>
              <th className="p-3">지급 월</th><th className="p-3 text-right">TTM 횟수</th>
              <th className="p-3">최근 지급일</th><th className="p-3 text-right">TTM 순수취</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={`${r.symbol ?? r.stockName}-${i}`} className="border-b border-gray-800 last:border-b-0">
                <td className="p-3">
                  <span className="font-medium text-gray-100">{r.stockName}</span>
                  {r.symbol && <span className="ml-2 text-xs text-gray-500">{r.symbol}</span>}
                </td>
                <td className="p-3"><span className="rounded bg-gray-800 px-2 py-0.5 text-xs text-gray-300">{r.cadence}</span></td>
                <td className="p-3">
                  <div className="flex gap-0.5">
                    {MONTHS.map((m) => (
                      <span key={m} className={`inline-block h-4 w-4 rounded-sm text-center text-[9px] leading-4 ${r.paidMonths.includes(m) ? 'bg-emerald-800 text-emerald-100' : 'bg-gray-800 text-gray-600'}`}>{m}</span>
                    ))}
                  </div>
                </td>
                <td className="p-3 text-right tabular-nums text-gray-300">{r.payCount}</td>
                <td className="p-3 tabular-nums text-gray-400">{r.lastPayDate}</td>
                <td className="p-3 text-right tabular-nums text-gray-100">{fmtKrw(r.ttmNet)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}
```
(import 경로·클래스는 같은 폴더 기존 컴포넌트와 실제 일치시킬 것.)

- [ ] **Step 3: 페이지 렌더** — `app/unified/reports/dividend-report/[id]/page.tsx`를 읽고 import 추가 후 bySymbol/byCountry 근처에:
```tsx
{body.dividendCalendar && body.dividendCalendar.length > 0 && <DividendCalendar rows={body.dividendCalendar} />}
```
(페이지의 실제 파싱 body 변수명 확인 후 맞춤.)

- [ ] **Step 4: 타입체크** — Run: `cd /Users/hong9/IdeaProjects/allfolio/frontend/allfolio_app && npx tsc --noEmit` → Expected: no errors. (`paidMonths.includes(m)`는 배열 메서드라 downlevelIteration 무관.)

- [ ] **Step 5: 커밋**

```bash
git add frontend/allfolio_app/types/dividend-report.ts \
        frontend/allfolio_app/components/dividend-report/DividendCalendar.tsx \
        "frontend/allfolio_app/app/unified/reports/dividend-report/[id]/page.tsx"
git commit -m "feat(dividend-fe): render dividend payment calendar (history-based)"
```

---

## Self-Review 체크
- [ ] Spec 항목(cadence 분류·paidMonths·payCount·lastPayDate·ttmNet·합산·정렬·이력 기반 문구) 모두 커버.
- [ ] `DividendCalendarEntry`/`build` 시그니처 Task1↔Task2↔FE 일치.
- [ ] FE `dividendCalendar?` 옵셔널 → 구 아카이브 호환.
- [ ] **예측·금액추정·조언 문구 없음**(과거 이력 서술만) + "보장·예측 아님" 명시.

## Rollout
- 스키마 변경 없음 → 마이그레이션 불필요. main 병합 시 배포.
