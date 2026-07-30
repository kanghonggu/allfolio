# R-05 당월 실현손익 (FIFO) 후속 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 월말 보유명세서(R-05)에 당월 FIFO 실현손익을 추가한다 — `ua_stock_trades`에서 `FifoCostEngine`으로 심볼별 당월 실현손익을 계산해 `HoldingsReportGenerator` 본문(summary·holdings 컬럼·realized 섹션)에 싣고 FE 보유명세 화면에 표시.

**Architecture:** `unified-asset`가 `trade` 모듈에 의존(순환 없음)하여 검증된 `FifoCostEngine.apply()`를 재사용. 신규 순수 `FifoRealizedPnlCalculator`가 사용자 `StockTrade`(계좌 순회)를 심볼별로 FIFO 재생하고 period.start 직전 누적 실현손익을 스냅샷해 당월분만 산출. `HoldingsReportGenerator`가 이를 본문에 통합. FE는 요약 카드 + 그리드 컬럼 추가. **스키마 변경 없음.**

**Tech Stack:** Kotlin, Spring Boot 6, JUnit5, Next.js/React/TS.

**Spec:** `docs/superpowers/specs/2026-07-30-holdings-realized-pnl-design.md`
**Branch:** `feat/holdings-realized-pnl` (main에서 분기)

---

## Reference: 현재 상태 & 관례

- `trade/domain/FifoCostEngine.kt` (순수 object): `apply(position: LotPosition, tradeType: TradeType, quantity, price, fee=ZERO): LotPosition`. `LotPosition.EMPTY`, `LotPosition.realizedPnl`(누적 실현손익). `TradeType { BUY, SELL }`. SELL: FIFO 소진 + `realizedPnl += soldQty*price − consumedCost − fee`, 과매도 clamp.
- `unified-asset` 도메인 `StockTrade`(`domain/account/StockTrade.kt`): `symbol: String?`(생성 시 uppercase), `quantity, price, fee, tax: BigDecimal`, `tradeType: StockTradeType`, `tradedAt: LocalDate`, `createdAt: LocalDateTime`, `stockName`. `StockTradeType { BUY, SELL, CREDIT_BUY, CREDIT_SELL, MARGIN, DIVIDEND }`. `StockTrade.create(accountId, userId, tradeType, stockName, symbol, quantity, price, totalAmount, fee=ZERO, tax=ZERO, tradedAt, memo)`.
- `StockTradeRepository`(`application/port/StockTradeRepository.kt`): `save`, `findByAccountId(accountId): List<StockTrade>`, `findById`, `delete`.
- `HoldingsReportGenerator`(`application/usecase/HoldingsReportGenerator.kt`): 생성자 `(assetRepository, accountRepository, fx)`. `generate(userId, period)`가 `accounts = accountRepository.findByUserId(userId)`, `valued`, `holdings`(map), `byAccount`, `byType`, `cash`, `summary{totalValueKrw,holdingCount,accountCount,cashWeight,unrealizedPnlKrw}`, `note` 본문 생성. `pct()` 헬퍼. `asOf=period.end`.
- `ReportPeriod(start: LocalDate, end: LocalDate)`, `ReportPeriod.monthly(y,m)`.
- 테스트 관례(`HoldingsReportGeneratorTest`): `FakeAssetRepo`, `FakeAccountRepo`, `fx`(KRW=1:1, else ×1000), `asset(...)` 헬퍼, `private fun generator(assets, accounts) = HoldingsReportGenerator(FakeAssetRepo(assets), FakeAccountRepo(accounts), fx)`. `mapper.readTree(...generate(userId,period).bodyJson)` 후 `body["..."]` 단언.
- FE: `types/holdings-report.ts`(`HoldingsSummary`, `Holding`, `HoldingsReportBody`). 컴포넌트 `components/holdings-report/HoldingsSummary.tsx`(`Card` + `fmtKrw`/`pctColor`/`fmtPctScaled` from `@/lib/report-format`), `HoldingsGrid.tsx`(`<th>`/`<td>`, `pctColor(h.unrealizedPnl)`+`fmtKrw`, 빈행 `colSpan={9}`). `[id]/page.tsx`가 이들을 렌더.

**공통 규칙:** 경로 = 리포 루트. BE 테스트 `cd allfolio-backend && ./gradlew :unified-asset:test --tests '<FQCN>'`. FE `cd frontend/allfolio_app && npx tsc --noEmit`.

---

## File Structure

**Backend — unified-asset**
- (수정) `build.gradle.kts` — `implementation(project(":trade"))`
- (신규) `application/usecase/FifoRealizedPnlCalculator.kt`
- (수정) `application/usecase/HoldingsReportGenerator.kt`
- (test 신규) `application/usecase/FifoRealizedPnlCalculatorTest.kt`
- (test 수정) `application/usecase/HoldingsReportGeneratorTest.kt`

**Frontend**
- (수정) `types/holdings-report.ts`
- (수정) `components/holdings-report/HoldingsSummary.tsx`
- (수정) `components/holdings-report/HoldingsGrid.tsx`

---

## Task 1: trade 의존 + FifoRealizedPnlCalculator (TDD)

**Files:**
- Modify: `allfolio-backend/unified-asset/build.gradle.kts`
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/FifoRealizedPnlCalculator.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/FifoRealizedPnlCalculatorTest.kt`

- [ ] **Step 1: build.gradle에 trade 의존 추가**

Modify `allfolio-backend/unified-asset/build.gradle.kts` — dependencies 블록의 기존 `implementation(project(":report"))` 아래에 추가:
```kotlin
    implementation(project(":trade"))
```

- [ ] **Step 2: 실패하는 테스트 작성**

Create `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/FifoRealizedPnlCalculatorTest.kt`:
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

class FifoRealizedPnlCalculatorTest {

    private val acct = UUID.randomUUID()
    private val user = UUID.randomUUID()
    private val period = ReportPeriod.monthly(2026, 6) // 2026-06-01 ~ 2026-06-30

    private fun trade(type: StockTradeType, symbol: String?, qty: String, price: String, on: LocalDate, fee: String = "0") =
        StockTrade.create(
            accountId = acct, userId = user, tradeType = type, stockName = symbol ?: "?", symbol = symbol,
            quantity = BigDecimal(qty), price = BigDecimal(price),
            totalAmount = BigDecimal(qty).multiply(BigDecimal(price)), fee = BigDecimal(fee), tax = BigDecimal.ZERO,
            tradedAt = on, memo = null,
        )

    @Test
    fun `당월 매수 후 부분매도 실현손익`() {
        val r = FifoRealizedPnlCalculator.calculate(
            listOf(
                trade(StockTradeType.BUY, "AAA", "10", "100", LocalDate.of(2026, 6, 5)),
                trade(StockTradeType.SELL, "AAA", "4", "150", LocalDate.of(2026, 6, 20)),
            ),
            period,
        )
        assertThat(r["AAA"]).isEqualByComparingTo("200") // 4*(150-100)
    }

    @Test
    fun `이전월 매수 lot 원가가 당월 매도 실현손익에 반영된다 (경계)`() {
        val r = FifoRealizedPnlCalculator.calculate(
            listOf(
                trade(StockTradeType.BUY, "BBB", "10", "100", LocalDate.of(2026, 5, 10)),
                trade(StockTradeType.SELL, "BBB", "4", "150", LocalDate.of(2026, 6, 20)),
            ),
            period,
        )
        assertThat(r["BBB"]).isEqualByComparingTo("200") // 옛 lot 원가 100 사용, 당월분만
    }

    @Test
    fun `이전월 매도는 당월에 포함되지 않는다`() {
        val r = FifoRealizedPnlCalculator.calculate(
            listOf(
                trade(StockTradeType.BUY, "CCC", "10", "100", LocalDate.of(2026, 5, 1)),
                trade(StockTradeType.SELL, "CCC", "5", "150", LocalDate.of(2026, 5, 15)), // 5월 실현(제외)
                trade(StockTradeType.SELL, "CCC", "5", "200", LocalDate.of(2026, 6, 15)), // 6월 실현
            ),
            period,
        )
        assertThat(r["CCC"]).isEqualByComparingTo("500") // 5*(200-100), 5월분 5*(150-100) 제외
    }

    @Test
    fun `당월 전량매도 종목도 실현손익이 잡힌다`() {
        val r = FifoRealizedPnlCalculator.calculate(
            listOf(
                trade(StockTradeType.BUY, "DDD", "10", "100", LocalDate.of(2026, 6, 3)),
                trade(StockTradeType.SELL, "DDD", "10", "150", LocalDate.of(2026, 6, 25)),
            ),
            period,
        )
        assertThat(r["DDD"]).isEqualByComparingTo("500")
    }

    @Test
    fun `신용매수 매도도 BUY SELL로 매핑된다`() {
        val r = FifoRealizedPnlCalculator.calculate(
            listOf(
                trade(StockTradeType.CREDIT_BUY, "EEE", "10", "100", LocalDate.of(2026, 6, 5)),
                trade(StockTradeType.CREDIT_SELL, "EEE", "10", "120", LocalDate.of(2026, 6, 20)),
            ),
            period,
        )
        assertThat(r["EEE"]).isEqualByComparingTo("200")
    }

    @Test
    fun `배당 미수 심볼없음은 제외되고 매도없으면 0`() {
        val r = FifoRealizedPnlCalculator.calculate(
            listOf(
                trade(StockTradeType.DIVIDEND, "FFF", "0", "0", LocalDate.of(2026, 6, 5)),
                trade(StockTradeType.MARGIN, "FFF", "1", "100", LocalDate.of(2026, 6, 6)),
                trade(StockTradeType.BUY, "FFF", "10", "100", LocalDate.of(2026, 6, 7)), // 매수만
                trade(StockTradeType.BUY, null, "1", "1", LocalDate.of(2026, 6, 8)),      // symbol 없음 제외
            ),
            period,
        )
        assertThat(r["FFF"]).isEqualByComparingTo("0")
    }

    @Test
    fun `수수료는 실현손익에서 차감된다`() {
        val r = FifoRealizedPnlCalculator.calculate(
            listOf(
                trade(StockTradeType.BUY, "GGG", "10", "100", LocalDate.of(2026, 6, 5)),
                trade(StockTradeType.SELL, "GGG", "10", "150", LocalDate.of(2026, 6, 20), fee = "50"),
            ),
            period,
        )
        assertThat(r["GGG"]).isEqualByComparingTo("450") // 500 - 50
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests 'com.allfolio.unifiedasset.application.usecase.FifoRealizedPnlCalculatorTest' -q`
Expected: 컴파일 에러(`FifoRealizedPnlCalculator` 미존재). (build.gradle 의존 추가 후 trade 심볼은 해소됨.)

- [ ] **Step 4: 계산기 구현**

Create `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/FifoRealizedPnlCalculator.kt`:
```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.trade.domain.FifoCostEngine
import com.allfolio.trade.domain.LotPosition
import com.allfolio.trade.domain.TradeType
import com.allfolio.unifiedasset.domain.account.StockTrade
import com.allfolio.unifiedasset.domain.account.StockTradeType
import java.math.BigDecimal

/**
 * ua_stock_trades 기반 심볼별 당월 FIFO 실현손익(KRW) 계산 (순수).
 * 검증된 trade 모듈 FifoCostEngine.apply를 재사용한다.
 * period.start 직전 누적 실현손익을 스냅샷해 당월분(= 최종 − 직전)만 반환한다.
 * 통화 컬럼 부재 → KRW 취급. DIVIDEND/MARGIN·symbol 없음은 제외.
 */
object FifoRealizedPnlCalculator {
    private val BUY_TYPES = setOf(StockTradeType.BUY, StockTradeType.CREDIT_BUY)
    private val SELL_TYPES = setOf(StockTradeType.SELL, StockTradeType.CREDIT_SELL)

    fun calculate(trades: List<StockTrade>, period: ReportPeriod): Map<String, BigDecimal> =
        trades
            .filter { it.symbol != null && !it.tradedAt.isAfter(period.end) && (it.tradeType in BUY_TYPES || it.tradeType in SELL_TYPES) }
            .groupBy { it.symbol!! }
            .mapValues { (_, ts) -> monthRealized(ts, period) }

    private fun monthRealized(symbolTrades: List<StockTrade>, period: ReportPeriod): BigDecimal {
        val asc = symbolTrades.sortedWith(compareBy({ it.tradedAt }, { it.createdAt }))
        var pos = LotPosition.EMPTY
        var realizedBeforeStart = BigDecimal.ZERO
        var crossed = false
        for (t in asc) {
            if (!crossed && !t.tradedAt.isBefore(period.start)) {
                realizedBeforeStart = pos.realizedPnl
                crossed = true
            }
            val tt = if (t.tradeType in BUY_TYPES) TradeType.BUY else TradeType.SELL
            pos = FifoCostEngine.apply(pos, tt, t.quantity, t.price, t.fee)
        }
        if (!crossed) realizedBeforeStart = pos.realizedPnl // 전부 기간 이전 → 당월 0
        return pos.realizedPnl - realizedBeforeStart
    }
}
```

- [ ] **Step 5: 통과 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests 'com.allfolio.unifiedasset.application.usecase.FifoRealizedPnlCalculatorTest' -q`
Expected: PASS (7 tests).

- [ ] **Step 6: Commit**

```bash
git add allfolio-backend/unified-asset/build.gradle.kts \
        allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/FifoRealizedPnlCalculator.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/FifoRealizedPnlCalculatorTest.kt
git commit -m "feat(holdings): add FIFO realized-pnl calculator reusing trade FifoCostEngine"
```

---

## Task 2: HoldingsReportGenerator 통합 (TDD)

**Files:**
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/HoldingsReportGenerator.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/HoldingsReportGeneratorTest.kt`

- [ ] **Step 1: 테스트 확장(헬퍼 + 신규 실패 테스트)**

Modify `HoldingsReportGeneratorTest.kt`:
1. import 추가:
```kotlin
import com.allfolio.unifiedasset.application.port.StockTradeRepository
import com.allfolio.unifiedasset.domain.account.StockTrade
import com.allfolio.unifiedasset.domain.account.StockTradeType
```
2. 클래스 안에 fake 거래 repo + 헬퍼 추가:
```kotlin
    private class FakeStockTradeRepo(private val trades: List<StockTrade>) : StockTradeRepository {
        override fun save(trade: StockTrade) = trade
        override fun findByAccountId(accountId: UUID) = trades.filter { it.accountId == accountId }
        override fun findById(id: UUID): StockTrade? = null
        override fun delete(id: UUID) {}
    }

    private fun stockTrade(accountId: UUID, type: StockTradeType, symbol: String, qty: String, price: String, on: LocalDate) =
        StockTrade.create(
            accountId = accountId, userId = userId, tradeType = type, stockName = symbol, symbol = symbol,
            quantity = BigDecimal(qty), price = BigDecimal(price),
            totalAmount = BigDecimal(qty).multiply(BigDecimal(price)), tradedAt = on, memo = null,
        )
```
3. 기존 `generator(assets, accounts)` 헬퍼를 아래로 교체(기본 빈 거래 repo로 기존 테스트 보존):
```kotlin
    private fun generator(assets: List<Asset>, accounts: List<Account>, trades: List<StockTrade> = emptyList()) =
        HoldingsReportGenerator(FakeAssetRepo(assets), FakeAccountRepo(accounts), fx, FakeStockTradeRepo(trades))
```
4. 신규 테스트 추가(클래스 끝 `}` 앞). `standardAssets()`/`standardAccounts()`의 계좌 id와 보유 심볼은 기존 테스트에서 쓰는 것을 재사용하되, 실현손익은 별도 심볼로 검증(현재보유와 무관하게 거래만으로):
```kotlin
    @Test
    fun `당월 실현손익이 요약과 holdings와 realized 섹션에 반영된다`() {
        // 현재 보유(standard) + 당월 매수/부분매도 거래 1건(심볼 ZZZ)
        val trades = listOf(
            stockTrade(acctA, StockTradeType.BUY, "ZZZ", "10", "100", LocalDate.of(2026, 6, 5)),
            stockTrade(acctA, StockTradeType.SELL, "ZZZ", "4", "150", LocalDate.of(2026, 6, 20)),
        )
        val body = mapper.readTree(
            generator(standardAssets(), standardAccounts(), trades).generate(userId, period).bodyJson,
        )
        assertEquals(200.0, body["summary"]["realizedPnlKrw"].asDouble(), 0.01)
        val realized = body["realized"]
        assertEquals(1, realized.size())
        assertEquals("ZZZ", realized[0]["symbol"].asText())
        assertEquals(200.0, realized[0]["realizedPnl"].asDouble(), 0.01)
    }

    @Test
    fun `거래 없으면 실현손익 0이고 realized 섹션 비어있다`() {
        val body = mapper.readTree(
            generator(standardAssets(), standardAccounts()).generate(userId, period).bodyJson,
        )
        assertEquals(0.0, body["summary"]["realizedPnlKrw"].asDouble(), 0.01)
        assertEquals(0, body["realized"].size())
        // 기존 holdings에도 realizedPnl 필드가 존재(0)
        assertEquals(0.0, body["holdings"][0]["realizedPnl"].asDouble(), 0.01)
    }
```
> 참고: `acctA`는 기존 테스트 필드(계좌 A id). `standardAccounts()`가 `acctA`를 포함하는지 확인하고, 아니면 거래의 accountId를 `standardAccounts().first().id`로 맞춘다.

- [ ] **Step 2: 실패 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests 'com.allfolio.unifiedasset.application.usecase.HoldingsReportGeneratorTest' -q`
Expected: 컴파일 에러(생성자 4-arg 미존재).

- [ ] **Step 3: 생성기 수정**

Modify `HoldingsReportGenerator.kt`:
1. import 추가:
```kotlin
import com.allfolio.unifiedasset.application.port.StockTradeRepository
```
2. 생성자에 파라미터 추가:
```kotlin
@Component
class HoldingsReportGenerator(
    private val assetRepository: AssetRepository,
    private val accountRepository: AccountRepository,
    private val fx: FxConverter,
    private val stockTradeRepository: StockTradeRepository,
) : ReportBodyGenerator {
```
3. `generate` 안, `val labels = ...` 다음(또는 valued 계산 부근)에 실현손익 계산 추가:
```kotlin
        val trades = accounts.flatMap { stockTradeRepository.findByAccountId(it.id) }
        val realizedBySymbol = FifoRealizedPnlCalculator.calculate(trades, period)
        val realizedTotal = realizedBySymbol.values.fold(BigDecimal.ZERO) { a, b -> a + b }
        val nameBySymbol = trades.filter { it.symbol != null }
            .groupBy { it.symbol!! }
            .mapValues { (_, ts) -> ts.maxByOrNull { it.tradedAt }!!.stockName }
```
4. `holdings` map에 realizedPnl 추가 — 기존 map의 `"unrealizedPnl" to ...` 줄 옆(같은 mapOf 안)에:
```kotlin
                "realizedPnl" to (a.symbol?.let { realizedBySymbol[it] } ?: BigDecimal.ZERO),
```
5. `summary` mapOf에 추가 — `"unrealizedPnlKrw" to unrealizedTotal,` 다음:
```kotlin
                "realizedPnlKrw" to realizedTotal,
```
6. body mapOf에 realized 섹션 추가 — `"cash" to cash,` 다음:
```kotlin
            "realized" to realizedBySymbol.filterValues { it.signum() != 0 }
                .map { (sym, pnl) -> mapOf("symbol" to sym, "name" to (nameBySymbol[sym] ?: sym), "realizedPnl" to pnl) }
                .sortedByDescending { it["realizedPnl"] as BigDecimal },
```
7. `note` 문자열 갱신:
```kotlin
            "note" to "보유·평가액은 보고서 생성 시점 기준 · 당월 실현손익은 수동 입력 거래(ua_stock_trades) 기준",
```

- [ ] **Step 4: 통과 확인(신규 + 기존)**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests 'com.allfolio.unifiedasset.application.usecase.HoldingsReportGeneratorTest' -q`
Expected: PASS (기존 + 신규 2). 기존 테스트는 빈 거래 repo로 realizedPnlKrw=0·realized=[]·holdings[].realizedPnl=0.

- [ ] **Step 5: Commit**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/HoldingsReportGenerator.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/HoldingsReportGeneratorTest.kt
git commit -m "feat(holdings): integrate monthly FIFO realized-pnl into holdings report body"
```

---

## Task 3: 백엔드 전체 회귀

**Files:** (없음 — 검증)

- [ ] **Step 1: 전체 테스트**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test :backend-app:test -q`
Expected: BUILD SUCCESSFUL. 신규 `FifoRealizedPnlCalculatorTest`(7) + 확장 `HoldingsReportGeneratorTest`, 기존 회귀 없음. (trade 의존 추가가 다른 모듈에 영향 없음 확인.)

- [ ] **Step 2: 실패 시 진단 후 수정 → 재실행. Commit(수정 시)**

```bash
git add -A && git commit -m "test(holdings): fix regressions"
```

---

## Task 4: FE — 타입 + 요약 카드 + 그리드 컬럼

**Files:**
- Modify: `frontend/allfolio_app/types/holdings-report.ts`
- Modify: `frontend/allfolio_app/components/holdings-report/HoldingsSummary.tsx`
- Modify: `frontend/allfolio_app/components/holdings-report/HoldingsGrid.tsx`

- [ ] **Step 1: 타입 확장**

Modify `types/holdings-report.ts`:
1. `HoldingsSummary`에 추가:
```ts
  realizedPnlKrw: number     // 당월 FIFO 실현손익, 부호 있는 KRW
```
2. `Holding`에 추가:
```ts
  realizedPnl: number        // 당월 실현손익 KRW, 부호
```
3. 신규 인터페이스 + body 필드:
```ts
export interface HoldingRealized {
  symbol: string
  name: string
  realizedPnl: number
}
```
`HoldingsReportBody`에 추가:
```ts
  realized: HoldingRealized[]
```

- [ ] **Step 2: 요약 카드 추가**

Modify `components/holdings-report/HoldingsSummary.tsx` — 평가손익 Card 다음에 추가:
```tsx
        <Card label="당월 실현손익" value={fmtKrw(summary.realizedPnlKrw)} color={pctColor(summary.realizedPnlKrw)} />
```
(그리드가 4열이라 5번째 카드는 자연스럽게 다음 줄로 래핑됨. `fmtKrw`/`pctColor`는 이미 import됨.)

- [ ] **Step 3: 그리드 컬럼 추가**

Modify `components/holdings-report/HoldingsGrid.tsx`:
1. 헤더 `<th ...>수익률</th>` 다음에 추가:
```tsx
              <th className="p-3 text-right">당월 실현손익</th>
```
2. 각 행의 수익률 `<td>`(마지막, `fmtPctScaled(h.returnRate)`) 다음에 추가:
```tsx
                <td className={`p-3 text-right tabular-nums ${pctColor(h.realizedPnl)}`}>{fmtKrw(h.realizedPnl)}</td>
```
3. 빈행 `colSpan={9}` → `colSpan={10}`.

- [ ] **Step 4: 타입 체크**

Run: `cd frontend/allfolio_app && npx tsc --noEmit`
Expected: 에러 없음.

- [ ] **Step 5: Commit**

```bash
git add frontend/allfolio_app/types/holdings-report.ts \
        frontend/allfolio_app/components/holdings-report/HoldingsSummary.tsx \
        frontend/allfolio_app/components/holdings-report/HoldingsGrid.tsx
git commit -m "feat(holdings): show monthly realized-pnl in summary card and holdings grid"
```

---

## Task 5: 통합 검증

**Files:** (없음 — 검증)

- [ ] **Step 1: 백엔드 빌드 + FE 타입 최종 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test :backend-app:test -q` → BUILD SUCCESSFUL
Run: `cd frontend/allfolio_app && npx tsc --noEmit` → 에러 없음

- [ ] **Step 2: 커버리지 요약 보고**

계산기(FIFO 경계·매핑·수수료·제외 7케이스) + 생성기(요약·holdings·realized 섹션) + 기존 회귀가 로직을 검증. 스키마 변경 없어 DB 검증 불필요. 결과 요약 보고.

- [ ] **Step 3: (커밋 불필요)**

---

## Rollout (배포 시 — 사용자 실행)
- **스키마 변경 없음** → 운영 마이그레이션 불필요.
- main 병합 → Render 자동배포(BE) → FE 배포.
- 검증: 수동 STOCK 계좌에 당월 매수·매도 거래가 있는 계정 → HOLDINGS 리포트 생성 → 요약 "당월 실현손익"·그리드 컬럼·realized 반영 확인. 브로커 동기화 계좌만 있는 계정 → 실현손익 0(정상).

---

## Notes / 주의
- `unified-asset → trade` 의존 추가(순환 없음: trade는 common만 의존). 사용 심볼(`FifoCostEngine`/`LotPosition`/`TradeType`)은 순수 도메인.
- 당월 경계: period.start 직전 누적 realized 스냅샷 diff → 이전월 매수 lot 원가가 당월 매도에 정확 반영, 이전월 실현분은 제외.
- 커버리지: ua_stock_trades BUY/SELL 있는 심볼만(수동 STOCK). 브로커 동기화 → 0. note 명시.
- KRW 취급(ua_stock_trades 통화 컬럼 부재) — R-03/R-04 관례 동일.
- realized 섹션은 당월 realized≠0 심볼만(전량매도 종목 포함) → 요약 합계와 정합.
- 범위 밖(후속): 월간 변동 diff·지역 그룹핑·Excel/ISIN·통화 정규화.
