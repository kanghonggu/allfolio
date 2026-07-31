# R-05 월간 변동 diff (SCR-RPT-08 ⑤) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 월말 보유명세서에 월간 변동(신규 편입·전량 매도·수량 변동)을 추가한다 — 거래이력으로 period.start 시점 수량을 재구성해 스냅샷 없이 diff.

**Architecture:** 신규 순수 `MonthlyChangeCalculator`가 `ua_stock_trades` 수량추적으로 period.start 시점 수량(qtyBefore)과 period.end 수량(qtyEnd)을 재구성해 3분류(신규편입/전량매도/수량변동)한다. `HoldingsReportGenerator`가 이미 계산해 둔 `trades`·`realizedBySymbol`·`nameBySymbol`(#53)을 재사용 → 생성자 변경 없이 `monthlyChange` body 추가. FE는 월간변동 섹션. **스키마 변경 없음.**

**Tech Stack:** Kotlin, Spring Boot 6, JUnit5, Next.js/React/TS.

**Spec:** `docs/superpowers/specs/2026-07-30-holdings-monthly-change-design.md`
**Branch:** `feat/holdings-monthly-change` (main에서 분기, #53 realized 포함)

---

## Reference: 현재 상태 & 관례

- `HoldingsReportGenerator`(`application/usecase/HoldingsReportGenerator.kt`, #53 버전): 생성자 `(assetRepository, accountRepository, fx, stockTradeRepository)`. `generate`에서 이미: `val trades = accounts.flatMap { stockTradeRepository.findByAccountId(it.id) }`, `val realizedBySymbol = FifoRealizedPnlCalculator.calculate(trades, period)`, `val nameBySymbol = trades.filter{symbol!=null}.groupBy{symbol}.mapValues{ 최신 tradedAt stockName }`. body `mapOf("summary","holdings","byAccount","byType","cash","realized","note")`.
- `StockTrade`: `symbol: String?`, `tradeType: StockTradeType`, `quantity: BigDecimal`, `price: BigDecimal`, `totalAmount: BigDecimal`, `tradedAt: LocalDate`, `createdAt: LocalDateTime`, `stockName`. `StockTradeType { BUY, SELL, CREDIT_BUY, CREDIT_SELL, MARGIN, DIVIDEND }`.
- `ReportPeriod(start, end)`. `FifoRealizedPnlCalculator.calculate(trades, period): Map<String, BigDecimal>`(당월 realized, #53).
- 테스트(`HoldingsReportGeneratorTest`, #53): `generator(assets: List<Asset>, accounts: List<Account>, trades: List<StockTrade> = emptyList())`, `stockTrade(accountId, type, symbol, qty, price, on)`, `asset(accountId, name, type, qty, purchase, current)`, `standardAssets()`, `standardAccounts()`(acctA=KIS, acctB=MANUAL), `FakeStockTradeRepo`. `mapper.readTree(...generate(userId, period).bodyJson)`. `period=monthly(2026,6)`, `userId`, `acctA`, `acctB`.
- FE: `types/holdings-report.ts`(`HoldingsReportBody`에 realized 등). `[id]/page.tsx`가 `fmtKrw`/`pctColor` import, `HoldingsSummary`/`HoldingsGrid`/realized 섹션/`ByAccountTable`/`ByTypeTable`/`CashTable` 렌더.

**공통 규칙:** BE 테스트 `cd allfolio-backend && ./gradlew :unified-asset:test --tests '<FQCN>'`. FE `cd frontend/allfolio_app && npx tsc --noEmit`.

---

## File Structure

**Backend — unified-asset**
- (신규) `application/usecase/MonthlyChangeCalculator.kt`
- (수정) `application/usecase/HoldingsReportGenerator.kt`
- (test 신규) `application/usecase/MonthlyChangeCalculatorTest.kt`
- (test 수정) `application/usecase/HoldingsReportGeneratorTest.kt`

**Frontend**
- (수정) `types/holdings-report.ts`
- (신규) `components/holdings-report/MonthlyChange.tsx`
- (수정) `app/unified/reports/holdings-report/[id]/page.tsx`

---

## Task 1: MonthlyChangeCalculator (TDD)

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/MonthlyChangeCalculator.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/MonthlyChangeCalculatorTest.kt`

- [ ] **Step 1: 실패하는 테스트 작성**

Create `MonthlyChangeCalculatorTest.kt`:
```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.unifiedasset.domain.account.StockTrade
import com.allfolio.unifiedasset.domain.account.StockTradeType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class MonthlyChangeCalculatorTest {

    private val acct = UUID.randomUUID()
    private val user = UUID.randomUUID()
    private val period = ReportPeriod.monthly(2026, 6) // 2026-06-01 ~ 2026-06-30

    private fun t(type: StockTradeType, symbol: String, qty: String, price: String, on: LocalDate) =
        StockTrade.create(
            accountId = acct, userId = user, tradeType = type, stockName = "$symbol name", symbol = symbol,
            quantity = BigDecimal(qty), price = BigDecimal(price),
            totalAmount = BigDecimal(qty).multiply(BigDecimal(price)), tradedAt = on, memo = null,
        )

    @Test
    fun `당월 첫 매수는 신규 편입`() {
        val m = MonthlyChangeCalculator.build(
            listOf(t(StockTradeType.BUY, "AAA", "10", "100", LocalDate.of(2026, 6, 5))),
            period, emptyMap(), mapOf("AAA" to "종목A"),
        )
        assertThat(m.newEntries).hasSize(1)
        assertThat(m.newEntries[0].symbol).isEqualTo("AAA")
        assertThat(m.newEntries[0].firstBuyDate).isEqualTo(LocalDate.of(2026, 6, 5))
        assertThat(m.newEntries[0].buyPrice).isEqualByComparingTo("100")
        assertThat(m.soldOut).isEmpty()
        assertThat(m.qtyChanges).isEmpty()
    }

    @Test
    fun `보유 중 당월 전량매도는 전량 매도이고 실현손익을 붙인다`() {
        val m = MonthlyChangeCalculator.build(
            listOf(
                t(StockTradeType.BUY, "BBB", "10", "100", LocalDate.of(2026, 5, 1)),  // 이전월 보유
                t(StockTradeType.SELL, "BBB", "10", "150", LocalDate.of(2026, 6, 20)),
            ),
            period, mapOf("BBB" to BigDecimal("500")), mapOf("BBB" to "종목B"),
        )
        assertThat(m.soldOut).hasSize(1)
        assertThat(m.soldOut[0].soldOutDate).isEqualTo(LocalDate.of(2026, 6, 20))
        assertThat(m.soldOut[0].realizedPnl).isEqualByComparingTo("500")
        assertThat(m.newEntries).isEmpty()
    }

    @Test
    fun `보유 유지 중 당월 추가매수는 수량 변동`() {
        val m = MonthlyChangeCalculator.build(
            listOf(
                t(StockTradeType.BUY, "CCC", "10", "100", LocalDate.of(2026, 5, 1)),  // 이전월 보유 10
                t(StockTradeType.BUY, "CCC", "5", "120", LocalDate.of(2026, 6, 10)),   // 당월 +5
            ),
            period, emptyMap(), mapOf("CCC" to "종목C"),
        )
        assertThat(m.qtyChanges).hasSize(1)
        assertThat(m.qtyChanges[0].netQty).isEqualByComparingTo("5")
        assertThat(m.qtyChanges[0].netBuyAmount).isEqualByComparingTo("600") // 5*120
        assertThat(m.newEntries).isEmpty()
        assertThat(m.soldOut).isEmpty()
    }

    @Test
    fun `당월 편입 후 전량매도 라운드트립은 전량 매도로 분류`() {
        val m = MonthlyChangeCalculator.build(
            listOf(
                t(StockTradeType.BUY, "DDD", "10", "100", LocalDate.of(2026, 6, 3)),
                t(StockTradeType.SELL, "DDD", "10", "150", LocalDate.of(2026, 6, 25)),
            ),
            period, mapOf("DDD" to BigDecimal("500")), mapOf("DDD" to "종목D"),
        )
        assertThat(m.soldOut).hasSize(1)
        assertThat(m.soldOut[0].soldOutDate).isEqualTo(LocalDate.of(2026, 6, 25))
        assertThat(m.newEntries).isEmpty()
    }

    @Test
    fun `당월 거래 없으면 변동 없음이고 배당은 무시된다`() {
        val m = MonthlyChangeCalculator.build(
            listOf(
                t(StockTradeType.BUY, "EEE", "10", "100", LocalDate.of(2026, 5, 1)),   // 이전월만
                t(StockTradeType.DIVIDEND, "EEE", "0", "0", LocalDate.of(2026, 6, 5)),  // 배당 무시
            ),
            period, emptyMap(), mapOf("EEE" to "E"),
        )
        assertThat(m.newEntries).isEmpty()
        assertThat(m.soldOut).isEmpty()
        assertThat(m.qtyChanges).isEmpty()
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests 'com.allfolio.unifiedasset.application.usecase.MonthlyChangeCalculatorTest' -q`
Expected: 컴파일 에러(`MonthlyChangeCalculator` 미존재).

- [ ] **Step 3: 계산기 구현**

Create `MonthlyChangeCalculator.kt`:
```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.unifiedasset.domain.account.StockTrade
import com.allfolio.unifiedasset.domain.account.StockTradeType
import java.math.BigDecimal
import java.time.LocalDate

data class NewEntry(val symbol: String, val name: String, val firstBuyDate: LocalDate, val buyPrice: BigDecimal)
data class SoldOut(val symbol: String, val name: String, val soldOutDate: LocalDate, val realizedPnl: BigDecimal)
data class QtyChange(val symbol: String, val name: String, val netQty: BigDecimal, val netBuyAmount: BigDecimal)
data class MonthlyChange(val newEntries: List<NewEntry>, val soldOut: List<SoldOut>, val qtyChanges: List<QtyChange>)

/**
 * 거래이력으로 period.start 시점 수량(qtyBefore)·period.end 수량(qtyEnd)을 재구성해 월간 변동 3분류 (순수).
 * 신규편입/전량매도/수량변동. period.end 이후·DIVIDEND/MARGIN 제외. KRW 취급.
 */
object MonthlyChangeCalculator {
    private val BUY = setOf(StockTradeType.BUY, StockTradeType.CREDIT_BUY)
    private val SELL = setOf(StockTradeType.SELL, StockTradeType.CREDIT_SELL)

    fun build(
        trades: List<StockTrade>,
        period: ReportPeriod,
        realizedBySymbol: Map<String, BigDecimal>,
        nameBySymbol: Map<String, String>,
    ): MonthlyChange {
        val newEntries = mutableListOf<NewEntry>()
        val soldOut = mutableListOf<SoldOut>()
        val qtyChanges = mutableListOf<QtyChange>()

        val bySym = trades
            .filter { it.symbol != null && (it.tradeType in BUY || it.tradeType in SELL) }
            .groupBy { it.symbol!! }

        for ((sym, list) in bySym) {
            val upToEnd = list.filter { !it.tradedAt.isAfter(period.end) }
                .sortedWith(compareBy({ it.tradedAt }, { it.createdAt }))
            val periodTrades = upToEnd.filter { !it.tradedAt.isBefore(period.start) }
            if (periodTrades.isEmpty()) continue
            val name = nameBySymbol[sym] ?: sym

            var running = BigDecimal.ZERO
            var qtyBefore = BigDecimal.ZERO
            var firstBuyInPeriod: StockTrade? = null
            var soldOutDate: LocalDate? = null
            for (tr in upToEnd) {
                val inPeriod = !tr.tradedAt.isBefore(period.start)
                if (!inPeriod) {
                    running = apply(running, tr)
                    qtyBefore = running
                    continue
                }
                if (firstBuyInPeriod == null && tr.tradeType in BUY) firstBuyInPeriod = tr
                val before = running
                running = apply(running, tr)
                if (before.signum() > 0 && running.signum() <= 0) soldOutDate = tr.tradedAt
            }
            val qtyEnd = running

            when {
                qtyBefore.signum() <= 0 && qtyEnd.signum() > 0 && firstBuyInPeriod != null ->
                    newEntries += NewEntry(sym, name, firstBuyInPeriod.tradedAt, firstBuyInPeriod.price)

                qtyEnd.signum() <= 0 && (qtyBefore.signum() > 0 || periodTrades.any { it.tradeType in BUY }) ->
                    soldOut += SoldOut(sym, name, soldOutDate ?: periodTrades.last().tradedAt, realizedBySymbol[sym] ?: BigDecimal.ZERO)

                qtyBefore.signum() > 0 && qtyEnd.signum() > 0 && (qtyEnd - qtyBefore).signum() != 0 -> {
                    val netBuy = periodTrades.filter { it.tradeType in BUY }.fold(BigDecimal.ZERO) { a, t -> a + t.totalAmount } -
                        periodTrades.filter { it.tradeType in SELL }.fold(BigDecimal.ZERO) { a, t -> a + t.totalAmount }
                    qtyChanges += QtyChange(sym, name, qtyEnd - qtyBefore, netBuy)
                }
            }
        }

        return MonthlyChange(
            newEntries.sortedBy { it.firstBuyDate },
            soldOut.sortedBy { it.soldOutDate },
            qtyChanges.sortedByDescending { it.netBuyAmount.abs() },
        )
    }

    private fun apply(qty: BigDecimal, t: StockTrade): BigDecimal =
        if (t.tradeType in BUY) qty + t.quantity else qty - t.quantity
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests 'com.allfolio.unifiedasset.application.usecase.MonthlyChangeCalculatorTest' -q`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/MonthlyChangeCalculator.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/MonthlyChangeCalculatorTest.kt
git commit -m "feat(holdings): add MonthlyChangeCalculator (신규편입/전량매도/수량변동)"
```

---

## Task 2: HoldingsReportGenerator 통합 (TDD)

**Files:**
- Modify: `application/usecase/HoldingsReportGenerator.kt`
- Test: `application/usecase/HoldingsReportGeneratorTest.kt`

- [ ] **Step 1: 신규 실패 테스트 추가**

Modify `HoldingsReportGeneratorTest.kt` — 클래스 끝 `}` 앞에 추가(기존 헬퍼 `generator`/`stockTrade`/`asset`/`standardAssets`/`standardAccounts`/`acctA` 재사용; import에 `StockTradeType`·`LocalDate`는 #53에서 이미 존재):
```kotlin
    @Test
    fun `월간 변동에 신규 편입과 전량 매도가 잡힌다`() {
        // 보유: 삼성전자(현재 보유). 거래: 삼성전자 당월 신규매수(신규편입), 매도된 종목 SOLD 이전월 매수+당월 전량매도(전량매도).
        val assets = listOf(asset(acctA, "삼성전자", AssetType.STOCK, "10", "100", "150"))
        val trades = listOf(
            stockTrade(acctA, StockTradeType.BUY, "삼성전자", "10", "100", LocalDate.of(2026, 6, 5)),
            stockTrade(acctA, StockTradeType.BUY, "SOLD", "10", "100", LocalDate.of(2026, 5, 1)),
            stockTrade(acctA, StockTradeType.SELL, "SOLD", "10", "150", LocalDate.of(2026, 6, 20)),
        )
        val body = mapper.readTree(generator(assets, standardAccounts(), trades).generate(userId, period).bodyJson)
        val mc = body["monthlyChange"]
        assertEquals(1, mc["newEntries"].size())
        assertEquals("삼성전자", mc["newEntries"][0]["symbol"].asText())
        assertEquals(1, mc["soldOut"].size())
        assertEquals("SOLD", mc["soldOut"][0]["symbol"].asText())
    }

    @Test
    fun `거래 없으면 월간 변동은 빈 구조다`() {
        val body = mapper.readTree(generator(standardAssets(), standardAccounts()).generate(userId, period).bodyJson)
        val mc = body["monthlyChange"]
        assertEquals(0, mc["newEntries"].size())
        assertEquals(0, mc["soldOut"].size())
        assertEquals(0, mc["qtyChanges"].size())
    }
```
> 참고: `stockTrade`/`asset`의 symbol은 대문자 정규화될 수 있으나(Asset.create/StockTrade.create) 한글 종목명은 그대로. "SOLD"는 대문자 유지. 매칭 일관.

- [ ] **Step 2: 실패 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests 'com.allfolio.unifiedasset.application.usecase.HoldingsReportGeneratorTest' -q`
Expected: FAIL (`monthlyChange` 키 부재 → NPE/assert 실패).

- [ ] **Step 3: 생성기 수정**

Modify `HoldingsReportGenerator.kt`:
1. `generate` 안, body 생성 직전(기존 `realizedBySymbol`/`nameBySymbol`/`cash` 등 계산 이후)에 추가:
```kotlin
        val monthlyChange = MonthlyChangeCalculator.build(trades, period, realizedBySymbol, nameBySymbol)
```
2. body mapOf에 추가(`"cash" to cash,` 또는 `"realized" to ...` 다음, `"note"` 앞):
```kotlin
            "monthlyChange" to mapOf(
                "newEntries" to monthlyChange.newEntries.map {
                    mapOf("symbol" to it.symbol, "name" to it.name, "firstBuyDate" to it.firstBuyDate.toString(), "buyPrice" to it.buyPrice)
                },
                "soldOut" to monthlyChange.soldOut.map {
                    mapOf("symbol" to it.symbol, "name" to it.name, "soldOutDate" to it.soldOutDate.toString(), "realizedPnl" to it.realizedPnl)
                },
                "qtyChanges" to monthlyChange.qtyChanges.map {
                    mapOf("symbol" to it.symbol, "name" to it.name, "netQty" to it.netQty, "netBuyAmount" to it.netBuyAmount)
                },
            ),
```
> 자산 0(빈 보고서) 경로가 있으면 그 body에도 동일 `monthlyChange` 빈 구조를 추가한다. (HoldingsReportGenerator는 assets 0이어도 일반 경로로 빈 리스트를 만들면 monthlyChange도 빈 구조가 됨 — 별도 emptyReport 분기 없으면 불필요.)

- [ ] **Step 4: 통과 확인(신규 + 기존)**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests 'com.allfolio.unifiedasset.application.usecase.HoldingsReportGeneratorTest' -q`
Expected: PASS (기존 + 신규 2).

- [ ] **Step 5: Commit**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/HoldingsReportGenerator.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/HoldingsReportGeneratorTest.kt
git commit -m "feat(holdings): integrate monthly change (신규편입/전량매도/수량변동) into report body"
```

---

## Task 3: 백엔드 전체 회귀

**Files:** (없음 — 검증)

- [ ] **Step 1: 전체 테스트**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test :backend-app:test -q`
Expected: BUILD SUCCESSFUL. 신규 `MonthlyChangeCalculatorTest`(5) + 확장 `HoldingsReportGeneratorTest`, 회귀 없음.

- [ ] **Step 2: 실패 시 진단 후 수정 → 재실행. Commit(수정 시)**

```bash
git add -A && git commit -m "test(holdings): fix regressions"
```

---

## Task 4: FE — 타입 + 월간변동 컴포넌트 + 렌더

**Files:**
- Modify: `frontend/allfolio_app/types/holdings-report.ts`
- Create: `frontend/allfolio_app/components/holdings-report/MonthlyChange.tsx`
- Modify: `frontend/allfolio_app/app/unified/reports/holdings-report/[id]/page.tsx`

- [ ] **Step 1: 타입 확장**

Modify `types/holdings-report.ts` — 신규 인터페이스 추가 + body 필드:
```ts
export interface HoldingNewEntry { symbol: string; name: string; firstBuyDate: string; buyPrice: number }
export interface HoldingSoldOut { symbol: string; name: string; soldOutDate: string; realizedPnl: number }
export interface HoldingQtyChange { symbol: string; name: string; netQty: number; netBuyAmount: number }
export interface HoldingMonthlyChange {
  newEntries: HoldingNewEntry[]
  soldOut: HoldingSoldOut[]
  qtyChanges: HoldingQtyChange[]
}
```
`HoldingsReportBody`에 추가:
```ts
  monthlyChange?: HoldingMonthlyChange
```

- [ ] **Step 2: 월간변동 컴포넌트 생성**

Create `frontend/allfolio_app/components/holdings-report/MonthlyChange.tsx`:
```tsx
import { fmtKrw, pctColor } from '@/lib/report-format'
import type { HoldingMonthlyChange } from '@/types/holdings-report'

export function MonthlyChange({ data }: { data: HoldingMonthlyChange }) {
  const empty = data.newEntries.length === 0 && data.soldOut.length === 0 && data.qtyChanges.length === 0
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">월간 변동</h2>
      {empty ? (
        <div className="rounded-xl border border-gray-700 bg-gray-900 p-6 text-center text-sm text-gray-500">당월 변동 없음</div>
      ) : (
        <div className="grid gap-4 lg:grid-cols-3">
          <div className="rounded-xl border border-gray-800 bg-gray-900 p-4">
            <h3 className="mb-2 text-sm font-medium text-emerald-300">신규 편입</h3>
            {data.newEntries.length === 0 ? <p className="text-xs text-gray-600">-</p> : data.newEntries.map((e) => (
              <div key={e.symbol} className="flex justify-between border-b border-gray-800 py-1 text-xs last:border-b-0">
                <span className="text-gray-200">{e.name} <span className="text-gray-500">{e.symbol}</span></span>
                <span className="tabular-nums text-gray-400">{e.firstBuyDate}</span>
              </div>
            ))}
          </div>
          <div className="rounded-xl border border-gray-800 bg-gray-900 p-4">
            <h3 className="mb-2 text-sm font-medium text-red-300">전량 매도</h3>
            {data.soldOut.length === 0 ? <p className="text-xs text-gray-600">-</p> : data.soldOut.map((e) => (
              <div key={e.symbol} className="flex justify-between border-b border-gray-800 py-1 text-xs last:border-b-0">
                <span className="text-gray-200">{e.name} <span className="text-gray-500">{e.symbol}</span></span>
                <span className={`tabular-nums ${pctColor(e.realizedPnl)}`}>{fmtKrw(e.realizedPnl)}</span>
              </div>
            ))}
          </div>
          <div className="rounded-xl border border-gray-800 bg-gray-900 p-4">
            <h3 className="mb-2 text-sm font-medium text-gray-300">수량 변동</h3>
            {data.qtyChanges.length === 0 ? <p className="text-xs text-gray-600">-</p> : data.qtyChanges.map((e) => (
              <div key={e.symbol} className="flex justify-between border-b border-gray-800 py-1 text-xs last:border-b-0">
                <span className="text-gray-200">{e.name} <span className="text-gray-500">{e.symbol}</span></span>
                <span className={`tabular-nums ${pctColor(e.netBuyAmount)}`}>{e.netQty > 0 ? '+' : ''}{e.netQty} · {fmtKrw(e.netBuyAmount)}</span>
              </div>
            ))}
          </div>
        </div>
      )}
    </section>
  )
}
```

- [ ] **Step 3: 상세 페이지에 렌더**

Modify `app/unified/reports/holdings-report/[id]/page.tsx`:
1. import 추가(다른 holdings 컴포넌트 import 옆):
```tsx
import { MonthlyChange } from '@/components/holdings-report/MonthlyChange'
```
2. `<HoldingsGrid holdings={body.holdings} />` (및 realized 섹션) 다음, `<div className="grid gap-4 lg:grid-cols-2">` (ByAccount/ByType) 앞에 추가:
```tsx
      {body.monthlyChange && <MonthlyChange data={body.monthlyChange} />}
```

- [ ] **Step 4: 타입 체크**

Run: `cd frontend/allfolio_app && npx tsc --noEmit`
Expected: 에러 없음.

- [ ] **Step 5: Commit**

```bash
git add frontend/allfolio_app/types/holdings-report.ts \
        frontend/allfolio_app/components/holdings-report/MonthlyChange.tsx \
        "frontend/allfolio_app/app/unified/reports/holdings-report/[id]/page.tsx"
git commit -m "feat(holdings): render monthly-change section (신규편입/전량매도/수량변동)"
```

---

## Task 5: 통합 검증

**Files:** (없음 — 검증)

- [ ] **Step 1: 백엔드 + FE 최종 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test :backend-app:test -q` → BUILD SUCCESSFUL
Run: `cd frontend/allfolio_app && npx tsc --noEmit` → 에러 없음

- [ ] **Step 2: 커버리지 요약 보고**

계산기(신규편입·전량매도+실현손익·수량변동·라운드트립·거래없음 5케이스) + 생성기(monthlyChange 섹션) + 기존 회귀 검증. 스키마 변경 없어 DB 검증 불필요. 결과 요약 보고.

- [ ] **Step 3: (커밋 불필요)**

---

## Rollout (배포 시 — 사용자 실행)
- **스키마 변경 없음** → 운영 마이그레이션 불필요.
- main 병합 → Render 자동배포(BE) → FE 배포.
- 검증: 수동 STOCK 계좌 당월 매수·매도 계정 → HOLDINGS 리포트 → 월간 변동(신규편입·전량매도·수량변동) 확인.

---

## Notes / 주의
- start 시점 수량 재구성: `tradedAt < period.start` 순수량. period.end 이후·DIVIDEND/MARGIN 제외.
- 3분류 상호배타(when 순서): 신규편입(qtyEnd>0) → 전량매도(qtyEnd≤0) → 수량변동. 라운드트립(당월 편입+청산)은 전량매도.
- 전량매도 실현손익은 `realizedBySymbol`(#53 당월 FIFO) 재사용. 생성자·주입 변경 없음(기존 trades 재사용).
- monthlyChange body는 옵셔널(FE) → 구 아카이브 호환.
- 커버리지: 수동 STOCK 계좌 거래만. KRW/원통화 혼용(순매수금액 KRW, 편입가 원통화) 한계 유지.
- 범위 밖(후속): 지역 그룹핑, Excel/ISIN, 통화 정규화.
```
