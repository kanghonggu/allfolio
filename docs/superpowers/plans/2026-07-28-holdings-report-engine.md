# 월말 보유 명세서 생성 엔진 (R-05) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `HoldingsReportGenerator`(type=HOLDINGS)를 #32 프레임에 등록 — `ua_assets` 보유 자산의 종목별 명세(수량·평단·평가액·평가손익)와 계좌/자산군 소계를 집계한 본문 JSON을 생성해 아카이브한다.

**Architecture:** 헥사고날. `MonthlyReportGenerator`(#36)와 동일하게 기존 `AssetRepository`·`AccountRepository`·`FxConverter`를 재사용하는 순수 집계 생성기 — 신규 포트/JDBC 어댑터 없음. 자산 0건은 예외가 아닌 유효한 0 보고서.

**Tech Stack:** Kotlin/Spring · 기존 포트·NavCalculator 확장 재사용 · 신규 DDL 없음.

**Spec:** `docs/superpowers/specs/2026-07-28-holdings-report-engine-design.md`

**테스트 명령:** `./gradlew :unified-asset:test --tests "*HoldingsReportGeneratorTest*"` (전체: `./gradlew :unified-asset:test`)

---

## File Structure

- Create `unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/HoldingsReportGenerator.kt` — 생성기
- Create `unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/HoldingsReportGeneratorTest.kt` — 단위 테스트

경로 접두사: `allfolio-backend/`

---

## Task 1: HoldingsReportGenerator (TDD)

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/HoldingsReportGenerator.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/HoldingsReportGeneratorTest.kt`

- [ ] **Step 1 (RED): 테스트 작성**

`MonthlyReportGeneratorTest`와 동일한 fake 포트/팩토리. `currentValueInKrw`/`unrealizedPnlInKrw`는 동일 패키지(`application.usecase`) 확장이라 import 불필요.

```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountStatus
import com.allfolio.unifiedasset.domain.account.AccountType
import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.asset.AssetCategory
import com.allfolio.unifiedasset.domain.asset.AssetSourceType
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.domain.asset.ValuationMethod
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class HoldingsReportGeneratorTest {

    private val userId = UUID.randomUUID()
    private val acctA = UUID.randomUUID()
    private val acctB = UUID.randomUUID()
    private val period = ReportPeriod.monthly(2026, 6)
    private val mapper = jacksonObjectMapper()

    private class FakeAssetRepo(private val assets: List<Asset>) : AssetRepository {
        override fun save(asset: Asset) = asset
        override fun saveAll(assets: List<Asset>) = assets
        override fun findById(id: UUID): Asset? = null
        override fun findByAccountId(accountId: UUID) = assets
        override fun findByUserId(userId: UUID) = assets
        override fun deleteByAccountId(accountId: UUID) {}
        override fun delete(id: UUID) {}
    }
    private class FakeAccountRepo(private val accounts: List<Account>) : AccountRepository {
        override fun save(account: Account) = account
        override fun findById(id: UUID): Account? = null
        override fun findByUserId(userId: UUID) = accounts
        override fun findByProviders(providers: Collection<AccountProvider>) = emptyList<Account>()
        override fun delete(id: UUID) {}
        override fun updateStatus(id: UUID, status: AccountStatus) {}
    }
    private val fx = object : FxConverter {
        override fun toKrw(amount: BigDecimal, currency: String): BigDecimal =
            if (currency.uppercase() == "KRW") amount else amount * BigDecimal("1000")
    }

    private fun asset(accountId: UUID, name: String, type: AssetType, qty: String, purchase: String, current: String, currency: String = "KRW") =
        Asset.create(
            userId = userId, accountId = accountId, category = AssetCategory.FINANCIAL,
            type = type, sourceType = AssetSourceType.STOCK_API, name = name, symbol = name,
            quantity = BigDecimal(qty), purchasePrice = BigDecimal(purchase),
            currentValue = BigDecimal(current), currency = currency,
            valuationMethod = ValuationMethod.MARKET_PRICE,
        )
    private fun account(id: UUID, name: String, provider: AccountProvider) = Account.reconstruct(
        id = id, userId = userId, provider = provider, accountType = AccountType.STOCK,
        accountName = name, externalId = null, currency = "KRW", status = AccountStatus.ACTIVE,
        lastSyncedAt = null, createdAt = LocalDateTime.now(), apiKey = null, apiSecret = null,
        walletAddress = null, chain = null,
    )

    private fun generator(assets: List<Asset>, accounts: List<Account>) =
        HoldingsReportGenerator(FakeAssetRepo(assets), FakeAccountRepo(accounts), fx)

    // 삼성전자 KRW: 취득 7M, 평가 8M → pnl 1M · Apple USD(×1000): 취득 4000→4M, 평가 5000→5M, pnl 1M · 현금 3M pnl 0
    private fun standardAssets() = listOf(
        asset(acctA, "삼성전자", AssetType.STOCK, "1", "7000000", "8000000"),
        asset(acctA, "Apple", AssetType.STOCK, "1", "4000", "5000", "USD"),
        asset(acctB, "원화예수금", AssetType.CASH, "1", "3000000", "3000000"),
    )
    private fun standardAccounts() = listOf(
        account(acctA, "한국투자", AccountProvider.KIS),
        account(acctB, "은행", AccountProvider.MANUAL),
    )

    @Test
    fun `summary aggregates total value count pnl cashWeight`() {
        val body = mapper.readTree(generator(standardAssets(), standardAccounts()).generate(userId, period).bodyJson)
        val s = body["summary"]
        assertEquals(16000000.0, s["totalValueKrw"].asDouble(), 0.01)
        assertEquals(3, s["holdingCount"].asInt())
        assertEquals(2, s["accountCount"].asInt())
        assertEquals(2000000.0, s["unrealizedPnlKrw"].asDouble(), 0.01)
        assertEquals(18.75, s["cashWeight"].asDouble(), 0.01)
    }

    @Test
    fun `holdings sorted by valueKrw desc with fields`() {
        val body = mapper.readTree(generator(standardAssets(), standardAccounts()).generate(userId, period).bodyJson)
        val h = body["holdings"]
        assertEquals(3, h.size())
        assertEquals("삼성전자", h[0]["name"].asText())
        assertEquals(8000000.0, h[0]["valueKrw"].asDouble(), 0.01)
        assertEquals(1000000.0, h[0]["unrealizedPnl"].asDouble(), 0.01)
        assertEquals(14.29, h[0]["returnRate"].asDouble(), 0.01)  // 1M/7M×100
    }

    @Test
    fun `byType groups with weights summing to 100`() {
        val body = mapper.readTree(generator(standardAssets(), standardAccounts()).generate(userId, period).bodyJson)
        val types = body["byType"].associate { it["type"].asText() to it }
        assertEquals(13000000.0, types["STOCK"]!!["valueKrw"].asDouble(), 0.01)
        assertEquals(2, types["STOCK"]!!["holdingCount"].asInt())
        assertEquals(3000000.0, types["CASH"]!!["valueKrw"].asDouble(), 0.01)
        assertEquals(100.0, body["byType"].sumOf { it["weight"].asDouble() }, 0.1)
    }

    @Test
    fun `byAccount subtotals with weight`() {
        val body = mapper.readTree(generator(standardAssets(), standardAccounts()).generate(userId, period).bodyJson)
        val kis = body["byAccount"].first { it["account"].asText() == "한국투자" }
        assertEquals(13000000.0, kis["valueKrw"].asDouble(), 0.01)
        assertEquals(2, kis["holdingCount"].asInt())
        assertEquals(81.25, kis["weight"].asDouble(), 0.01)
    }

    @Test
    fun `cash section lists CASH assets`() {
        val body = mapper.readTree(generator(standardAssets(), standardAccounts()).generate(userId, period).bodyJson)
        assertEquals(1, body["cash"].size())
        assertEquals(3000000.0, body["cash"][0]["valueKrw"].asDouble(), 0.01)
        assertEquals("KRW", body["cash"][0]["currency"].asText())
    }

    @Test
    fun `zero assets yields valid empty report`() {
        val generated = generator(emptyList(), emptyList()).generate(userId, period)
        assertEquals(LocalDate.of(2026, 6, 30), generated.asOfDate)
        val body = mapper.readTree(generated.bodyJson)
        assertEquals(0.0, body["summary"]["totalValueKrw"].asDouble(), 0.01)
        assertEquals(0, body["holdings"].size())
        assertEquals(0.0, body["summary"]["cashWeight"].asDouble(), 0.01)
    }
}
```

- [ ] **Step 2 (RED 확인)**

Run: `./gradlew :unified-asset:test --tests "*HoldingsReportGeneratorTest*"`
Expected: 컴파일 실패(`HoldingsReportGenerator` 미존재).

- [ ] **Step 3 (GREEN): 생성기 구현**

```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.application.GeneratedReport
import com.allfolio.report.application.ReportBodyGenerator
import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.report.domain.archive.ReportType
import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.UUID

/**
 * R-05 월말 보유 명세서 생성 엔진 (R2 #40 BE).
 * ua_assets 보유 자산의 종목별 명세(수량·평단·평가액·평가손익)와 계좌/자산군 소계를 본문에 고정.
 * 월말 스냅샷 히스토리 부재 → 생성 시점 보유 기준(asOf=period.end). 자산 0건은 예외 없는 유효 0 보고서.
 * v1 제외: 당월 실현손익(FIFO), 월간 변동 diff, 지역별 그룹핑.
 */
@Component
class HoldingsReportGenerator(
    private val assetRepository: AssetRepository,
    private val accountRepository: AccountRepository,
    private val fx: FxConverter,
) : ReportBodyGenerator {

    override val type = ReportType.HOLDINGS

    private val mapper = jacksonObjectMapper()
    private val mc = MathContext(10, RoundingMode.HALF_UP)

    override fun generate(userId: UUID, period: ReportPeriod): GeneratedReport {
        val assets = assetRepository.findByUserId(userId)
        val accounts = accountRepository.findByUserId(userId)
        val labels = accounts.associate { it.id to Pair(it.accountName, it.provider.name) }

        val valued = assets.map { it to it.currentValueInKrw(fx) }
        val totalKrw = valued.fold(BigDecimal.ZERO) { acc, (_, v) -> acc + v }

        val holdings = valued.sortedByDescending { it.second }.map { (a, valueKrw) ->
            val (accName, provider) = labels[a.accountId] ?: Pair("-", "-")
            mapOf(
                "name" to a.name, "symbol" to a.symbol, "type" to a.type.name,
                "account" to accName, "provider" to provider,
                "quantity" to a.quantity, "avgPrice" to a.purchasePrice,
                "currentValue" to a.currentValue, "valueKrw" to valueKrw,
                "weight" to pct(valueKrw, totalKrw),
                "unrealizedPnl" to a.unrealizedPnlInKrw(fx), "returnRate" to a.returnRate(),
            )
        }

        val byAccount = valued.groupBy { it.first.accountId }.map { (accId, g) ->
            val sum = g.fold(BigDecimal.ZERO) { acc, (_, v) -> acc + v }
            val (accName, provider) = labels[accId] ?: Pair("-", "-")
            mapOf(
                "account" to accName, "provider" to provider, "valueKrw" to sum,
                "weight" to pct(sum, totalKrw), "holdingCount" to g.size,
            )
        }.sortedByDescending { it["valueKrw"] as BigDecimal }

        val byType = valued.groupBy { it.first.type }.map { (t, g) ->
            val sum = g.fold(BigDecimal.ZERO) { acc, (_, v) -> acc + v }
            mapOf("type" to t.name, "valueKrw" to sum, "weight" to pct(sum, totalKrw), "holdingCount" to g.size)
        }.sortedByDescending { it["valueKrw"] as BigDecimal }

        val cashValued = valued.filter { it.first.type == AssetType.CASH }
        val cashKrw = cashValued.fold(BigDecimal.ZERO) { acc, (_, v) -> acc + v }
        val cash = cashValued.map { (a, valueKrw) ->
            val (accName, _) = labels[a.accountId] ?: Pair("-", "-")
            mapOf("account" to accName, "currency" to a.currency, "valueKrw" to valueKrw)
        }

        val unrealizedTotal = assets.fold(BigDecimal.ZERO) { acc, a -> acc + a.unrealizedPnlInKrw(fx) }

        val body = mapOf(
            "summary" to mapOf(
                "totalValueKrw" to totalKrw, "holdingCount" to assets.size,
                "accountCount" to accounts.size, "cashWeight" to pct(cashKrw, totalKrw),
                "unrealizedPnlKrw" to unrealizedTotal,
            ),
            "holdings" to holdings,
            "byAccount" to byAccount,
            "byType" to byType,
            "cash" to cash,
            "note" to "보유·평가액은 보고서 생성 시점 기준",
        )
        return GeneratedReport(asOfDate = period.end, bodyJson = mapper.writeValueAsString(body))
    }

    /** a/b × 100, 0~100 스케일 (b<=0이면 0) */
    private fun pct(a: BigDecimal, b: BigDecimal): BigDecimal =
        if (b <= BigDecimal.ZERO) BigDecimal.ZERO
        else a.divide(b, mc).multiply(BigDecimal(100), mc).setScale(2, RoundingMode.HALF_UP)
}
```

- [ ] **Step 4 (GREEN 확인)**

Run: `./gradlew :unified-asset:test --tests "*HoldingsReportGeneratorTest*"`
Expected: 6개 테스트 전부 PASS. 실패 시 원인 수정(테스트 약화 금지).

- [ ] **Step 5: 전체 모듈 테스트 + 커밋**

Run: `./gradlew :unified-asset:test` (Expected: BUILD SUCCESSFUL — 기존 테스트 포함 전부 통과)

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/HoldingsReportGenerator.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/HoldingsReportGeneratorTest.kt
git commit -m "feat(holdings): 월말 보유 명세서 생성 엔진 R-05 v1 (#32 프레임 등록, TDD)"
```

---

## Task 2: 스모크 검증

**Files:** (없음 — 검증 전용)

- [ ] **Step 1: 빈 스캔 확인**

앱 기동 시 `GenerateReportUseCase`의 "리포트 타입당 생성기는 하나여야 합니다" require 통과(HOLDINGS 생성기 중복 없음).

- [ ] **Step 2: 생성·조회 스모크**

로컬 기동 → `ua_assets` 시드(주식 KRW·주식 USD·현금 CASH 혼재, 계좌 2개) → `POST /api/reports/archive/generate {type:"HOLDINGS", year:2026, month:6}` → `GET /api/reports/archive/{id}` 본문 검산:
- `summary.totalValueKrw` = Σ KRW 환산, `unrealizedPnlKrw` = Σ 평가손익
- `holdings` valueKrw 내림차순, 원통화 평가액·평단·수익률(0~100)
- `byAccount`/`byType` 소계·비중, `cash`에 CASH 자산, `cashWeight`
- USD 자산 KRW 환산 반영, 재생성 upsert

- [ ] **Step 3: 자산 0건 스모크**

자산 없는 유저 → `generate` → 400 아닌 정상 + 빈 배열/0 요약(예외 없음).

- [ ] **Step 4: 정리** — 시드 정리. 수정 있었으면 커밋.

---

## 완료 기준

- `POST /api/reports/archive/generate {type: HOLDINGS}` 동작, 본문 5키(summary·holdings·byAccount·byType·cash)
- 종목 명세·소계·현금비중·평가손익 정확, USD 환산, returnRate 0~100 스케일
- 자산 0건 → 예외 없는 유효 보고서
- `./gradlew :unified-asset:test` 통과, 기존 리포트 영향 없음
- FE 화면(SCR-RPT-08)은 #40 2단계 별도
