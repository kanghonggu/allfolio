# R-03 세율마스터 Phase B — 기대세율 비교 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 배당 보고서(R-03) `byCountry`에 실효 원천징수율 vs 기준(기대) 세율 대조(국내 KR, ±0.5%p 초과 시 ⚠)를 추가한다.

**Architecture:** 순수 `ExpectedTaxComparison`(국가→ISO 매핑 + 편차/플래그) + `DividendInterestReportGenerator`가 `TaxRateRepository.findEffective`로 기준율 조회 후 byCountry 각 행에 대조 필드 직렬화. FE ByCountryTable 열 확장.

**Tech Stack:** Kotlin/Spring(unified-asset), JUnit, Next.js/React/TypeScript.

Spec: `docs/superpowers/specs/2026-07-31-tax-expected-comparison-design.md`

---

## Task 1: ExpectedTaxComparison (순수 계산기 + 테스트)

**Files:**
- Create: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/ExpectedTaxComparison.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/ExpectedTaxComparisonTest.kt`

- [ ] **Step 1: 실패 테스트 작성** — `ExpectedTaxComparisonTest.kt`:

```kotlin
package com.allfolio.unifiedasset.application.usecase

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ExpectedTaxComparisonTest {
    private fun bd(s: String) = BigDecimal(s)

    @Test
    fun `isoOf는 국내만 KR로 매핑하고 그 외는 null`() {
        assertThat(ExpectedTaxComparison.isoOf("국내")).isEqualTo("KR")
        assertThat(ExpectedTaxComparison.isoOf("해외")).isNull()
        assertThat(ExpectedTaxComparison.isoOf("기타")).isNull()
    }

    @Test
    fun `일치하면 편차 0 flag false`() {
        val r = ExpectedTaxComparison.compare(bd("15.40"), bd("15.4"))
        assertThat(r.expectedRate).isEqualByComparingTo("15.4")
        assertThat(r.deviationPp).isEqualByComparingTo("0.00")
        assertThat(r.flagged).isFalse
    }

    @Test
    fun `0_5%p 초과면 flag true`() {
        val r = ExpectedTaxComparison.compare(bd("20.00"), bd("15.4"))
        assertThat(r.deviationPp).isEqualByComparingTo("4.60")
        assertThat(r.flagged).isTrue
    }

    @Test
    fun `0_5%p 경계 이하면 flag false`() {
        val r = ExpectedTaxComparison.compare(bd("15.80"), bd("15.4"))  // 편차 0.40
        assertThat(r.deviationPp).isEqualByComparingTo("0.40")
        assertThat(r.flagged).isFalse
        val r2 = ExpectedTaxComparison.compare(bd("15.90"), bd("15.4")) // 편차 0.50 (경계, >0.5 아님)
        assertThat(r2.deviationPp).isEqualByComparingTo("0.50")
        assertThat(r2.flagged).isFalse
    }

    @Test
    fun `기대율 null이면 대조 생략`() {
        val r = ExpectedTaxComparison.compare(bd("15.40"), null)
        assertThat(r.expectedRate).isNull()
        assertThat(r.deviationPp).isNull()
        assertThat(r.flagged).isFalse
    }
}
```

- [ ] **Step 2: 테스트 실패 확인** — Run: `cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :unified-asset:test --tests "*ExpectedTaxComparisonTest*"` → Expected: compile FAIL.

- [ ] **Step 3: 최소 구현** — `ExpectedTaxComparison.kt`:

```kotlin
package com.allfolio.unifiedasset.application.usecase

import java.math.BigDecimal
import java.math.RoundingMode

data class TaxComparison(val expectedRate: BigDecimal?, val deviationPp: BigDecimal?, val flagged: Boolean)

/** 배당 실효 원천징수율 vs 기준(기대) 세율 대조(사실형 정합 체크). 조언 아님. */
object ExpectedTaxComparison {
    private val THRESHOLD_PP = BigDecimal("0.5")

    /** byCountry 라벨 → ISO2. "국내"만 KR로 신뢰 매핑, 그 외는 국가 판별 불가 → null. */
    fun isoOf(countryLabel: String): String? = if (countryLabel == "국내") "KR" else null

    /** 실효세율 vs 기대율(둘 다 % 스케일). 기대율 null이면 대조 생략. */
    fun compare(actualEffRate: BigDecimal, expectedRate: BigDecimal?): TaxComparison {
        if (expectedRate == null) return TaxComparison(null, null, false)
        val dev = actualEffRate.subtract(expectedRate).setScale(2, RoundingMode.HALF_UP)
        return TaxComparison(expectedRate, dev, dev.abs() > THRESHOLD_PP)
    }
}
```

- [ ] **Step 4: 테스트 통과 확인** — Run: `cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :unified-asset:test --tests "*ExpectedTaxComparisonTest*"` → Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/ExpectedTaxComparison.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/ExpectedTaxComparisonTest.kt
git commit -m "feat(tax): add ExpectedTaxComparison calculator (TDD)"
```

---

## Task 2: DividendInterestReportGenerator 통합 + 테스트

**Files:**
- Modify: `allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/DividendInterestReportGenerator.kt`
- Test: `allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/DividendInterestReportGeneratorTest.kt`

- [ ] **Step 1: 실패 테스트 확장** — 먼저 테스트 파일을 읽어 셋업 파악(JUnit5 `assertEquals` 스타일). `TaxRateRepository` fake를 추가하고 `generator()` 헬퍼를 4-arg로 갱신:

```kotlin
    // import 추가:
    // import com.allfolio.unifiedasset.application.port.TaxRateRepository
    // import com.allfolio.unifiedasset.domain.tax.IncomeType
    // import com.allfolio.unifiedasset.domain.tax.TaxRate
    // import java.time.LocalDateTime

    private class FakeTaxRateRepo(private val krDividendRate: BigDecimal?) : TaxRateRepository {
        override fun findAll(): List<TaxRate> = emptyList()
        override fun findOpen(country: String, incomeType: IncomeType): TaxRate? = null
        override fun findEffective(country: String, incomeType: IncomeType, date: LocalDate): TaxRate? =
            if (country == "KR" && incomeType == IncomeType.DIVIDEND && krDividendRate != null)
                TaxRate(UUID.randomUUID(), "KR", IncomeType.DIVIDEND, krDividendRate,
                    LocalDate.of(2000,1,1), null, null, LocalDateTime.now(), LocalDateTime.now())
            else null
        override fun save(taxRate: TaxRate): TaxRate = taxRate
    }
```
그리고 기존 `generator(...)` 헬퍼 시그니처에 `taxRate: BigDecimal? = BigDecimal("15.4")` 파라미터를 추가하고 생성자 호출을 `DividendInterestReportGenerator(FakeLedger(records), FakeAssetRepo(assets), fx, FakeTaxRateRepo(taxRate))`로 변경. (기존 모든 호출부는 기본값으로 그대로 동작.)

새 테스트:
```kotlin
    @Test
    fun `byCountry 국내 행은 기대세율과 편차-플래그를 포함한다`() {
        // 국내(numeric symbol) 배당: gross 10000, tax 2000 → 실효 20% vs 기대 15.4 → 편차 4.60 flag
        val gen = generator(listOf(rec(3, "삼성전자", "005930", "10000", "2000")), taxRate = BigDecimal("15.4"))
        val body = mapper.readTree(gen.generate(userId, period).bodyJson)
        val domestic = body["byCountry"].first { it["country"].asText() == "국내" }
        assertEquals(15.4, domestic["expectedTaxRate"].asDouble(), 0.001)
        assertEquals(4.60, domestic["taxDeviationPp"].asDouble(), 0.001)
        assertTrue(domestic["taxFlagged"].asBoolean())
    }

    @Test
    fun `byCountry 해외 행은 기대세율이 null(대조 생략)`() {
        val gen = generator(listOf(rec(20, "AAPL", "AAPL", "20000", "3000")))
        val body = mapper.readTree(gen.generate(userId, period).bodyJson)
        val foreign = body["byCountry"].first { it["country"].asText() == "해외" }
        assertTrue(foreign["expectedTaxRate"].isNull)
        assertEquals(false, foreign["taxFlagged"].asBoolean())
    }
```
(주의: `rec`·`generator`·`mapper`·assertion 스타일은 파일 기존 방식과 일치. JsonNode null 체크는 `.isNull`.)

- [ ] **Step 2: 테스트 실패 확인** — Run: `cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :unified-asset:test --tests "*DividendInterestReportGeneratorTest*"` → Expected: FAIL (컴파일 실패: 4-arg 생성자 미존재 → 구현 후 통과).

- [ ] **Step 3: 생성기 통합** — `DividendInterestReportGenerator`:
  1. import 추가: `com.allfolio.unifiedasset.application.port.TaxRateRepository`, `com.allfolio.unifiedasset.domain.tax.IncomeType`.
  2. 생성자에 4번째 파라미터 `private val taxRates: TaxRateRepository,` 추가.
  3. `byCountry` 블록을 spec §3.2 코드로 교체(effRate 계산 후 iso/expected/cmp, 필드 4개 추가).
  4. KDoc "v1 제외: 세율 마스터·기대세율 비교, 이자, ..." 에서 "세율 마스터·기대세율 비교"를 제거하고 "기대세율 비교(국내 KR, ±0.5%p ⚠) 포함. 후속: 해외 국가 매핑·이자 대조." 추가.

- [ ] **Step 4: 테스트 통과 확인** — Run: `cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :unified-asset:test --tests "*DividendInterestReportGeneratorTest*" --tests "*ExpectedTaxComparisonTest*"` → Expected: PASS.

- [ ] **Step 5: 커밋**

```bash
git add allfolio-backend/unified-asset/src/main/kotlin/com/allfolio/unifiedasset/application/usecase/DividendInterestReportGenerator.kt \
        allfolio-backend/unified-asset/src/test/kotlin/com/allfolio/unifiedasset/application/usecase/DividendInterestReportGeneratorTest.kt
git commit -m "feat(dividend): compare effective vs expected withholding rate (KR, ±0.5pp flag)"
```

---

## Task 3: 백엔드 회귀

- [ ] **Step 1: unified-asset 전체 테스트** — Run: `cd /Users/hong9/IdeaProjects/allfolio/allfolio-backend && ./gradlew :unified-asset:test` → Expected: BUILD SUCCESSFUL. (다른 곳에서 DividendInterestReportGenerator를 생성하는 프로덕션 코드가 있으면 Spring DI라 자동 주입되므로 무변경; 혹시 수동 생성부가 있으면 taxRates 주입 추가.)
- [ ] **Step 2: (실패 시) 수정 후 재실행.**

---

## Task 4: Frontend — 타입 + ByCountryTable 확장

**Files:**
- Modify: `frontend/allfolio_app/types/dividend-report.ts`
- Modify: `frontend/allfolio_app/components/dividend-report/ByCountryTable.tsx`

- [ ] **Step 1: 타입 확장** — `types/dividend-report.ts`의 byCountry 행 인터페이스(현재 country/gross/tax/net/effectiveTaxRate 보유)를 찾아 옵셔널 필드 추가:
```ts
  expectedTaxRate?: number | null
  taxDeviationPp?: number | null
  taxFlagged?: boolean
```

- [ ] **Step 2: 컴포넌트 확장** — `components/dividend-report/ByCountryTable.tsx`를 읽고, "실효세율" 열 옆에 "기대세율"·"편차(%p)" 열을 추가. 규칙:
  - `expectedTaxRate == null` → 두 열 "–".
  - `taxFlagged` → 편차 셀에 ⚠ 배지/강조(예: `text-amber-300` + "⚠").
  - 표 하단에 주석: "기대세율은 국내(KR) 기준율 대비이며, 해외는 국가 판별 불가로 생략."
  - 값은 `.toFixed(2)+'%'`(세율)·`+'%p'`(편차). ×100 금지(이미 % 스케일). null 안전 접근(`row.expectedTaxRate ?? null`).

- [ ] **Step 3: 타입체크** — Run: `cd /Users/hong9/IdeaProjects/allfolio/frontend/allfolio_app && npx tsc --noEmit` → Expected: no errors.

- [ ] **Step 4: 커밋**

```bash
git add frontend/allfolio_app/types/dividend-report.ts frontend/allfolio_app/components/dividend-report/ByCountryTable.tsx
git commit -m "feat(dividend-fe): show expected withholding rate + deviation flag in ByCountry"
```

---

## Self-Review 체크
- [ ] Spec 항목(isoOf 국내→KR/그외 null·편차·0.5%p 경계·null 생략·byCountry 필드·해외 null) 커버.
- [ ] `TaxComparison`/`compare`/`isoOf` 시그니처 Task1↔Task2 일치. 생성자 4-arg 반영.
- [ ] FE 옵셔널 필드 → 구 아카이브 "–" 안전.
- [ ] 조언/예측 문구 없음(정합 대조 사실형) + 해외 판별 한계 명시.

## Rollout
- 스키마 변경 없음(tax_rates는 #51에서 생성·시드 완료). main 병합 시 배포.
