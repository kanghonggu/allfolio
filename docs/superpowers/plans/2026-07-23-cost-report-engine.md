# 비용 보고서 생성 엔진 (R-04) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `CostReportGenerator`(type=COST)를 #32 프레임에 등록 — `ua_stock_trades` 비-DIVIDEND 거래의 fee(매매수수료)·tax(거래세)를 집계하고 비용률·TER·수익대비를 산출한 본문 JSON을 생성해 아카이브한다.

**Architecture:** 헥사고날(#38 배당 엔진과 동일) — 순수 집계 생성기(fake 포트로 단위 테스트) + `CostLedgerSource` 포트 + JDBC 어댑터. 비용률/수익대비는 #33 `GetReturnsAnalysisUseCase`를 `runCatching`으로 감싸 null-safe. DIVIDEND는 R-03 이중집계 방지를 위해 제외. 거래 0건은 유효한 0 보고서.

**Tech Stack:** Kotlin/Spring · JdbcTemplate 어댑터 · 기존 포트/유스케이스 재사용 · 신규 DDL 없음.

**Spec:** `docs/superpowers/specs/2026-07-23-cost-report-engine-design.md`

**테스트 명령:** `./gradlew :unified-asset:test --tests "*CostReportGeneratorTest*"` (전체: `./gradlew :unified-asset:test`)

---

## File Structure

- Create `unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/port/CostLedgerSource.kt` — 포트 + `CostRecord`
- Create `unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/CostReportGenerator.kt` — 생성기
- Create `unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/CostReportGeneratorTest.kt` — 단위 테스트
- Create `unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/adapter/JdbcCostLedgerSource.kt` — JDBC 어댑터

경로 접두사: `allfolio-backend/`

---

## Task 1: 포트 + CostRecord

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/port/CostLedgerSource.kt`

- [ ] **Step 1: 포트 작성**

```kotlin
package com.allfolio.unifiedasset.application.port

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** 비용 거래 1건 — 금액은 KRW 취급(ua_stock_trades에 통화 컬럼 없음). DIVIDEND 제외(R-03 전담). */
data class CostRecord(
    val tradeDate: LocalDate,
    val stockName: String,
    val symbol: String?,
    val accountName: String,
    val provider: String,
    val tradeType: String,
    val fee: BigDecimal,   // 매매수수료
    val tax: BigDecimal,   // 거래세·제세금
) {
    val total: BigDecimal get() = fee + tax
}

interface CostLedgerSource {
    /** [from, to] 구간의 비-DIVIDEND 거래 비용 (거래일 오름차순) */
    fun findCosts(userId: UUID, from: LocalDate, to: LocalDate): List<CostRecord>
}
```

- [ ] **Step 2: 컴파일 확인 + 커밋**

Run: `./gradlew :unified-asset:compileKotlin`
Expected: BUILD SUCCESSFUL

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/port/CostLedgerSource.kt
git commit -m "feat(cost): 비용 원장 소스 포트 + CostRecord (R1 #39)"
```

---

## Task 2: CostReportGenerator (TDD)

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/CostReportGenerator.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/CostReportGeneratorTest.kt`

- [ ] **Step 1 (RED): 테스트 작성**

`GetReturnsAnalysisUseCase`는 인터페이스가 아니므로, `MonthlyReportGeneratorTest`와 동일하게 fake NAV/현금흐름/BM 포트로 **실제 인스턴스를 조립**해 avgNav·pnl을 제어한다. `NavHistorySource`/fake들은 동일 패키지(`application.usecase`)라 import 불필요한 것 있음(아래 import는 검증됨).

```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.returns.NavPoint
import com.allfolio.unifiedasset.application.port.BenchmarkDailyStore
import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.port.CostLedgerSource
import com.allfolio.unifiedasset.application.port.CostRecord
import com.allfolio.unifiedasset.application.port.UserBenchmarkLookup
import com.allfolio.unifiedasset.domain.benchmark.BenchmarkType
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.report.domain.archive.ReportPeriod
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class CostReportGeneratorTest {

    private val userId = UUID.randomUUID()
    private val period = ReportPeriod.monthly(2026, 6)
    private val mapper = jacksonObjectMapper()

    // ── fakes ─────────────────────────────────────────────────────
    private class FakeCostLedger(private val all: List<CostRecord>) : CostLedgerSource {
        override fun findCosts(userId: UUID, from: LocalDate, to: LocalDate) =
            all.filter { it.tradeDate in from..to }.sortedBy { it.tradeDate }
    }
    private class FakeNavSource(private val points: List<NavPoint>) : NavHistorySource {
        override fun navSeries(userId: UUID, from: LocalDate, to: LocalDate) =
            points.filter { it.date in from..to }
    }
    private class FakeCashFlowRepo : CashFlowRepository {
        override fun save(cashFlow: CashFlow) = cashFlow
        override fun findById(id: UUID): CashFlow? = null
        override fun findByUserIdAndPeriod(userId: UUID, from: LocalDate, to: LocalDate) = emptyList<CashFlow>()
        override fun findByUserId(userId: UUID) = emptyList<CashFlow>()
        override fun delete(id: UUID) {}
    }
    private class FakeUserBm : UserBenchmarkLookup {
        override fun get(userId: UUID): BenchmarkType? = null
    }
    private class FakeBmStore : BenchmarkDailyStore {
        override fun latestDate(type: BenchmarkType): LocalDate? = null
        override fun upsert(type: BenchmarkType, rows: List<Pair<LocalDate, BigDecimal>>) {}
        override fun series(type: BenchmarkType, from: LocalDate, to: LocalDate) = emptyList<Pair<LocalDate, BigDecimal>>()
    }

    private fun cost(day: Int, provider: String, fee: String, tax: String, type: String = "BUY", name: String = "삼성전자") =
        CostRecord(LocalDate.of(2026, 6, day), name, "005930", "$provider 계좌", provider, type, BigDecimal(fee), BigDecimal(tax))

    private fun nav(day: Int, v: String) = NavPoint(LocalDate.of(2026, 6, day), BigDecimal(v))

    private fun generator(costs: List<CostRecord>, navs: List<NavPoint> = emptyList()): CostReportGenerator {
        val analysis = GetReturnsAnalysisUseCase(FakeNavSource(navs), FakeCashFlowRepo(), FakeUserBm(), FakeBmStore())
        return CostReportGenerator(FakeCostLedger(costs), analysis)
    }

    @Test
    fun `summary totals fee and tax`() {
        val body = mapper.readTree(generator(listOf(
            cost(3, "KIS", "1000", "230"),
            cost(20, "BINANCE", "500", "0"),
        )).generate(userId, period).bodyJson)
        val s = body["summary"]
        assertEquals(1730.0, s["totalCost"].asDouble(), 0.01)
        assertEquals(1500.0, s["brokerFee"].asDouble(), 0.01)
        assertEquals(230.0, s["tradingTax"].asDouble(), 0.01)
        assertEquals(2, s["tradeCount"].asInt())
    }

    @Test
    fun `byType weights sum to about 100`() {
        val body = mapper.readTree(generator(listOf(cost(3, "KIS", "1000", "1000"))).generate(userId, period).bodyJson)
        val sum = body["byType"].sumOf { it["weight"].asDouble() }
        assertEquals(100.0, sum, 0.1)
    }

    @Test
    fun `byBroker aggregates fee and tax per provider`() {
        val body = mapper.readTree(generator(listOf(
            cost(3, "KIS", "1000", "230"),
            cost(4, "KIS", "500", "100"),
            cost(20, "BINANCE", "300", "0"),
        )).generate(userId, period).bodyJson)
        val kis = body["byBroker"].first { it["broker"].asText() == "KIS" }
        assertEquals(1500.0, kis["fee"].asDouble(), 0.01)
        assertEquals(330.0, kis["tax"].asDouble(), 0.01)
        assertEquals(1830.0, kis["total"].asDouble(), 0.01)
    }

    @Test
    fun `monthly aggregates by year-month`() {
        val body = mapper.readTree(generator(listOf(
            cost(3, "KIS", "1000", "0"),
            cost(20, "KIS", "500", "230"),
        )).generate(userId, period).bodyJson)
        assertEquals(1, body["monthly"].size())
        assertEquals("2026-06", body["monthly"][0]["month"].asText())
        assertEquals(1730.0, body["monthly"][0]["total"].asDouble(), 0.01)
    }

    @Test
    fun `cost ratio computed from average nav`() {
        // 평균 NAV = (10,000,000 + 10,000,000)/2 = 10,000,000; 총비용 1000 → 0.01%
        val body = mapper.readTree(
            generator(listOf(cost(3, "KIS", "1000", "0")), navs = listOf(nav(1, "10000000"), nav(30, "10000000")))
                .generate(userId, period).bodyJson
        )
        assertEquals(0.01, body["summary"]["costRatio"].asDouble(), 0.001)
        assertTrue(body["summary"]["annualizedTer"].isNumber)
    }

    @Test
    fun `cost ratio null when nav insufficient`() {
        // NAV 1건 → analyze InsufficientDataException → costRatio·TER null
        val body = mapper.readTree(
            generator(listOf(cost(3, "KIS", "1000", "0")), navs = listOf(nav(1, "10000000")))
                .generate(userId, period).bodyJson
        )
        assertTrue(body["summary"]["costRatio"].isNull)
        assertTrue(body["summary"]["annualizedTer"].isNull)
        assertTrue(body["summary"]["costVsProfit"].isNull)
    }

    @Test
    fun `cost vs profit null when pnl is zero`() {
        // NAV 평탄(10M→10M), 현금흐름 없음 → investmentPnl 0 → costVsProfit null (costRatio는 non-null)
        val body = mapper.readTree(
            generator(listOf(cost(3, "KIS", "1000", "0")), navs = listOf(nav(1, "10000000"), nav(30, "10000000")))
                .generate(userId, period).bodyJson
        )
        assertTrue(body["summary"]["costVsProfit"].isNull)
        assertTrue(body["summary"]["costRatio"].isNumber)
    }

    @Test
    fun `zero trades yields valid empty report without exception`() {
        val generated = generator(emptyList()).generate(userId, period)
        assertEquals(LocalDate.of(2026, 6, 30), generated.asOfDate)
        val body = mapper.readTree(generated.bodyJson)
        assertEquals(0.0, body["summary"]["totalCost"].asDouble(), 0.01)
        assertEquals(0, body["details"].size())
        assertTrue(body["byType"].isEmpty)
    }
}
```

- [ ] **Step 2 (RED 확인)**

Run: `./gradlew :unified-asset:test --tests "*CostReportGeneratorTest*"`
Expected: 컴파일 실패(`CostReportGenerator` 미존재).

- [ ] **Step 3 (GREEN): 생성기 구현**

```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.application.GeneratedReport
import com.allfolio.report.application.ReportBodyGenerator
import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.report.domain.archive.ReportType
import com.allfolio.unifiedasset.application.port.CostLedgerSource
import com.allfolio.unifiedasset.application.port.CostRecord
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * R-04 비용 보고서 생성 엔진 (R1 #39 BE).
 * ua_stock_trades 비-DIVIDEND 거래의 fee(매매수수료)·tax(거래세)를 브로커/유형/월별 집계.
 * 비용률·TER·수익대비는 #33 수익률 엔진을 runCatching으로 감싸 null-safe.
 * DIVIDEND 제외(R-03 원천징수 이중집계 방지). 거래 0건은 예외 없는 유효 0 보고서.
 * v1 제외: 환전 비용, 파생 수수료, 인사이트.
 */
@Component
class CostReportGenerator(
    private val costLedger: CostLedgerSource,
    private val returnsAnalysis: GetReturnsAnalysisUseCase,
) : ReportBodyGenerator {

    override val type = ReportType.COST

    private val mapper = jacksonObjectMapper()
    private val mc = MathContext(10, RoundingMode.HALF_UP)

    override fun generate(userId: UUID, period: ReportPeriod): GeneratedReport {
        val records = costLedger.findCosts(userId, period.start, period.end)

        val brokerFee = records.sum { it.fee }
        val tradingTax = records.sum { it.tax }
        val totalCost = brokerFee + tradingTax

        val analysis = runCatching { returnsAnalysis.analyze(userId, period.start, period.end) }.getOrNull()
        val avgNav: BigDecimal? = analysis?.navSeries?.takeIf { it.isNotEmpty() }
            ?.let { s -> s.fold(BigDecimal.ZERO) { a, p -> a + p.nav }.divide(BigDecimal(s.size), mc) }
        val pnl: BigDecimal? = analysis?.summary?.investmentPnl

        val costRatio: BigDecimal? =
            if (avgNav != null && avgNav > BigDecimal.ZERO) pct(totalCost, avgNav) else null
        val days = ChronoUnit.DAYS.between(period.start, period.end) + 1
        val ter: BigDecimal? =
            costRatio?.multiply(BigDecimal(365))?.divide(BigDecimal(days), 2, RoundingMode.HALF_UP)
        val costVsProfit: BigDecimal? =
            if (pnl != null && pnl.signum() != 0) pct(totalCost, pnl.abs()) else null

        val byType = listOf("매매수수료" to brokerFee, "거래세" to tradingTax)
            .filter { it.second > BigDecimal.ZERO }
            .map { (t, amt) -> mapOf("type" to t, "amount" to amt, "weight" to pct(amt, totalCost)) }

        val byBroker = records.groupBy { it.provider }
            .map { (broker, rs) ->
                val f = rs.sum { it.fee }; val t = rs.sum { it.tax }
                mapOf("broker" to broker, "fee" to f, "tax" to t, "total" to (f + t), "weight" to pct(f + t, totalCost))
            }.sortedByDescending { it["total"] as BigDecimal }

        val monthly = records.groupBy { it.tradeDate.toString().substring(0, 7) }
            .map { (m, rs) ->
                val f = rs.sum { it.fee }; val t = rs.sum { it.tax }
                mapOf("month" to m, "brokerFee" to f, "tradingTax" to t, "total" to (f + t))
            }.sortedBy { it["month"] as String }

        val details = records.map {
            mapOf(
                "date" to it.tradeDate.toString(), "account" to it.accountName, "provider" to it.provider,
                "tradeType" to it.tradeType, "stockName" to it.stockName, "fee" to it.fee, "tax" to it.tax,
            )
        }

        val body = mapOf(
            "summary" to mapOf(
                "totalCost" to totalCost, "brokerFee" to brokerFee, "tradingTax" to tradingTax,
                "tradeCount" to records.size,
                "costRatio" to costRatio, "annualizedTer" to ter, "costVsProfit" to costVsProfit,
            ),
            "byType" to byType,
            "byBroker" to byBroker,
            "monthly" to monthly,
            "details" to details,
        )
        val asOf = records.maxOfOrNull { it.tradeDate } ?: period.end
        return GeneratedReport(asOfDate = asOf, bodyJson = mapper.writeValueAsString(body))
    }

    private fun List<CostRecord>.sum(sel: (CostRecord) -> BigDecimal): BigDecimal =
        fold(BigDecimal.ZERO) { acc, r -> acc + sel(r) }

    /** a/b × 100, 0~100 스케일 (b<=0이면 0) */
    private fun pct(a: BigDecimal, b: BigDecimal): BigDecimal =
        if (b <= BigDecimal.ZERO) BigDecimal.ZERO
        else a.divide(b, mc).multiply(BigDecimal(100), mc).setScale(2, RoundingMode.HALF_UP)
}
```

- [ ] **Step 4 (GREEN 확인)**

Run: `./gradlew :unified-asset:test --tests "*CostReportGeneratorTest*"`
Expected: 8개 테스트 전부 PASS. 실패 시 원인 수정(테스트 약화 금지).

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/CostReportGenerator.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/CostReportGeneratorTest.kt
git commit -m "feat(cost): 비용 보고서 생성 엔진 R-04 v1 (#32 프레임 등록, TDD)"
```

---

## Task 3: JDBC 어댑터

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/adapter/JdbcCostLedgerSource.kt`

- [ ] **Step 1: 어댑터 작성**

`trade_type <> 'DIVIDEND'`(원천징수 제외) 이면서 비용이 있는 행(`fee > 0 OR tax > 0`)만.

```kotlin
package com.allfolio.unifiedasset.infrastructure.adapter

import com.allfolio.unifiedasset.application.port.CostLedgerSource
import com.allfolio.unifiedasset.application.port.CostRecord
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

@Repository
class JdbcCostLedgerSource(private val jdbc: JdbcTemplate) : CostLedgerSource {

    override fun findCosts(userId: UUID, from: LocalDate, to: LocalDate): List<CostRecord> =
        jdbc.query(
            """SELECT t.traded_at, t.stock_name, t.symbol, t.trade_type, t.fee, t.tax,
                      a.account_name, a.provider
               FROM ua_stock_trades t
               JOIN ua_accounts a ON a.id = t.account_id
               WHERE t.user_id = ? AND t.trade_type <> 'DIVIDEND'
                 AND (t.fee > 0 OR t.tax > 0)
                 AND t.traded_at >= ? AND t.traded_at <= ?
               ORDER BY t.traded_at ASC""",
            { rs, _ ->
                CostRecord(
                    tradeDate = rs.getDate("traded_at").toLocalDate(),
                    stockName = rs.getString("stock_name"),
                    symbol = rs.getString("symbol"),
                    accountName = rs.getString("account_name"),
                    provider = rs.getString("provider"),
                    tradeType = rs.getString("trade_type"),
                    fee = rs.getBigDecimal("fee"),
                    tax = rs.getBigDecimal("tax"),
                )
            },
            userId, from, to,
        )
}
```

- [ ] **Step 2: 전체 모듈 테스트 통과 확인**

Run: `./gradlew :unified-asset:test`
Expected: BUILD SUCCESSFUL (신규 생성기 테스트 포함 전부 통과).

- [ ] **Step 3: 커밋**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/adapter/JdbcCostLedgerSource.kt
git commit -m "feat(cost): 비용 원장 JDBC 어댑터 — ua_stock_trades 비-DIVIDEND (R1 #39)"
```

---

## Task 4: 스모크 검증

**Files:** (없음 — 검증 전용)

- [ ] **Step 1: 빈 스캔 확인**

앱 기동 시 `GenerateReportUseCase`의 "리포트 타입당 생성기는 하나여야 합니다" require 통과(COST 생성기 중복 없음).

- [ ] **Step 2: 생성·조회 스모크**

로컬 기동 → `ua_stock_trades` 시드: BUY(fee 1000, tax 0) + SELL(fee 500, tax 230) + **DIVIDEND(tax 1540)** 각 1건 → `POST /api/reports/archive/generate {type:"COST", year:2026, month:6}` → `GET /api/reports/archive/{id}` 본문 검산:
- `summary.totalCost = 1730` (DIVIDEND의 1540 **미포함** 확인 — 핵심)
- `byType`에 매매수수료 1500·거래세 230
- `byBroker` 매트릭스, `details`에 DIVIDEND 없음
- NAV 시드 있으면 `costRatio` 값, 없으면 null
- 재생성 upsert

- [ ] **Step 3: 거래 0건 스모크**

비용 거래 없는 유저 → `generate` → 400이 아닌 정상 + 빈 배열/0 요약(예외 없음).

- [ ] **Step 4: 정리**

시드 정리. 수정 있었으면 커밋.

---

## 완료 기준

- `POST /api/reports/archive/generate {type: COST}` 동작, 본문 5키(summary·byType·byBroker·monthly·details)
- 총비용=fee+tax, **DIVIDEND 제외**, 비용률/TER/수익대비 null-safe
- 거래 0건 → 예외 없는 유효 보고서
- `./gradlew :unified-asset:test` 통과, 기존 리포트·화면 영향 없음
- FE 화면(SCR-RPT-07)은 #39 2단계 별도
