# R-07 위반 이력 (SCR-RPT-10) 후속 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ESG 스크리닝 보고서에 위반 이력 타임라인(편입/청산/리스트등록)과 편입일/등록전후 배지를 추가한다 — ua_stock_trades 수량추적 + 배제리스트 등록일 기반.

**Architecture:** 신규 순수 `ViolationHistoryCalculator`가 배제소스 심볼별로 사용자 거래(계좌 순회)를 수량추적해 편입/청산 이벤트·firstBuyDate를 산출하고, 유저리스트 added_at으로 리스트등록 이벤트·등록전후 배지를 만든다. `EsgScreeningReportGenerator`가 이를 violations 그리드와 신규 `violationHistory` 섹션에 통합. FE는 편입일 컬럼 + 타임라인 섹션. **스키마 변경 없음.**

**Tech Stack:** Kotlin, Spring Boot 6, JUnit5, Next.js/React/TS.

**Spec:** `docs/superpowers/specs/2026-07-30-esg-violation-history-design.md`
**Branch:** `feat/esg-violation-history` (main에서 분기, #52 배제리스트 포함)

---

## Reference: 현재 상태 & 관례

- `EsgScreeningReportGenerator`(`application/usecase/EsgScreeningReportGenerator.kt`): 생성자 `(assetRepository, fx, exclusionRepo: ExclusionListRepository)`. `generate`에서 `lookup = LinkedHashMap<String, Pair<listName, reason>>`(프리셋 `EsgExclusionPreset.entries` ∪ `exclusionRepo.findActiveByUser(userId)` items), `violated`(assets ∩ lookup), `violations`(list of mapOf name/symbol/listName/reason/valueKrw/weight), body `mapOf("esg", "esgBreakdown", "screening", "violations", "note")`. `emptyReport()`도 있음. `private data class Quad(asset, value, listName, reason)`.
- `StockTradeRepository.findByAccountId(accountId): List<StockTrade>`. `StockTrade`: `symbol: String?`(uppercased), `tradeType: StockTradeType`, `quantity: BigDecimal`, `tradedAt: LocalDate`, `createdAt: LocalDateTime`, `stockName`. `StockTradeType { BUY, SELL, CREDIT_BUY, CREDIT_SELL, MARGIN, DIVIDEND }`. `StockTrade.create(accountId, userId, tradeType, stockName, symbol, quantity, price, totalAmount, fee=ZERO, tax=ZERO, tradedAt, memo)`.
- `AccountRepository.findByUserId(userId): List<Account>`. `Account.reconstruct(id, userId, provider, accountType, accountName, externalId, currency, status, lastSyncedAt, createdAt, apiKey, apiSecret, walletAddress, chain)`.
- `ExclusionList(id, userId, name, category, description, active, createdAt, updatedAt, items)`, `ExclusionItem(id, listId, symbol, memo, addedAt: LocalDateTime)`. `ExclusionListRepository.findActiveByUser`.
- `ReportPeriod(start, end)`. `EsgExclusionPreset.entries: Map<String, ExclusionEntry(listName, reason)>`.
- 테스트(`EsgScreeningReportGeneratorTest`): `FakeAssetRepo`, `fx`(KRW=1:1, else ×1000), `asset(name, symbol, current, currency=KRW, type=STOCK)`, `FakeExclusionRepo(lists)`, `userList(active, owner, vararg symbols)`(items의 addedAt=now), `generator(assets, exclusion=FakeExclusionRepo(emptyList())) = EsgScreeningReportGenerator(FakeAssetRepo(assets), fx, exclusion)`. `mapper.readTree(...generate(userId, period).bodyJson)`. `userId`, `acctId`, `period=monthly(2026,6)` 필드.
- FE: `types/esg-screening.ts`(`EsgViolation`, `EsgScreeningReportBody`). 컴포넌트 `components/esg-screening/{EsgSummary,EsgScoreBars,EsgBreakdownTable,ViolationsTable}.tsx`. `ViolationsTable`은 name/symbol/listName/reason/valueKrw/weight 렌더, `fmtKrw` 사용. `[id]/page.tsx`가 렌더. 포맷 `@/lib/report-format`.

**공통 규칙:** BE 테스트 `cd allfolio-backend && ./gradlew :unified-asset:test --tests '<FQCN>'`. FE `cd frontend/allfolio_app && npx tsc --noEmit`.

---

## File Structure

**Backend — unified-asset**
- (신규) `application/usecase/ViolationHistoryCalculator.kt`
- (수정) `application/usecase/EsgScreeningReportGenerator.kt`
- (test 신규) `application/usecase/ViolationHistoryCalculatorTest.kt`
- (test 수정) `application/usecase/EsgScreeningReportGeneratorTest.kt`

**Frontend**
- (수정) `types/esg-screening.ts`
- (수정) `components/esg-screening/ViolationsTable.tsx`
- (신규) `components/esg-screening/ViolationHistory.tsx`
- (수정) `app/unified/reports/esg-screening/[id]/page.tsx`

---

## Task 1: ViolationHistoryCalculator (TDD)

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/ViolationHistoryCalculator.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/ViolationHistoryCalculatorTest.kt`

- [ ] **Step 1: 실패하는 테스트 작성**

Create `ViolationHistoryCalculatorTest.kt`:
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

class ViolationHistoryCalculatorTest {

    private val acct = UUID.randomUUID()
    private val user = UUID.randomUUID()
    private val period = ReportPeriod.monthly(2026, 6)

    private fun t(type: StockTradeType, symbol: String, qty: String, on: LocalDate) =
        StockTrade.create(
            accountId = acct, userId = user, tradeType = type, stockName = symbol, symbol = symbol,
            quantity = BigDecimal(qty), price = BigDecimal.ONE, totalAmount = BigDecimal(qty),
            tradedAt = on, memo = null,
        )

    @Test
    fun `첫 매수는 편입 이벤트와 firstBuyDate를 만든다`() {
        val h = ViolationHistoryCalculator.build(
            setOf("AAA"), listOf(t(StockTradeType.BUY, "AAA", "10", LocalDate.of(2026, 6, 5))),
            emptyMap(), mapOf("AAA" to "종목A"), period,
        )
        assertThat(h.perSymbol["AAA"]!!.firstBuyDate).isEqualTo(LocalDate.of(2026, 6, 5))
        assertThat(h.events).anySatisfy { assertThat(it.event).isEqualTo("편입"); assertThat(it.symbol).isEqualTo("AAA") }
    }

    @Test
    fun `전량 매도는 청산 이벤트를 만든다`() {
        val h = ViolationHistoryCalculator.build(
            setOf("BBB"),
            listOf(t(StockTradeType.BUY, "BBB", "10", LocalDate.of(2026, 6, 3)), t(StockTradeType.SELL, "BBB", "10", LocalDate.of(2026, 6, 20))),
            emptyMap(), mapOf("BBB" to "종목B"), period,
        )
        assertThat(h.events.map { it.event }).containsExactly("편입", "청산")
    }

    @Test
    fun `매수 전량매도 재매수는 편입 청산 편입 3이벤트`() {
        val h = ViolationHistoryCalculator.build(
            setOf("CCC"),
            listOf(
                t(StockTradeType.BUY, "CCC", "5", LocalDate.of(2026, 6, 1)),
                t(StockTradeType.SELL, "CCC", "5", LocalDate.of(2026, 6, 10)),
                t(StockTradeType.BUY, "CCC", "3", LocalDate.of(2026, 6, 20)),
            ),
            emptyMap(), mapOf("CCC" to "종목C"), period,
        )
        assertThat(h.events.map { it.event }).containsExactly("편입", "청산", "편입")
        assertThat(h.perSymbol["CCC"]!!.firstBuyDate).isEqualTo(LocalDate.of(2026, 6, 1))
    }

    @Test
    fun `리스트 등록일이 있으면 리스트등록 이벤트를 만든다`() {
        val h = ViolationHistoryCalculator.build(
            setOf("DDD"), listOf(t(StockTradeType.BUY, "DDD", "10", LocalDate.of(2026, 6, 5))),
            mapOf("DDD" to LocalDate.of(2026, 6, 8)), mapOf("DDD" to "종목D"), period,
        )
        assertThat(h.events).anySatisfy { assertThat(it.event).isEqualTo("리스트등록") }
    }

    @Test
    fun `등록전 보유와 등록후 매수 배지`() {
        // firstBuy 6-01 < listedAt 6-10 → 등록전보유
        val before = ViolationHistoryCalculator.build(
            setOf("E1"), listOf(t(StockTradeType.BUY, "E1", "10", LocalDate.of(2026, 6, 1))),
            mapOf("E1" to LocalDate.of(2026, 6, 10)), mapOf("E1" to "E1"), period,
        )
        assertThat(before.perSymbol["E1"]!!.sinceListed).isEqualTo("등록전보유")
        // firstBuy 6-20 >= listedAt 6-10 → 등록후매수
        val after = ViolationHistoryCalculator.build(
            setOf("E2"), listOf(t(StockTradeType.BUY, "E2", "10", LocalDate.of(2026, 6, 20))),
            mapOf("E2" to LocalDate.of(2026, 6, 10)), mapOf("E2" to "E2"), period,
        )
        assertThat(after.perSymbol["E2"]!!.sinceListed).isEqualTo("등록후매수")
    }

    @Test
    fun `프리셋 소스는 프리셋 배지이고 거래없는 심볼은 제외된다`() {
        val h = ViolationHistoryCalculator.build(
            setOf("P1", "NOHOLD"), listOf(t(StockTradeType.BUY, "P1", "10", LocalDate.of(2026, 6, 5))),
            emptyMap(), mapOf("P1" to "P1"), period,
        )
        assertThat(h.perSymbol["P1"]!!.sinceListed).isEqualTo("프리셋")
        assertThat(h.perSymbol).doesNotContainKey("NOHOLD") // 거래·등록일 없음 → 제외
    }

    @Test
    fun `배당 미수는 무시되고 기간 이후 거래는 제외된다`() {
        val h = ViolationHistoryCalculator.build(
            setOf("FFF"),
            listOf(
                t(StockTradeType.DIVIDEND, "FFF", "0", LocalDate.of(2026, 6, 5)),
                t(StockTradeType.BUY, "FFF", "10", LocalDate.of(2026, 6, 6)),
                t(StockTradeType.SELL, "FFF", "10", LocalDate.of(2026, 7, 1)), // 기간 이후
            ),
            emptyMap(), mapOf("FFF" to "F"), period,
        )
        assertThat(h.events.map { it.event }).containsExactly("편입") // 청산(7월) 제외, 배당 무시
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests 'com.allfolio.unifiedasset.application.usecase.ViolationHistoryCalculatorTest' -q`
Expected: 컴파일 에러(`ViolationHistoryCalculator` 미존재).

- [ ] **Step 3: 계산기 구현**

Create `ViolationHistoryCalculator.kt`:
```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.unifiedasset.domain.account.StockTrade
import com.allfolio.unifiedasset.domain.account.StockTradeType
import java.math.BigDecimal
import java.time.LocalDate

data class SymbolViolationInfo(val firstBuyDate: LocalDate?, val sinceListed: String)
data class ViolationEvent(val date: LocalDate, val symbol: String, val name: String, val event: String, val note: String)
data class ViolationHistory(val perSymbol: Map<String, SymbolViolationInfo>, val events: List<ViolationEvent>)

/**
 * 배제소스 심볼별 위반 이력 산출 (순수).
 * 편입(qty 0→+)·청산(qty +→0)은 ua_stock_trades 수량추적, 리스트등록은 유저리스트 added_at.
 * 거래·등록일이 전혀 없는 심볼은 제외. period.end 이후 거래 제외. DIVIDEND/MARGIN 무시.
 */
object ViolationHistoryCalculator {
    private val BUY = setOf(StockTradeType.BUY, StockTradeType.CREDIT_BUY)
    private val SELL = setOf(StockTradeType.SELL, StockTradeType.CREDIT_SELL)

    fun build(
        sourceSymbols: Set<String>,
        trades: List<StockTrade>,
        listedAtBySymbol: Map<String, LocalDate>,
        nameBySymbol: Map<String, String>,
        period: ReportPeriod,
    ): ViolationHistory {
        val events = mutableListOf<ViolationEvent>()
        val perSymbol = mutableMapOf<String, SymbolViolationInfo>()

        for (sym in sourceSymbols) {
            val symTrades = trades
                .filter { it.symbol == sym && !it.tradedAt.isAfter(period.end) && (it.tradeType in BUY || it.tradeType in SELL) }
                .sortedWith(compareBy({ it.tradedAt }, { it.createdAt }))
            val listedAt = listedAtBySymbol[sym]
            if (symTrades.isEmpty() && listedAt == null) continue // 거래·등록일 없음 → 제외
            val name = nameBySymbol[sym] ?: sym

            var qty = BigDecimal.ZERO
            var firstBuy: LocalDate? = null
            for (t in symTrades) {
                val before = qty
                qty = if (t.tradeType in BUY) qty + t.quantity else qty - t.quantity
                if (before.signum() <= 0 && qty.signum() > 0) {
                    if (firstBuy == null) firstBuy = t.tradedAt
                    events += ViolationEvent(t.tradedAt, sym, name, "편입", "신규 매수")
                }
                if (before.signum() > 0 && qty.signum() <= 0) {
                    events += ViolationEvent(t.tradedAt, sym, name, "청산", "전량 매도")
                }
            }
            if (listedAt != null && !listedAt.isAfter(period.end)) {
                events += ViolationEvent(listedAt, sym, name, "리스트등록", "배제리스트 추가")
            }

            val sinceListed = when {
                listedAt == null -> "프리셋"
                firstBuy == null -> "-"
                firstBuy.isBefore(listedAt) -> "등록전보유"
                else -> "등록후매수"
            }
            perSymbol[sym] = SymbolViolationInfo(firstBuy, sinceListed)
        }

        return ViolationHistory(perSymbol, events.sortedWith(compareBy({ it.date }, { it.symbol }, { it.event })))
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests 'com.allfolio.unifiedasset.application.usecase.ViolationHistoryCalculatorTest' -q`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/ViolationHistoryCalculator.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/ViolationHistoryCalculatorTest.kt
git commit -m "feat(esg): add ViolationHistoryCalculator (편입/청산/리스트등록 events)"
```

---

## Task 2: 생성기 통합 (TDD)

**Files:**
- Modify: `application/usecase/EsgScreeningReportGenerator.kt`
- Test: `application/usecase/EsgScreeningReportGeneratorTest.kt`

- [ ] **Step 1: 테스트 확장(헬퍼 + 신규 실패 테스트)**

Modify `EsgScreeningReportGeneratorTest.kt`:
1. import 추가:
```kotlin
import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.StockTradeRepository
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountStatus
import com.allfolio.unifiedasset.domain.account.AccountType
import com.allfolio.unifiedasset.domain.account.StockTrade
import com.allfolio.unifiedasset.domain.account.StockTradeType
import java.time.LocalDate
```
2. 클래스 안에 fake account/stockTrade repo + 헬퍼 추가:
```kotlin
    private class FakeAccountRepo(private val accounts: List<Account>) : AccountRepository {
        override fun save(account: Account) = account
        override fun findById(id: UUID): Account? = null
        override fun findByUserId(userId: UUID) = accounts
        override fun findByProviders(providers: Collection<AccountProvider>) = emptyList<Account>()
        override fun delete(id: UUID) {}
        override fun updateStatus(id: UUID, status: AccountStatus) {}
    }
    private class FakeStockTradeRepo(private val trades: List<StockTrade>) : StockTradeRepository {
        override fun save(trade: StockTrade) = trade
        override fun findByAccountId(accountId: UUID) = trades.filter { it.accountId == accountId }
        override fun findById(id: UUID): StockTrade? = null
        override fun delete(id: UUID) {}
    }
    private fun account() = Account.reconstruct(
        id = acctId, userId = userId, provider = AccountProvider.KIS, accountType = AccountType.STOCK,
        accountName = "한투", externalId = null, currency = "KRW", status = AccountStatus.ACTIVE,
        lastSyncedAt = null, createdAt = LocalDateTime.now(), apiKey = null, apiSecret = null,
        walletAddress = null, chain = null,
    )
    private fun stockTrade(type: StockTradeType, symbol: String, qty: String, on: LocalDate) =
        StockTrade.create(
            accountId = acctId, userId = userId, tradeType = type, stockName = symbol, symbol = symbol,
            quantity = BigDecimal(qty), price = BigDecimal.ONE, totalAmount = BigDecimal(qty), tradedAt = on, memo = null,
        )
```
3. 기존 `generator(assets, exclusion)` 헬퍼를 아래로 교체(account/trades 기본 빈 → 기존 테스트 보존):
```kotlin
    private fun generator(
        assets: List<Asset>,
        exclusion: ExclusionListRepository = FakeExclusionRepo(emptyList()),
        accounts: List<Account> = emptyList(),
        trades: List<StockTrade> = emptyList(),
    ) = EsgScreeningReportGenerator(FakeAssetRepo(assets), fx, exclusion, FakeAccountRepo(accounts), FakeStockTradeRepo(trades))
```
4. 신규 테스트 추가(클래스 끝 `}` 앞). `asset(name, symbol, current)`는 symbol=대문자 저장:
```kotlin
    @Test
    fun `위반 종목에 편입일과 배지가 붙고 위반이력 섹션이 생긴다`() {
        val assets = listOf(asset("종목Z", "ZZZ", "1000000"))
        val list = userList(active = true, owner = userId, "ZZZ")   // 유저 리스트에 ZZZ
        val trades = listOf(stockTrade(StockTradeType.BUY, "ZZZ", "10", LocalDate.of(2026, 6, 5)))
        val body = mapper.readTree(
            generator(assets, FakeExclusionRepo(listOf(list)), listOf(account()), trades).generate(userId, period).bodyJson,
        )
        val v = body["violations"].first { it["symbol"].asText() == "ZZZ" }
        assertEquals("2026-06-05", v["firstBuyDate"].asText())
        assertTrue(v["sinceListed"].asText().isNotEmpty())
        val hist = body["violationHistory"]
        assertTrue(hist.any { it["symbol"].asText() == "ZZZ" && it["event"].asText() == "편입" })
    }

    @Test
    fun `거래가 없으면 위반이력은 비어있다`() {
        val assets = listOf(asset("종목Z", "ZZZ", "1000000"))
        val list = userList(active = true, owner = userId, "ZZZ")
        val body = mapper.readTree(
            generator(assets, FakeExclusionRepo(listOf(list))).generate(userId, period).bodyJson,
        )
        // 유저리스트 등록일(now)만 있고 거래 없음 → 리스트등록 이벤트는 있을 수 있으나 편입/청산 없음
        assertTrue(body.has("violationHistory"))
    }
```
> 참고: `userList`의 addedAt=now(생성 시점, 2026-07 이후)라 period.end(2026-06-30) 이후 → 리스트등록 이벤트는 period.end 필터로 제외될 수 있음. 두 번째 테스트는 `violationHistory` 키 존재만 확인(빈 배열 허용).

- [ ] **Step 2: 실패 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests 'com.allfolio.unifiedasset.application.usecase.EsgScreeningReportGeneratorTest' -q`
Expected: 컴파일 에러(생성자 5-arg 미존재).

- [ ] **Step 3: 생성기 수정**

Modify `EsgScreeningReportGenerator.kt`:
1. import 추가:
```kotlin
import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.StockTradeRepository
```
2. 생성자에 파라미터 추가(마지막):
```kotlin
    private val accountRepository: AccountRepository,
    private val stockTradeRepository: StockTradeRepository,
```
3. `generate`의 `if (totalKrw <= ZERO) emptyReport() else { ... }` 블록 안, `lookup` 구성 + `violated`/`violations` 계산 이후, body mapOf 직전에 위반이력 계산 추가:
```kotlin
            val trades = accountRepository.findByUserId(userId).flatMap { stockTradeRepository.findByAccountId(it.id) }
            val listedAtBySymbol = exclusionRepo.findActiveByUser(userId)
                .flatMap { it.items }.groupBy { it.symbol }
                .mapValues { (_, items) -> items.minOf { it.addedAt.toLocalDate() } }
            val nameBySymbol = (trades.mapNotNull { t -> t.symbol?.let { it to t.stockName } } +
                violated.mapNotNull { q -> q.asset.symbol?.let { it to q.asset.name } }).toMap()
            val history = ViolationHistoryCalculator.build(lookup.keys, trades, listedAtBySymbol, nameBySymbol, period)
```
4. `violations` 생성 부분에 firstBuyDate/sinceListed 추가 — 기존 `violations` map에 다음을 추가(각 항목):
```kotlin
            val violations = violated.map { q ->
                val info = q.asset.symbol?.let { history.perSymbol[it] }
                mapOf("name" to q.asset.name, "symbol" to q.asset.symbol, "listName" to q.listName,
                    "reason" to q.reason, "valueKrw" to q.value, "weight" to pct(q.value, totalKrw),
                    "firstBuyDate" to info?.firstBuyDate?.toString(), "sinceListed" to (info?.sinceListed ?: "-"))
            }
```
5. body mapOf에 violationHistory 추가(`"violations" to violations,` 다음):
```kotlin
                "violationHistory" to history.events.map {
                    mapOf("date" to it.date.toString(), "symbol" to it.symbol, "name" to it.name,
                        "event" to it.event, "note" to it.note)
                },
```
6. `emptyReport()`에도 `"violationHistory" to emptyList<Any>(),` 추가.
7. 클래스 KDoc "제외(후속): 위반 이력·감시로그·편입일..." → "제외(후속): 리스트 제외 이벤트·감시로그, 국가/ISIN 정밀 매칭"으로 갱신.

- [ ] **Step 4: 통과 확인(신규 + 기존)**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test --tests 'com.allfolio.unifiedasset.application.usecase.EsgScreeningReportGeneratorTest' -q`
Expected: PASS (기존 + 신규 2). 기존 테스트는 빈 account/trades로 violationHistory=[]·violations에 firstBuyDate=null/sinceListed="-" 추가만 되고 기존 단언 불변.

- [ ] **Step 5: Commit**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/EsgScreeningReportGenerator.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/EsgScreeningReportGeneratorTest.kt
git commit -m "feat(esg): integrate violation history + 편입일/배지 into screening report"
```

---

## Task 3: 백엔드 전체 회귀

**Files:** (없음 — 검증)

- [ ] **Step 1: 전체 테스트**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test :backend-app:test -q`
Expected: BUILD SUCCESSFUL. 신규 `ViolationHistoryCalculatorTest`(7) + 확장 `EsgScreeningReportGeneratorTest`, 기존 회귀 없음.

- [ ] **Step 2: 실패 시 진단 후 수정 → 재실행. Commit(수정 시)**

```bash
git add -A && git commit -m "test(esg): fix regressions"
```

---

## Task 4: FE — 타입 + 편입일 컬럼 + 위반이력 섹션

**Files:**
- Modify: `frontend/allfolio_app/types/esg-screening.ts`
- Modify: `frontend/allfolio_app/components/esg-screening/ViolationsTable.tsx`
- Create: `frontend/allfolio_app/components/esg-screening/ViolationHistory.tsx`
- Modify: `frontend/allfolio_app/app/unified/reports/esg-screening/[id]/page.tsx`

- [ ] **Step 1: 타입 확장**

Modify `types/esg-screening.ts`:
1. `EsgViolation`에 추가:
```ts
  firstBuyDate: string | null
  sinceListed: string
```
2. 신규 인터페이스 추가:
```ts
export interface EsgViolationEvent {
  date: string
  symbol: string
  name: string
  event: string
  note: string
}
```
3. `EsgScreeningReportBody`에 옵셔널 필드 추가(구 아카이브 호환):
```ts
  violationHistory?: EsgViolationEvent[]
```

- [ ] **Step 2: ViolationsTable에 편입일·배지 컬럼 추가**

Modify `components/esg-screening/ViolationsTable.tsx`:
1. 헤더 `<th className="p-3">사유</th>` 다음에 추가:
```tsx
                <th className="p-3">편입일</th>
```
2. 각 행의 사유 `<td>`(`{r.reason}` 셀) 다음에 추가:
```tsx
                  <td className="p-3 text-xs text-gray-400">
                    {r.firstBuyDate ?? '-'}
                    <span className="ml-1 rounded bg-gray-800 px-1.5 py-0.5 text-[10px] text-gray-400">{r.sinceListed}</span>
                  </td>
```

- [ ] **Step 3: 위반이력 타임라인 컴포넌트 생성**

Create `frontend/allfolio_app/components/esg-screening/ViolationHistory.tsx`:
```tsx
import type { EsgViolationEvent } from '@/types/esg-screening'

const EVENT_STYLE: Record<string, string> = {
  편입: 'bg-red-950 text-red-300',
  청산: 'bg-gray-800 text-gray-300',
  리스트등록: 'bg-amber-950 text-amber-300',
}

export function ViolationHistory({ events }: { events: EsgViolationEvent[] }) {
  return (
    <section className="space-y-3 break-inside-avoid">
      <h2 className="text-lg font-semibold">위반 이력</h2>
      {events.length === 0 ? (
        <div className="rounded-xl border border-gray-700 bg-gray-900 p-6 text-center text-sm text-gray-500">
          위반 이력 없음
        </div>
      ) : (
        <div className="overflow-x-auto rounded-xl border border-gray-800 bg-gray-900">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-800 text-left text-xs text-gray-500">
                <th className="p-3">일자</th><th className="p-3">종목</th><th className="p-3">이벤트</th><th className="p-3">비고</th>
              </tr>
            </thead>
            <tbody>
              {events.map((e, i) => (
                <tr key={`${e.symbol}-${e.date}-${e.event}-${i}`} className="border-b border-gray-800 last:border-b-0">
                  <td className="p-3 tabular-nums text-gray-400">{e.date}</td>
                  <td className="p-3">
                    <span className="text-gray-100">{e.name}</span>
                    <span className="ml-2 text-xs text-gray-500">{e.symbol}</span>
                  </td>
                  <td className="p-3">
                    <span className={`rounded px-2 py-0.5 text-xs ${EVENT_STYLE[e.event] ?? 'bg-gray-800 text-gray-300'}`}>{e.event}</span>
                  </td>
                  <td className="p-3 text-gray-500">{e.note}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
```

- [ ] **Step 4: 상세 페이지에 렌더**

Modify `app/unified/reports/esg-screening/[id]/page.tsx`:
1. import 추가(`ViolationsTable` import 옆):
```tsx
import { ViolationHistory } from '@/components/esg-screening/ViolationHistory'
```
2. `<ViolationsTable rows={body.violations} />` 다음 줄에 추가:
```tsx
      {body.violationHistory && <ViolationHistory events={body.violationHistory} />}
```

- [ ] **Step 5: 타입 체크**

Run: `cd frontend/allfolio_app && npx tsc --noEmit`
Expected: 에러 없음.

- [ ] **Step 6: Commit**

```bash
git add frontend/allfolio_app/types/esg-screening.ts \
        frontend/allfolio_app/components/esg-screening/ViolationsTable.tsx \
        frontend/allfolio_app/components/esg-screening/ViolationHistory.tsx \
        "frontend/allfolio_app/app/unified/reports/esg-screening/[id]/page.tsx"
git commit -m "feat(esg): show 편입일/배지 in violations grid and violation-history timeline"
```

---

## Task 5: 통합 검증

**Files:** (없음 — 검증)

- [ ] **Step 1: 백엔드 + FE 최종 확인**

Run: `cd allfolio-backend && ./gradlew :unified-asset:test :backend-app:test -q` → BUILD SUCCESSFUL
Run: `cd frontend/allfolio_app && npx tsc --noEmit` → 에러 없음

- [ ] **Step 2: 커버리지 요약 보고**

계산기(편입/청산/재편입/리스트등록/배지/제외 7케이스) + 생성기(편입일·배지·이력섹션) + 기존 회귀 검증. 스키마 변경 없어 DB 검증 불필요. 결과 요약 보고.

- [ ] **Step 3: (커밋 불필요)**

---

## Rollout (배포 시 — 사용자 실행)
- **스키마 변경 없음** → 운영 마이그레이션 불필요.
- main 병합 → Render 자동배포(BE) → FE 배포.
- 검증: 수동 STOCK 계좌 + 배제리스트에 보유 심볼 등록 → ESG 스크리닝 리포트 → violations 편입일/배지 + 위반 이력 타임라인(편입/청산/리스트등록) 확인.

---

## Notes / 주의
- 위반이력은 거래이력(ua_stock_trades) 있는 심볼만(수동 STOCK). 브로커 동기화 → firstBuyDate null·이벤트 없음(커버리지 한계, note).
- 편입=qty 0→+, 청산=qty +→0 수량추적(FIFO 불필요). period.end 이후 거래·DIVIDEND/MARGIN 제외.
- 리스트등록 이벤트는 유저리스트 added_at ≤ period.end일 때만(프리셋은 날짜 없음).
- violationHistory·firstBuyDate/sinceListed는 옵셔널/기본값 → 구 아카이브 리포트 호환.
- 범위 밖(후속): 리스트 제외 이벤트(감사이력 부재), 감시 알림 로그(알림 인프라), 국가/ISIN 정밀 매칭.
```
