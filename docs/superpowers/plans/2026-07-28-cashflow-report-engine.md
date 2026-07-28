# 현금흐름 보고서 생성 엔진 (R-06) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `CashflowReportGenerator`(type=CASHFLOW)를 #32 프레임에 등록 — `cash_flow`(입금/출금)와 `ua_stock_trades`(매수/매도/배당/수수료)를 유형별로 분류·집계한 현금흐름 본문 JSON을 생성해 아카이브한다.

**Architecture:** 헥사고날. 기존 `CashFlowRepository`·`AccountRepository` + 신규 `CashflowTradeSource` 포트/JDBC 어댑터. 순수 집계 생성기(fake 포트 테스트). 흐름·거래 0건은 예외가 아닌 유효한 0 보고서.

**Tech Stack:** Kotlin/Spring · JdbcTemplate 어댑터 · 기존 포트 재사용 · 신규 DDL 없음.

**Spec:** `docs/superpowers/specs/2026-07-28-cashflow-report-engine-design.md`

**테스트 명령:** `./gradlew :unified-asset:test --tests "*CashflowReportGeneratorTest*"` (전체: `./gradlew :unified-asset:test`)

---

## File Structure

- Create `unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/port/CashflowTradeSource.kt`
- Create `unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/CashflowReportGenerator.kt`
- Create `unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/CashflowReportGeneratorTest.kt`
- Create `unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/adapter/JdbcCashflowTradeSource.kt`

경로 접두사: `allfolio-backend/`

---

## Task 1: 포트 + TradeCashRecord

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/port/CashflowTradeSource.kt`

- [ ] **Step 1: 포트 작성**

```kotlin
package com.allfolio.unifiedasset.application.port

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** 현금흐름 분류용 주식 거래 1건 — 금액은 KRW 취급. */
data class TradeCashRecord(
    val tradeDate: LocalDate,
    val tradeType: String,
    val stockName: String,
    val accountName: String,
    val totalAmount: BigDecimal,
    val fee: BigDecimal,
    val tax: BigDecimal,
)

interface CashflowTradeSource {
    /** [from, to] 구간의 모든 주식 거래 (거래일 오름차순) */
    fun findTrades(userId: UUID, from: LocalDate, to: LocalDate): List<TradeCashRecord>
}
```

- [ ] **Step 2: 컴파일 확인 + 커밋**

Run: `./gradlew :unified-asset:compileKotlin` (Expected: BUILD SUCCESSFUL)
```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/port/CashflowTradeSource.kt
git commit -m "feat(cashflow): 현금흐름 거래 소스 포트 + TradeCashRecord (R2 #41)"
```

---

## Task 2: CashflowReportGenerator (TDD)

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/CashflowReportGenerator.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/CashflowReportGeneratorTest.kt`

- [ ] **Step 1 (RED): 테스트 작성**

```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.port.CashflowTradeSource
import com.allfolio.unifiedasset.application.port.TradeCashRecord
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountStatus
import com.allfolio.unifiedasset.domain.account.AccountType
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.domain.cashflow.FlowType
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class CashflowReportGeneratorTest {

    private val userId = UUID.randomUUID()
    private val acctId = UUID.randomUUID()
    private val period = ReportPeriod.monthly(2026, 6)
    private val mapper = jacksonObjectMapper()

    private class FakeCashFlowRepo(private val flows: List<CashFlow>) : CashFlowRepository {
        override fun save(cashFlow: CashFlow) = cashFlow
        override fun findById(id: UUID): CashFlow? = null
        override fun findByUserIdAndPeriod(userId: UUID, from: LocalDate, to: LocalDate) =
            flows.filter { it.flowDate in from..to }
        override fun findByUserId(userId: UUID) = flows
        override fun delete(id: UUID) {}
    }
    private class FakeTradeSource(private val trades: List<TradeCashRecord>) : CashflowTradeSource {
        override fun findTrades(userId: UUID, from: LocalDate, to: LocalDate) =
            trades.filter { it.tradeDate in from..to }.sortedBy { it.tradeDate }
    }
    private class FakeAccountRepo(private val accounts: List<Account>) : AccountRepository {
        override fun save(account: Account) = account
        override fun findById(id: UUID): Account? = null
        override fun findByUserId(userId: UUID) = accounts
        override fun findByProviders(providers: Collection<AccountProvider>) = emptyList<Account>()
        override fun delete(id: UUID) {}
        override fun updateStatus(id: UUID, status: AccountStatus) {}
    }

    private fun deposit(day: Int, krw: String) = CashFlow.create(
        userId = userId, accountId = acctId, flowDate = LocalDate.of(2026, 6, day),
        type = FlowType.DEPOSIT, amount = BigDecimal(krw), currency = "KRW", amountKrw = BigDecimal(krw), memo = "입금",
    )
    private fun withdrawal(day: Int, krw: String) = CashFlow.create(
        userId = userId, accountId = acctId, flowDate = LocalDate.of(2026, 6, day),
        type = FlowType.WITHDRAWAL, amount = BigDecimal(krw), currency = "KRW", amountKrw = BigDecimal(krw), memo = "출금",
    )
    private fun trade(day: Int, type: String, name: String, total: String, fee: String, tax: String) =
        TradeCashRecord(LocalDate.of(2026, 6, day), type, name, "한투", BigDecimal(total), BigDecimal(fee), BigDecimal(tax))
    private fun account() = Account.reconstruct(
        id = acctId, userId = userId, provider = AccountProvider.KIS, accountType = AccountType.STOCK,
        accountName = "한투", externalId = null, currency = "KRW", status = AccountStatus.ACTIVE,
        lastSyncedAt = null, createdAt = LocalDateTime.now(), apiKey = null, apiSecret = null,
        walletAddress = null, chain = null,
    )

    private fun generator(flows: List<CashFlow>, trades: List<TradeCashRecord>) =
        CashflowReportGenerator(FakeCashFlowRepo(flows), FakeTradeSource(trades), FakeAccountRepo(listOf(account())))

    // 입금 1,000,000 · 출금 300,000 · 매수 5,000,000(fee1500) · 매도 2,000,000(fee800,tax3000) · 배당 50,000(tax7700)
    private fun standardFlows() = listOf(deposit(1, "1000000"), withdrawal(20, "300000"))
    private fun standardTrades() = listOf(
        trade(5, "BUY", "삼성전자", "5000000", "1500", "0"),
        trade(25, "SELL", "삼성전자", "2000000", "800", "3000"),
        trade(10, "DIVIDEND", "삼성전자", "50000", "0", "7700"),
    )

    @Test
    fun `summary inflow outflow netflow`() {
        val body = mapper.readTree(generator(standardFlows(), standardTrades()).generate(userId, period).bodyJson)
        val s = body["summary"]
        // 유입 = 입금 1,000,000 + 매도 2,000,000 + 배당 50,000 = 3,050,000
        assertEquals(3050000.0, s["totalInflow"].asDouble(), 0.01)
        // 유출 = 출금 300,000 + 매수 5,000,000 + 수수료세금(1500+800+3000+7700=13,000) = 5,313,000
        assertEquals(5313000.0, s["totalOutflow"].asDouble(), 0.01)
        assertEquals(-2263000.0, s["netFlow"].asDouble(), 0.01)
    }

    @Test
    fun `byType signs and directions`() {
        val body = mapper.readTree(generator(standardFlows(), standardTrades()).generate(userId, period).bodyJson)
        val byType = body["byType"].associate { it["type"].asText() to it }
        assertEquals(1000000.0, byType["입금"]!!["amount"].asDouble(), 0.01)
        assertEquals("IN", byType["입금"]!!["direction"].asText())
        assertEquals(2000000.0, byType["매도대금"]!!["amount"].asDouble(), 0.01)
        assertEquals(50000.0, byType["배당·이자"]!!["amount"].asDouble(), 0.01)
        assertEquals(-300000.0, byType["출금"]!!["amount"].asDouble(), 0.01)
        assertEquals(-5000000.0, byType["매수대금"]!!["amount"].asDouble(), 0.01)
        assertEquals(-13000.0, byType["수수료·세금"]!!["amount"].asDouble(), 0.01)
        assertEquals("OUT", byType["수수료·세금"]!!["direction"].asText())
    }

    @Test
    fun `dividend classified as dividend not sell`() {
        val body = mapper.readTree(generator(emptyList(), listOf(trade(10, "DIVIDEND", "삼성전자", "50000", "0", "0"))).generate(userId, period).bodyJson)
        val types = body["byType"].map { it["type"].asText() }.toSet()
        assertTrue(types.contains("배당·이자"))
        assertTrue(!types.contains("매도대금"))
    }

    @Test
    fun `monthly aggregation consistent with summary`() {
        val body = mapper.readTree(generator(standardFlows(), standardTrades()).generate(userId, period).bodyJson)
        assertEquals(1, body["monthly"].size())
        val m = body["monthly"][0]
        assertEquals("2026-06", m["month"].asText())
        assertEquals(3050000.0, m["inflow"].asDouble(), 0.01)
        assertEquals(5313000.0, m["outflow"].asDouble(), 0.01)
        assertEquals(-2263000.0, m["net"].asDouble(), 0.01)
    }

    @Test
    fun `details merge flows and trades sorted by date with signs`() {
        val body = mapper.readTree(generator(standardFlows(), standardTrades()).generate(userId, period).bodyJson)
        val d = body["details"]
        assertEquals(5, d.size())  // 입금·출금 2 + 거래 3
        assertEquals("2026-06-01", d[0]["date"].asText())   // 최초 = 입금
        assertEquals(1000000.0, d[0]["amount"].asDouble(), 0.01)
        assertEquals("2026-06-05", d[1]["date"].asText())   // 매수 = 음수
        assertEquals(-5000000.0, d[1]["amount"].asDouble(), 0.01)
    }

    @Test
    fun `empty yields valid zero report`() {
        val generated = generator(emptyList(), emptyList()).generate(userId, period)
        assertEquals(LocalDate.of(2026, 6, 30), generated.asOfDate)
        val body = mapper.readTree(generated.bodyJson)
        assertEquals(0.0, body["summary"]["netFlow"].asDouble(), 0.01)
        assertEquals(0, body["details"].size())
        assertTrue(body["byType"].isEmpty)
    }
}
```

- [ ] **Step 2 (RED 확인)**

Run: `./gradlew :unified-asset:test --tests "*CashflowReportGeneratorTest*"`
Expected: 컴파일 실패(`CashflowReportGenerator` 미존재).

- [ ] **Step 3 (GREEN): 생성기 구현**

```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.application.GeneratedReport
import com.allfolio.report.application.ReportBodyGenerator
import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.report.domain.archive.ReportType
import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.port.CashflowTradeSource
import com.allfolio.unifiedasset.application.port.TradeCashRecord
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.domain.cashflow.FlowType
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * R-06 현금흐름 보고서 생성 엔진 (R2 #41 BE).
 * cash_flow(입금/출금) + ua_stock_trades(매수/매도/배당/수수료)를 유형별 분류·집계.
 * 유입: 입금·매도대금·배당·이자 / 유출: 출금·매수대금·수수료·세금. 순흐름 = 유입 − 유출.
 * v1 제외: 기초/기말 조정·정합검증(월초 잔고 부재), 환전·계좌간이체, 특이거래. 0건은 예외 없는 유효 0 보고서.
 */
@Component
class CashflowReportGenerator(
    private val cashFlowRepository: CashFlowRepository,
    private val tradeSource: CashflowTradeSource,
    private val accountRepository: AccountRepository,
) : ReportBodyGenerator {

    override val type = ReportType.CASHFLOW
    private val mapper = jacksonObjectMapper()

    private val buyTypes = setOf("BUY", "CREDIT_BUY")
    private val sellTypes = setOf("SELL", "CREDIT_SELL")

    override fun generate(userId: UUID, period: ReportPeriod): GeneratedReport {
        val flows = cashFlowRepository.findByUserIdAndPeriod(userId, period.start, period.end)
        val trades = tradeSource.findTrades(userId, period.start, period.end)
        val acctNames = accountRepository.findByUserId(userId).associate { it.id to it.accountName }

        fun sumFlow(t: FlowType) = flows.filter { it.type == t }.fold(BigDecimal.ZERO) { a, f -> a + f.amountKrw }
        fun sumTrade(pred: (TradeCashRecord) -> Boolean) =
            trades.filter(pred).fold(BigDecimal.ZERO) { a, t -> a + t.totalAmount }

        val deposit = sumFlow(FlowType.DEPOSIT)
        val withdrawal = sumFlow(FlowType.WITHDRAWAL)
        val buy = sumTrade { it.tradeType in buyTypes }
        val sell = sumTrade { it.tradeType in sellTypes }
        val dividend = sumTrade { it.tradeType == "DIVIDEND" }
        val feesTax = trades.fold(BigDecimal.ZERO) { a, t -> a + t.fee + t.tax }

        val totalInflow = deposit + sell + dividend
        val totalOutflow = withdrawal + buy + feesTax
        val netFlow = totalInflow - totalOutflow

        val byType = buildList {
            fun row(type: String, amount: BigDecimal, dir: String) =
                mapOf("type" to type, "amount" to amount, "direction" to dir)
            if (deposit.signum() != 0) add(row("입금", deposit, "IN"))
            if (sell.signum() != 0) add(row("매도대금", sell, "IN"))
            if (dividend.signum() != 0) add(row("배당·이자", dividend, "IN"))
            if (withdrawal.signum() != 0) add(row("출금", withdrawal.negate(), "OUT"))
            if (buy.signum() != 0) add(row("매수대금", buy.negate(), "OUT"))
            if (feesTax.signum() != 0) add(row("수수료·세금", feesTax.negate(), "OUT"))
        }

        // 상세 (cash_flow + trades 통합) — 원거래 principal 기준, 수수료는 요약/유형에 반영
        data class Row(val date: LocalDate, val account: String, val type: String, val desc: String, val amount: BigDecimal)
        val flowRows = flows.map {
            val acct = it.accountId?.let { id -> acctNames[id] } ?: "-"
            if (it.type == FlowType.DEPOSIT) Row(it.flowDate, acct, "입금", it.memo ?: "입금", it.amountKrw)
            else Row(it.flowDate, acct, "출금", it.memo ?: "출금", it.amountKrw.negate())
        }
        val tradeRows = trades.mapNotNull { t ->
            when {
                t.tradeType in buyTypes -> Row(t.tradeDate, t.accountName, "매수대금", t.stockName, t.totalAmount.negate())
                t.tradeType in sellTypes -> Row(t.tradeDate, t.accountName, "매도대금", t.stockName, t.totalAmount)
                t.tradeType == "DIVIDEND" -> Row(t.tradeDate, t.accountName, "배당·이자", t.stockName, t.totalAmount)
                else -> null
            }
        }
        val details = (flowRows + tradeRows).sortedBy { it.date }.map {
            mapOf("date" to it.date.toString(), "account" to it.account, "type" to it.type,
                  "description" to it.desc, "amount" to it.amount)
        }

        // 월별 (요약과 동일 분류로 산출 — 수수료 포함)
        val months = (flows.map { ym(it.flowDate) } + trades.map { ym(it.tradeDate) }).distinct().sorted()
        val monthly = months.map { m ->
            val mf = flows.filter { ym(it.flowDate) == m }
            val mt = trades.filter { ym(it.tradeDate) == m }
            val inflow = mf.filter { it.type == FlowType.DEPOSIT }.fold(BigDecimal.ZERO) { a, f -> a + f.amountKrw } +
                mt.filter { it.tradeType in sellTypes }.fold(BigDecimal.ZERO) { a, t -> a + t.totalAmount } +
                mt.filter { it.tradeType == "DIVIDEND" }.fold(BigDecimal.ZERO) { a, t -> a + t.totalAmount }
            val outflow = mf.filter { it.type == FlowType.WITHDRAWAL }.fold(BigDecimal.ZERO) { a, f -> a + f.amountKrw } +
                mt.filter { it.tradeType in buyTypes }.fold(BigDecimal.ZERO) { a, t -> a + t.totalAmount } +
                mt.fold(BigDecimal.ZERO) { a, t -> a + t.fee + t.tax }
            mapOf("month" to m, "inflow" to inflow, "outflow" to outflow, "net" to (inflow - outflow))
        }

        val body = mapOf(
            "summary" to mapOf("totalInflow" to totalInflow, "totalOutflow" to totalOutflow, "netFlow" to netFlow),
            "byType" to byType,
            "monthly" to monthly,
            "details" to details,
        )
        val lastDate = (flows.map { it.flowDate } + trades.map { it.tradeDate }).maxOrNull() ?: period.end
        return GeneratedReport(asOfDate = lastDate, bodyJson = mapper.writeValueAsString(body))
    }

    private fun ym(d: LocalDate) = d.toString().substring(0, 7)
}
```

- [ ] **Step 4 (GREEN 확인)**

Run: `./gradlew :unified-asset:test --tests "*CashflowReportGeneratorTest*"`
Expected: 6개 테스트 전부 PASS. 실패 시 원인 수정(테스트 약화 금지).

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/CashflowReportGenerator.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/CashflowReportGeneratorTest.kt
git commit -m "feat(cashflow): 현금흐름 보고서 생성 엔진 R-06 v1 (#32 프레임 등록, TDD)"
```

---

## Task 3: JDBC 어댑터

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/adapter/JdbcCashflowTradeSource.kt`

- [ ] **Step 1: 어댑터 작성**

기간 내 전 주식 거래(현금흐름 분류용). `ua_stock_trades` JOIN `ua_accounts`.

```kotlin
package com.allfolio.unifiedasset.infrastructure.adapter

import com.allfolio.unifiedasset.application.port.CashflowTradeSource
import com.allfolio.unifiedasset.application.port.TradeCashRecord
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

@Repository
class JdbcCashflowTradeSource(private val jdbc: JdbcTemplate) : CashflowTradeSource {

    override fun findTrades(userId: UUID, from: LocalDate, to: LocalDate): List<TradeCashRecord> =
        jdbc.query(
            """SELECT t.traded_at, t.trade_type, t.stock_name, t.total_amount, t.fee, t.tax,
                      a.account_name
               FROM ua_stock_trades t
               JOIN ua_accounts a ON a.id = t.account_id
               WHERE t.user_id = ? AND t.traded_at >= ? AND t.traded_at <= ?
               ORDER BY t.traded_at ASC""",
            { rs, _ ->
                TradeCashRecord(
                    tradeDate = rs.getDate("traded_at").toLocalDate(),
                    tradeType = rs.getString("trade_type"),
                    stockName = rs.getString("stock_name"),
                    accountName = rs.getString("account_name"),
                    totalAmount = rs.getBigDecimal("total_amount"),
                    fee = rs.getBigDecimal("fee"),
                    tax = rs.getBigDecimal("tax"),
                )
            },
            userId, from, to,
        )
}
```

- [ ] **Step 2: 전체 모듈 테스트 + 커밋**

Run: `./gradlew :unified-asset:test` (Expected: BUILD SUCCESSFUL)
```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/adapter/JdbcCashflowTradeSource.kt
git commit -m "feat(cashflow): 현금흐름 거래 JDBC 어댑터 — ua_stock_trades 전체 (R2 #41)"
```

---

## Task 4: 스모크 검증

**Files:** (없음 — 검증 전용)

- [ ] **Step 1: 빈 스캔 확인** — 앱 기동 시 "리포트 타입당 생성기는 하나여야 합니다" require 통과(CASHFLOW 중복 없음).

- [ ] **Step 2: 생성·조회 스모크**

로컬 기동 → `cash_flow`(입금/출금) + `ua_stock_trades`(BUY/SELL/DIVIDEND, fee·tax) 시드 → `POST /api/reports/archive/generate {type:"CASHFLOW", year:2026, month:6}` → `GET /api/reports/archive/{id}` 본문 검산:
- `summary.totalInflow/totalOutflow/netFlow` = 유형별 부호 합
- `byType` 6유형 부호·direction, `monthly` 요약과 일치, `details` cash_flow+trades 통합·날짜순·부호
- DIVIDEND → 배당·이자 분류, 재생성 upsert

- [ ] **Step 3: 0건 스모크** — 흐름·거래 없는 유저 → `generate` → 400 아닌 정상 + 빈 배열/0 요약.

- [ ] **Step 4: 정리** — 시드 정리. 수정 있었으면 커밋.

---

## 완료 기준

- `POST /api/reports/archive/generate {type: CASHFLOW}` 동작, 본문 4키(summary·byType·monthly·details)
- 유형별 부호(유입 +/유출 −)·순현금흐름·월별 요약 일치·details 통합 정확, DIVIDEND 배당 분류
- 0건 → 예외 없는 유효 보고서
- `./gradlew :unified-asset:test` 통과, 기존 리포트 영향 없음
- FE 화면(SCR-RPT-09)은 #41 2단계 별도
