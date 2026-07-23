# 배당·이자 보고서 생성 엔진 (R-03) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `DividendInterestReportGenerator`(type=DIVIDEND_INTEREST)를 #32 프레임에 등록 — `ua_stock_trades` 배당 행의 세전·원천징수·세후를 집계한 본문 JSON을 생성해 아카이브한다.

**Architecture:** 헥사고날 — 순수 집계 생성기(fake 포트로 단위 테스트) + `DividendLedgerSource` 포트 + JDBC 어댑터. 기존 `DividendReportService`(live `/reports/dividend` 전용)는 그대로 유지. 배당 0건은 예외가 아닌 유효한 0 보고서.

**Tech Stack:** Kotlin/Spring · JdbcTemplate 어댑터 · 기존 포트(AssetRepository·FxConverter) 재사용 · 신규 DDL 없음.

**Spec:** `docs/superpowers/specs/2026-07-23-dividend-report-engine-design.md`

**테스트 명령:** `./gradlew :unified-asset:test --tests "*DividendInterestReportGeneratorTest*"` (전체: `./gradlew :unified-asset:test`)

---

## File Structure

- Create `unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/port/DividendLedgerSource.kt` — 포트 + `DividendRecord`
- Create `unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/DividendInterestReportGenerator.kt` — 생성기
- Create `unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/DividendInterestReportGeneratorTest.kt` — 단위 테스트
- Create `unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/adapter/JdbcDividendLedgerSource.kt` — JDBC 어댑터

경로 접두사: `allfolio-backend/`

---

## Task 1: 포트 + DividendRecord

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/port/DividendLedgerSource.kt`

- [ ] **Step 1: 포트 작성**

```kotlin
package com.allfolio.unifiedasset.application.port

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** 배당 수취 1건 — 금액은 KRW 취급(ua_stock_trades에 통화 컬럼 없음). */
data class DividendRecord(
    val payDate: LocalDate,
    val stockName: String,
    val symbol: String?,
    val accountName: String,
    val provider: String,
    val gross: BigDecimal,   // 세전 (total_amount)
    val tax: BigDecimal,     // 원천징수
) {
    val net: BigDecimal get() = gross - tax
}

interface DividendLedgerSource {
    /** [from, to] 구간의 배당 수취 기록 (지급일 오름차순) */
    fun findDividends(userId: UUID, from: LocalDate, to: LocalDate): List<DividendRecord>
}
```

- [ ] **Step 2: 컴파일 확인 + 커밋**

Run: `./gradlew :unified-asset:compileKotlin`
Expected: BUILD SUCCESSFUL

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/port/DividendLedgerSource.kt
git commit -m "feat(dividend): 배당 원장 소스 포트 + DividendRecord (R1 #38)"
```

---

## Task 2: DividendInterestReportGenerator (TDD)

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/DividendInterestReportGenerator.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/DividendInterestReportGeneratorTest.kt`

- [ ] **Step 1 (RED): 테스트 작성**

fake `DividendLedgerSource`/`AssetRepository`/`FxConverter`로 생성기를 조립. `Asset.create` 팩토리는 `MonthlyReportGeneratorTest`와 동일 시그니처.

```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.report.domain.archive.ReportType
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.DividendLedgerSource
import com.allfolio.unifiedasset.application.port.DividendRecord
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.asset.AssetCategory
import com.allfolio.unifiedasset.domain.asset.AssetSourceType
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.domain.asset.ValuationMethod
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class DividendInterestReportGeneratorTest {

    private val userId = UUID.randomUUID()
    private val accountId = UUID.randomUUID()
    private val period = ReportPeriod.monthly(2026, 6)
    private val mapper = jacksonObjectMapper()

    private class FakeLedger(private val all: List<DividendRecord>) : DividendLedgerSource {
        override fun findDividends(userId: UUID, from: LocalDate, to: LocalDate) =
            all.filter { it.payDate in from..to }.sortedBy { it.payDate }
    }

    private class FakeAssetRepo(private val assets: List<Asset>) : AssetRepository {
        override fun save(asset: Asset) = asset
        override fun saveAll(assets: List<Asset>) = assets
        override fun findById(id: UUID): Asset? = null
        override fun findByAccountId(accountId: UUID) = assets
        override fun findByUserId(userId: UUID) = assets
        override fun deleteByAccountId(accountId: UUID) {}
        override fun delete(id: UUID) {}
    }

    private val fx = object : FxConverter {
        override fun toKrw(amount: BigDecimal, currency: String): BigDecimal =
            if (currency.uppercase() == "KRW") amount else amount * BigDecimal("1000")
    }

    private fun asset(valueKrw: String) = Asset.create(
        userId = userId, accountId = accountId, category = AssetCategory.FINANCIAL,
        type = AssetType.STOCK, sourceType = AssetSourceType.STOCK_API, name = "보유", symbol = "005930",
        quantity = BigDecimal.ONE, purchasePrice = BigDecimal(valueKrw),
        currentValue = BigDecimal(valueKrw), currency = "KRW",
        valuationMethod = ValuationMethod.BALANCE,
    )

    private fun rec(day: Int, name: String, symbol: String?, gross: String, tax: String) =
        DividendRecord(LocalDate.of(2026, 6, day), name, symbol, "한투", "KIS", BigDecimal(gross), BigDecimal(tax))

    private fun generator(
        records: List<DividendRecord>,
        assets: List<Asset> = listOf(asset("100000000")),
    ) = DividendInterestReportGenerator(FakeLedger(records), FakeAssetRepo(assets), fx)

    @Test
    fun `summary aggregates gross tax net and effective rate`() {
        val gen = generator(listOf(
            rec(3, "삼성전자", "005930", "10000", "1540"),
            rec(20, "AAPL", "AAPL", "20000", "3000"),
        ))
        val body = mapper.readTree(gen.generate(userId, period).bodyJson)
        val s = body["summary"]
        assertEquals(30000.0, s["grossTotal"].asDouble(), 0.01)
        assertEquals(4540.0, s["withholdingTax"].asDouble(), 0.01)
        assertEquals(25460.0, s["netTotal"].asDouble(), 0.01)
        assertEquals(2, s["receiptCount"].asInt())
        // 실효세율 = 4540/30000*100 = 15.13
        assertEquals(15.13, s["effectiveTaxRate"].asDouble(), 0.01)
    }

    @Test
    fun `receipt net equals gross minus tax`() {
        val gen = generator(listOf(rec(3, "삼성전자", "005930", "10000", "1540")))
        val body = mapper.readTree(gen.generate(userId, period).bodyJson)
        val r0 = body["receipts"][0]
        assertEquals(10000.0, r0["gross"].asDouble(), 0.01)
        assertEquals(1540.0, r0["tax"].asDouble(), 0.01)
        assertEquals(8460.0, r0["net"].asDouble(), 0.01)
    }

    @Test
    fun `bySymbol weights sum to about 100`() {
        val gen = generator(listOf(
            rec(3, "삼성전자", "005930", "10000", "0"),
            rec(20, "AAPL", "AAPL", "30000", "0"),
        ))
        val body = mapper.readTree(gen.generate(userId, period).bodyJson)
        val sum = body["bySymbol"].sumOf { it["weight"].asDouble() }
        assertEquals(100.0, sum, 0.1)
    }

    @Test
    fun `monthly aggregates net by year-month`() {
        val gen = generator(listOf(
            rec(3, "삼성전자", "005930", "10000", "1000"),
            rec(20, "삼성전자", "005930", "5000", "500"),
        ))
        val body = mapper.readTree(gen.generate(userId, period).bodyJson)
        assertEquals(1, body["monthly"].size())
        assertEquals("2026-06", body["monthly"][0]["month"].asText())
        assertEquals(13500.0, body["monthly"][0]["net"].asDouble(), 0.01)
    }

    @Test
    fun `byCountry buckets numeric ticker as domestic and alpha as overseas`() {
        val gen = generator(listOf(
            rec(3, "삼성전자", "005930", "10000", "1540"),
            rec(20, "AAPL", "AAPL", "20000", "3000"),
        ))
        val body = mapper.readTree(gen.generate(userId, period).bodyJson)
        val countries = body["byCountry"].map { it["country"].asText() }.toSet()
        assertEquals(setOf("국내", "해외"), countries)
    }

    @Test
    fun `zero dividends yields valid empty report without exception`() {
        val gen = generator(emptyList())
        val generated = gen.generate(userId, period)
        assertEquals(LocalDate.of(2026, 6, 30), generated.asOfDate)  // period.end fallback
        val body = mapper.readTree(generated.bodyJson)
        assertEquals(0.0, body["summary"]["grossTotal"].asDouble(), 0.01)
        assertEquals(0, body["receipts"].size())
        // 평가액 있으면 ttmYield=0 (배당 0 / 평가액)
        assertEquals(0.0, body["summary"]["ttmYield"].asDouble(), 0.01)
    }

    @Test
    fun `ttm yield is net over portfolio value when assets exist`() {
        // 평가액 100,000,000, TTM 세후 = 8460 → 0.008460% → 0.01 (반올림)
        val gen = generator(listOf(rec(3, "삼성전자", "005930", "10000", "1540")))
        val body = mapper.readTree(gen.generate(userId, period).bodyJson)
        assertTrue(body["summary"]["ttmYield"].isNumber)
    }

    @Test
    fun `null ttm yield when portfolio value is zero`() {
        val gen = generator(listOf(rec(3, "삼성전자", "005930", "10000", "1540")), assets = emptyList())
        val body = mapper.readTree(gen.generate(userId, period).bodyJson)
        assertTrue(body["summary"]["ttmYield"].isNull)
    }
}
```

- [ ] **Step 2 (RED 확인): 테스트가 컴파일 실패로 실패하는지 확인**

Run: `./gradlew :unified-asset:test --tests "*DividendInterestReportGeneratorTest*"`
Expected: 컴파일 실패(`DividendInterestReportGenerator` 미존재) 또는 테스트 실패.

- [ ] **Step 3 (GREEN): 생성기 구현**

```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.application.GeneratedReport
import com.allfolio.report.application.ReportBodyGenerator
import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.report.domain.archive.ReportType
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.DividendLedgerSource
import com.allfolio.unifiedasset.application.port.DividendRecord
import com.allfolio.unifiedasset.application.port.FxConverter
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

/**
 * R-03 배당·이자 보고서 생성 엔진 (R1 #38 BE).
 * ua_stock_trades DIVIDEND 행의 세전(total_amount)·원천징수(tax)·세후(차액)를 본문에 고정.
 * 금액은 KRW 취급(통화 컬럼 부재). 배당 0건은 예외가 아닌 유효한 0 보고서.
 * v1 제외: 세율 마스터·기대세율 비교, 이자, 배당 캘린더·예상.
 */
@Component
class DividendInterestReportGenerator(
    private val ledger: DividendLedgerSource,
    private val assetRepository: AssetRepository,
    private val fx: FxConverter,
) : ReportBodyGenerator {

    override val type = ReportType.DIVIDEND_INTEREST

    private val mapper = jacksonObjectMapper()
    private val mc = MathContext(10, RoundingMode.HALF_UP)

    override fun generate(userId: UUID, period: ReportPeriod): GeneratedReport {
        val records = ledger.findDividends(userId, period.start, period.end)
        val ttm = ledger.findDividends(userId, period.end.minusYears(1), period.end)

        val gross = records.sum { it.gross }
        val tax = records.sum { it.tax }
        val net = gross - tax

        val portfolioKrw = assetRepository.findByUserId(userId)
            .fold(BigDecimal.ZERO) { acc, a -> acc + a.currentValueInKrw(fx) }
        val ttmNet = ttm.sum { it.net }
        val ttmYield: BigDecimal? =
            if (portfolioKrw <= BigDecimal.ZERO) null else pct(ttmNet, portfolioKrw)

        val receipts = records.map {
            mapOf(
                "payDate" to it.payDate, "stockName" to it.stockName, "symbol" to it.symbol,
                "account" to it.accountName, "gross" to it.gross, "tax" to it.tax, "net" to it.net,
            )
        }

        val monthly = records.groupBy { it.payDate.toString().substring(0, 7) }
            .map { (m, rs) -> mapOf("month" to m, "net" to rs.sum { it.net }) }
            .sortedBy { it["month"] as String }

        val bySymbol = records.groupBy { it.stockName to it.symbol }
            .map { (key, rs) ->
                val g = rs.sum { it.gross }; val t = rs.sum { it.tax }
                mapOf(
                    "stockName" to key.first, "symbol" to key.second,
                    "gross" to g, "tax" to t, "net" to (g - t), "weight" to pct(g - t, net),
                )
            }.sortedByDescending { it["net"] as BigDecimal }

        val byCountry = records.groupBy { if (it.symbol?.matches(Regex("^[0-9]+$")) == true) "국내" else "해외" }
            .map { (country, rs) ->
                val g = rs.sum { it.gross }; val t = rs.sum { it.tax }
                mapOf(
                    "country" to country, "gross" to g, "tax" to t, "net" to (g - t),
                    "effectiveTaxRate" to pct(t, g),
                )
            }.sortedByDescending { it["gross"] as BigDecimal }

        val body = mapOf(
            "summary" to mapOf(
                "grossTotal" to gross, "withholdingTax" to tax, "netTotal" to net,
                "effectiveTaxRate" to pct(tax, gross), "receiptCount" to records.size,
                "ttmYield" to ttmYield,
            ),
            "receipts" to receipts,
            "monthly" to monthly,
            "bySymbol" to bySymbol,
            "byCountry" to byCountry,
        )
        val asOf = records.maxOfOrNull { it.payDate } ?: period.end
        return GeneratedReport(asOfDate = asOf, bodyJson = mapper.writeValueAsString(body))
    }

    private fun List<DividendRecord>.sum(sel: (DividendRecord) -> BigDecimal): BigDecimal =
        fold(BigDecimal.ZERO) { acc, r -> acc + sel(r) }

    /** a/b × 100, 0~100 스케일 (b<=0이면 0) */
    private fun pct(a: BigDecimal, b: BigDecimal): BigDecimal =
        if (b <= BigDecimal.ZERO) BigDecimal.ZERO
        else a.divide(b, mc).multiply(BigDecimal(100)).setScale(2, RoundingMode.HALF_UP)
}
```

- [ ] **Step 4 (GREEN 확인): 테스트 통과**

Run: `./gradlew :unified-asset:test --tests "*DividendInterestReportGeneratorTest*"`
Expected: 8개 테스트 전부 PASS.

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/DividendInterestReportGenerator.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/DividendInterestReportGeneratorTest.kt
git commit -m "feat(dividend): 배당·이자 생성 엔진 R-03 v1 (#32 프레임 등록, TDD)"
```

---

## Task 3: JDBC 어댑터

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/adapter/JdbcDividendLedgerSource.kt`

- [ ] **Step 1: 어댑터 작성**

`ua_stock_trades` JOIN `ua_accounts`로 DIVIDEND 행 조회. 컬럼: `traded_at·stock_name·symbol·total_amount·tax` + `account_name·provider`.

```kotlin
package com.allfolio.unifiedasset.infrastructure.adapter

import com.allfolio.unifiedasset.application.port.DividendLedgerSource
import com.allfolio.unifiedasset.application.port.DividendRecord
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

@Repository
class JdbcDividendLedgerSource(private val jdbc: JdbcTemplate) : DividendLedgerSource {

    override fun findDividends(userId: UUID, from: LocalDate, to: LocalDate): List<DividendRecord> =
        jdbc.query(
            """SELECT t.traded_at, t.stock_name, t.symbol, t.total_amount, t.tax,
                      a.account_name, a.provider
               FROM ua_stock_trades t
               JOIN ua_accounts a ON a.id = t.account_id
               WHERE t.user_id = ? AND t.trade_type = 'DIVIDEND'
                 AND t.traded_at >= ? AND t.traded_at <= ?
               ORDER BY t.traded_at ASC""",
            { rs, _ ->
                DividendRecord(
                    payDate = rs.getDate("traded_at").toLocalDate(),
                    stockName = rs.getString("stock_name"),
                    symbol = rs.getString("symbol"),
                    accountName = rs.getString("account_name"),
                    provider = rs.getString("provider"),
                    gross = rs.getBigDecimal("total_amount"),
                    tax = rs.getBigDecimal("tax"),
                )
            },
            userId, from, to,
        )
}
```

- [ ] **Step 2: 전체 모듈 컴파일 + 테스트 통과 확인**

Run: `./gradlew :unified-asset:test`
Expected: BUILD SUCCESSFUL (신규 생성기 테스트 포함 전부 통과).

- [ ] **Step 3: 커밋**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/infrastructure/adapter/JdbcDividendLedgerSource.kt
git commit -m "feat(dividend): 배당 원장 JDBC 어댑터 — ua_stock_trades JOIN ua_accounts (R1 #38)"
```

---

## Task 4: 스모크 검증

**Files:** (없음 — 검증 전용)

- [ ] **Step 1: 로컬 기동 준비**

`GenerateReportUseCase`가 `DividendInterestReportGenerator`를 스프링 빈으로 자동 수집하는지 확인(같은 컴포넌트 스캔 범위). 앱 기동 시 "리포트 타입당 생성기는 하나여야 합니다" require 통과해야 함(DIVIDEND_INTEREST 생성기 중복 없음).

- [ ] **Step 2: 생성·조회 스모크**

로컬 기동 → `ua_stock_trades`에 DIVIDEND 행 시드(`total_amount`·`tax`·`traded_at`, 국내 숫자티커 1건 + 해외 영문티커 1건) → `POST /api/reports/archive/generate {type:"DIVIDEND_INTEREST", year:2026, month:6}` → 반환 메타 확인 → `GET /api/reports/archive/{id}` 본문 검산:
- `summary.grossTotal / withholdingTax / netTotal / effectiveTaxRate` 정확
- `receipts` 행별 `net = gross - tax`
- `byCountry`에 국내·해외 버킷
- 재생성 시 upsert(동일 기간 중복 아카이브 아님)

- [ ] **Step 3: 배당 0건 스모크**

배당 없는 유저로 `generate` → 400이 아닌 정상 200 + 빈 배열/0 요약 확인(예외 없음).

- [ ] **Step 4: 정리**

시드 데이터 정리. 검증 중 수정 있었으면 커밋, 없으면 이 태스크는 커밋 없이 완료.

---

## 완료 기준

- `POST /api/reports/archive/generate {type: DIVIDEND_INTEREST}` 동작, 본문 5키(summary·receipts·monthly·bySymbol·byCountry)
- 세전·원천징수·세후·실효세율·TTM수익률 정확, 국가 근사 버킷팅
- 배당 0건 → 예외 없는 유효 보고서
- `./gradlew :unified-asset:test` 통과, 기존 `DividendReportService`/live 화면 영향 없음
- FE 화면(SCR-RPT-05)은 #38 2단계 별도
