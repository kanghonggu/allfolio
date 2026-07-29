# 투자배제·ESG 스크리닝 생성 엔진 (R-07) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `EsgScreeningReportGenerator`(type=ESG_SCREENING)를 #32 프레임에 등록 — 기존 `EsgEngine` 재사용한 ESG 스코어 + 코드 내장 프리셋 기반 배제 스크리닝을 본문 JSON으로 생성해 아카이브한다.

**Architecture:** 헥사고날. 기존 `AssetRepository`·`FxConverter` + `EsgEngine`(esg 모듈 object) 재사용, 신규 포트/DDL 없음. 배제 프리셋은 코드 정의. 자산 0(또는 총평가액 0)은 `EsgEngine.calculate` 예외를 피해 유효한 0 보고서.

**Tech Stack:** Kotlin/Spring · 기존 포트·EsgEngine 재사용 · 신규 DDL 없음. (unified-asset는 이미 esg 모듈 의존 — EsgReportService 선례)

**Spec:** `docs/superpowers/specs/2026-07-28-esg-screening-engine-design.md`

**테스트 명령:** `./gradlew :unified-asset:test --tests "*EsgScreeningReportGeneratorTest*"` (전체: `./gradlew :unified-asset:test`)

---

## File Structure

- Create `unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/EsgExclusionPreset.kt`
- Create `unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/EsgScreeningReportGenerator.kt`
- Create `unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/EsgScreeningReportGeneratorTest.kt`

경로 접두사: `allfolio-backend/`

---

## Task 1: 배제 프리셋

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/EsgExclusionPreset.kt`

- [ ] **Step 1: 프리셋 작성**

```kotlin
package com.allfolio.unifiedasset.application.usecase

/** 배제 사유 1건. */
data class ExclusionEntry(val listName: String, val reason: String)

/**
 * v1 내장 배제 프리셋 — 심볼 → 배제 정보 (R2 #42).
 * 실제 회사를 배제로 단정하지 않도록 예시(placeholder) 심볼로만 시드한다.
 * 실제 배제리스트 큐레이션·사용자 리스트·CSV 반입은 후속(SCR-RPT-11).
 */
object EsgExclusionPreset {
    val entries: Map<String, ExclusionEntry> = mapOf(
        "EXCL-COAL-01" to ExclusionEntry("예시 프리셋", "석탄"),
        "EXCL-WEAPON-01" to ExclusionEntry("예시 프리셋", "논란무기"),
    )

    fun lookup(symbol: String?): ExclusionEntry? = symbol?.let { entries[it] }
}
```

- [ ] **Step 2: 컴파일 + 커밋**

Run: `./gradlew :unified-asset:compileKotlin` (Expected: BUILD SUCCESSFUL)
```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/EsgExclusionPreset.kt
git commit -m "feat(esg): 투자배제 v1 내장 프리셋 (예시 심볼) (R2 #42)"
```

---

## Task 2: EsgScreeningReportGenerator (TDD)

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/EsgScreeningReportGenerator.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/EsgScreeningReportGeneratorTest.kt`

- [ ] **Step 1 (RED): 테스트 작성**

```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.unifiedasset.application.port.AssetRepository
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

class EsgScreeningReportGeneratorTest {

    private val userId = UUID.randomUUID()
    private val acctId = UUID.randomUUID()
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
    private val fx = object : FxConverter {
        override fun toKrw(amount: BigDecimal, currency: String): BigDecimal =
            if (currency.uppercase() == "KRW") amount else amount * BigDecimal("1000")
    }

    private fun asset(name: String, symbol: String, current: String, currency: String = "KRW", type: AssetType = AssetType.STOCK) =
        Asset.create(
            userId = userId, accountId = acctId, category = AssetCategory.FINANCIAL,
            type = type, sourceType = AssetSourceType.STOCK_API, name = name, symbol = symbol,
            quantity = BigDecimal.ONE, purchasePrice = BigDecimal(current), currentValue = BigDecimal(current),
            currency = currency, valuationMethod = ValuationMethod.MARKET_PRICE,
        )

    private fun generator(assets: List<Asset>) = EsgScreeningReportGenerator(FakeAssetRepo(assets), fx)

    // 삼성 8M(KRW) · Apple 5000 USD(×1000=5M) · 배제심볼 2M(KRW) → 총 15M, 전부 STOCK
    private fun standardAssets() = listOf(
        asset("삼성전자", "005930", "8000000"),
        asset("Apple", "AAPL", "5000", "USD"),
        asset("석탄기업", "EXCL-COAL-01", "2000000"),
    )

    @Test
    fun `esg score reuses EsgEngine`() {
        val body = mapper.readTree(generator(standardAssets()).generate(userId, period).bodyJson)
        val esg = body["esg"]
        // 전부 STOCK → E60 S65 G65, total = 60*.35 + 65*.30 + 65*.35 = 63.25 → rating B
        assertEquals(63.25, esg["totalScore"].asDouble(), 0.01)
        assertEquals("B", esg["rating"].asText())
        assertEquals(60.0, esg["environmental"].asDouble(), 0.01)
        assertEquals(65.0, esg["social"].asDouble(), 0.01)
        assertEquals(3, body["esgBreakdown"].size())
        assertEquals("삼성전자", body["esgBreakdown"][0]["name"].asText())  // 8M 최상위
    }

    @Test
    fun `breakdown weight is 0 to 100 scale`() {
        val body = mapper.readTree(generator(standardAssets()).generate(userId, period).bodyJson)
        val bd = body["esgBreakdown"]
        assertEquals(53.33, bd[0]["weight"].asDouble(), 0.01)  // 8M/15M
        assertEquals(100.0, bd.sumOf { it["weight"].asDouble() }, 0.1)
    }

    @Test
    fun `screening flags preset symbol as violation`() {
        val body = mapper.readTree(generator(standardAssets()).generate(userId, period).bodyJson)
        assertEquals(1, body["screening"]["violationCount"].asInt())
        assertEquals(2000000.0, body["screening"]["violationValueKrw"].asDouble(), 0.01)
        assertEquals(13.33, body["screening"]["violationWeight"].asDouble(), 0.01)  // 2M/15M
        val v = body["violations"][0]
        assertEquals("EXCL-COAL-01", v["symbol"].asText())
        assertEquals("석탄", v["reason"].asText())
        assertEquals(2000000.0, v["valueKrw"].asDouble(), 0.01)
    }

    @Test
    fun `no violation when no preset symbol held`() {
        val body = mapper.readTree(generator(listOf(asset("삼성전자", "005930", "8000000"))).generate(userId, period).bodyJson)
        assertEquals(0, body["screening"]["violationCount"].asInt())
        assertEquals(0, body["violations"].size())
        assertEquals(0.0, body["screening"]["violationWeight"].asDouble(), 0.01)
    }

    @Test
    fun `usd asset converted for violation value`() {
        // 배제 심볼이 USD → ×1000 환산 반영
        val body = mapper.readTree(generator(listOf(asset("해외석탄", "EXCL-COAL-01", "2000", "USD"))).generate(userId, period).bodyJson)
        assertEquals(2000000.0, body["screening"]["violationValueKrw"].asDouble(), 0.01)
    }

    @Test
    fun `empty assets yields valid zero report`() {
        val generated = generator(emptyList()).generate(userId, period)
        assertEquals(LocalDate.of(2026, 6, 30), generated.asOfDate)
        val body = mapper.readTree(generated.bodyJson)
        assertEquals(0.0, body["esg"]["totalScore"].asDouble(), 0.01)
        assertEquals(0, body["esgBreakdown"].size())
        assertEquals(0, body["screening"]["violationCount"].asInt())
        assertTrue(body["violations"].isEmpty)
    }
}
```

- [ ] **Step 2 (RED 확인)**

Run: `./gradlew :unified-asset:test --tests "*EsgScreeningReportGeneratorTest*"`
Expected: 컴파일 실패(`EsgScreeningReportGenerator` 미존재).

- [ ] **Step 3 (GREEN): 생성기 구현**

```kotlin
package com.allfolio.unifiedasset.application.usecase

import com.allfolio.esg.domain.EsgEngine
import com.allfolio.report.application.GeneratedReport
import com.allfolio.report.application.ReportBodyGenerator
import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.report.domain.archive.ReportType
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.UUID

/**
 * R-07 투자배제·ESG 스크리닝 생성 엔진 (R2 #42 BE).
 * 기존 EsgEngine 재사용 ESG 스코어(자산유형 기반) + 코드 내장 프리셋 기반 배제 스크리닝.
 * 총평가액 0(자산 없음)은 EsgEngine.calculate 예외를 피해 유효한 0 보고서.
 * v1 제외: 사용자 배제리스트·관리(SCR-RPT-11), 위반 이력·감시로그·편입일, 국가/ISIN 매칭.
 */
@Component
class EsgScreeningReportGenerator(
    private val assetRepository: AssetRepository,
    private val fx: FxConverter,
) : ReportBodyGenerator {

    override val type = ReportType.ESG_SCREENING

    private val mapper = jacksonObjectMapper()
    private val mc = MathContext(10, RoundingMode.HALF_UP)

    override fun generate(userId: UUID, period: ReportPeriod): GeneratedReport {
        val valued = assetRepository.findByUserId(userId).map { it to it.currentValueInKrw(fx) }
        val totalKrw = valued.fold(BigDecimal.ZERO) { a, (_, v) -> a + v }

        val body: Map<String, Any?> = if (totalKrw <= BigDecimal.ZERO) emptyReport() else {
            val score = EsgEngine.calculate(valued.map { (a, v) -> EsgEngine.AssetInput(a.type.name, v) })

            val breakdown = valued.sortedByDescending { it.second }.map { (a, v) ->
                val (e, s, g) = EsgEngine.scoreOf(a.type.name)
                val assetTotal = BigDecimal(e).multiply(BigDecimal("0.35"))
                    .add(BigDecimal(s).multiply(BigDecimal("0.30")))
                    .add(BigDecimal(g).multiply(BigDecimal("0.35")))
                    .setScale(2, RoundingMode.HALF_UP)
                mapOf(
                    "name" to a.name, "type" to a.type.name, "weight" to pct(v, totalKrw),
                    "e" to e, "s" to s, "g" to g, "total" to assetTotal, "rating" to EsgEngine.rating(assetTotal),
                )
            }

            val violated = valued.mapNotNull { (a, v) ->
                EsgExclusionPreset.lookup(a.symbol)?.let { ex -> Triple(a, v, ex) }
            }.sortedByDescending { it.second }
            val violationValueKrw = violated.fold(BigDecimal.ZERO) { acc, t -> acc + t.second }
            val violations = violated.map { (a, v, ex) ->
                mapOf("name" to a.name, "symbol" to a.symbol, "listName" to ex.listName,
                    "reason" to ex.reason, "valueKrw" to v, "weight" to pct(v, totalKrw))
            }

            mapOf(
                "esg" to mapOf(
                    "rating" to score.rating, "totalScore" to score.total,
                    "environmental" to score.environmental, "social" to score.social, "governance" to score.governance,
                ),
                "esgBreakdown" to breakdown,
                "screening" to mapOf(
                    "violationCount" to violated.size, "violationValueKrw" to violationValueKrw,
                    "violationWeight" to pct(violationValueKrw, totalKrw),
                ),
                "violations" to violations,
                "note" to NOTE,
            )
        }
        return GeneratedReport(asOfDate = period.end, bodyJson = mapper.writeValueAsString(body))
    }

    private fun emptyReport(): Map<String, Any?> = mapOf(
        "esg" to mapOf("rating" to "-", "totalScore" to BigDecimal.ZERO,
            "environmental" to BigDecimal.ZERO, "social" to BigDecimal.ZERO, "governance" to BigDecimal.ZERO),
        "esgBreakdown" to emptyList<Any>(),
        "screening" to mapOf("violationCount" to 0, "violationValueKrw" to BigDecimal.ZERO, "violationWeight" to BigDecimal.ZERO),
        "violations" to emptyList<Any>(),
        "note" to NOTE,
    )

    private fun pct(a: BigDecimal, b: BigDecimal): BigDecimal =
        if (b <= BigDecimal.ZERO) BigDecimal.ZERO
        else a.divide(b, mc).multiply(BigDecimal(100), mc).setScale(2, RoundingMode.HALF_UP)

    companion object { private const val NOTE = "ESG 점수는 자산유형 기반 · 배제는 v1 내장 프리셋 기준" }
}
```

- [ ] **Step 4 (GREEN 확인)**

Run: `./gradlew :unified-asset:test --tests "*EsgScreeningReportGeneratorTest*"`
Expected: 6개 테스트 전부 PASS. 실패 시 원인 수정(테스트 약화 금지).

- [ ] **Step 5: 전체 모듈 테스트 + 커밋**

Run: `./gradlew :unified-asset:test` (Expected: BUILD SUCCESSFUL — 기존 ESG/리포트 테스트 포함 전부 통과)
```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/EsgScreeningReportGenerator.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/EsgScreeningReportGeneratorTest.kt
git commit -m "feat(esg): 투자배제·ESG 스크리닝 생성 엔진 R-07 v1 (#32 프레임 등록, TDD)"
```

---

## Task 3: 스모크 검증

**Files:** (없음 — 검증 전용)

- [ ] **Step 1: 빈 스캔 확인** — 앱 기동 시 "리포트 타입당 생성기는 하나여야 합니다" require 통과(ESG_SCREENING 중복 없음).

- [ ] **Step 2: 생성·조회 스모크**

로컬 기동 → 자산 시드(일반 종목 + 프리셋 심볼 `EXCL-COAL-01` 1건) → `POST /api/reports/archive/generate {type:"ESG_SCREENING", year:2026, month:6}` → `GET /api/reports/archive/{id}` 본문 검산:
- `esg` 점수(EsgEngine 결과)·`esgBreakdown` 종목별·weight 0~100
- `screening.violationCount=1`·`violationValueKrw`·`violationWeight`, `violations`에 `EXCL-COAL-01`
- 재생성 upsert

- [ ] **Step 3: 0건 스모크** — 자산 없는 유저 → `generate` → 400 아닌 정상 + ESG 0·빈 배열(예외 없음).

- [ ] **Step 4: 정리** — 시드 정리. 수정 있었으면 커밋.

---

## 완료 기준

- `POST /api/reports/archive/generate {type: ESG_SCREENING}` 동작, 본문 4키(esg·esgBreakdown·screening·violations)
- ESG 점수 EsgEngine 재사용·종목별 breakdown·weight 0~100, 프리셋 심볼 보유 시 위반·미보유 시 0
- 자산 0건 → 예외 없는 유효 보고서
- `./gradlew :unified-asset:test` 통과, 기존 ESG/리포트 영향 없음
- FE 화면(SCR-RPT-10)은 #42 2단계 별도
