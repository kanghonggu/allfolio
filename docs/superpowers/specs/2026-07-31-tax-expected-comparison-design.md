# R-03 세율마스터 Phase B — 기대세율 비교 (배당뷰어) Design Spec

- **Date**: 2026-07-31
- **Status**: Approved (design), pending implementation
- **Scope**: 배당·이자 보고서(R-03) `byCountry`에 **기준(기대) 원천징수율 비교** 추가 — 실효세율(tax/gross) vs `tax_rates` 마스터 기준율을 대조해 **±0.5%p 초과 시 ⚠** 플래그. BE 계산 + `DividendInterestReportGenerator` 통합 + FE.
- **Depends on**: R-03 세율마스터 **#51(main 머지 완료, 운영 마이그레이션 실행 완료)** — `TaxRateRepository.findEffective` 재사용. `main`에서 분기.
- **성격**: 데이터 정합/컴플라이언스 **사실형 대조**(원천징수가 기준율과 얼마나 다른가). 투자 조언·예측 아님.

## 1. Background

`DividendInterestReportGenerator`(#38)의 `byCountry`는 국가별(numeric symbol→"국내", 그 외→"해외") gross/tax/net과 실효세율 `effectiveTaxRate = pct(tax, gross)`를 산출. #51이 `tax_rates`(ISO2 국가×IncomeType×rate% 버저닝)와 `TaxRateRepository.findEffective(country, incomeType, date)`를 도입. Phase B는 실효세율을 기준율과 대조.

**국가 판별 제약(정직 명시)**: 배당 레코드는 국가 ISO를 직접 보유하지 않음. numeric symbol → **국내=KR**(신뢰), 그 외 "해외"는 US/JP 등 **단일 국가 판별 불가** → 기대세율 대조는 **국내(KR)만** 수행, 해외는 기대율 null(대조 생략). FE·리포트에 명시. (해외 국가 매핑은 후속 — 심볼→거래소/국가 데이터 필요.)

## 2. Decisions (확정)

| 항목 | 결정 |
|---|---|
| 대조 단위 | `byCountry` 행(국내/해외) |
| 국가→ISO | "국내"→"KR", 그 외→null(대조 생략) |
| 기대율 | `TaxRateRepository.findEffective(iso, DIVIDEND, period.end)?.rate` (%값) |
| 편차 | `deviationPp = 실효세율 − 기대율` (%p, 2자리) |
| 플래그 | 기대율 존재 & `|deviationPp| > 0.5` → ⚠ |
| 통화/기준일 | period.end 시점 유효 세율 |
| 후속 | 해외 국가 매핑(심볼→거래소/국가), 이자/분배 대조, 세액 재계산 |

## 3. Backend Design (module: `unified-asset`)

### 3.1 기대세율 비교 계산기 (신규, 순수)
`application/usecase/ExpectedTaxComparison.kt`:
```kotlin
import java.math.BigDecimal
import java.math.RoundingMode

data class TaxComparison(val expectedRate: BigDecimal?, val deviationPp: BigDecimal?, val flagged: Boolean)

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

### 3.2 생성기 통합 — `DividendInterestReportGenerator`
- 생성자에 `private val taxRates: TaxRateRepository` 추가(4번째 파라미터).
- `byCountry` 산출 시 각 행에 대조 필드 추가:
  ```kotlin
  val byCountry = records.groupBy { if (it.symbol?.matches(numericSymbol) == true) "국내" else "해외" }
      .map { (country, rs) ->
          val g = rs.sum { it.gross }; val t = rs.sum { it.tax }
          val effRate = pct(t, g)
          val iso = ExpectedTaxComparison.isoOf(country)
          val expected = iso?.let { taxRates.findEffective(it, IncomeType.DIVIDEND, period.end)?.rate }
          val cmp = ExpectedTaxComparison.compare(effRate, expected)
          mapOf(
              "country" to country, "gross" to g, "tax" to t, "net" to (g - t),
              "effectiveTaxRate" to effRate,
              "expectedTaxRate" to cmp.expectedRate,
              "taxDeviationPp" to cmp.deviationPp,
              "taxFlagged" to cmp.flagged,
          )
      }.sortedByDescending { it["gross"] as BigDecimal }
  ```
- import `com.allfolio.unifiedasset.domain.tax.IncomeType`, `com.allfolio.unifiedasset.application.port.TaxRateRepository`.
- KDoc "v1 제외: 세율 마스터·기대세율 비교" → "기대세율 비교(국내 KR, ±0.5%p ⚠) 포함. 후속: 해외 국가 매핑·이자 대조."

## 4. Frontend Design (dividend-report 상세)
- 타입 `types/dividend-report.ts` `ByCountryRow`(기존)에 옵셔널 추가: `expectedTaxRate?: number | null; taxDeviationPp?: number | null; taxFlagged?: boolean`.
- `components/dividend-report/ByCountryTable.tsx` 확장: "기대세율"·"편차(%p)" 열 추가, `taxFlagged`면 ⚠ 배지+강조. 기대율 null(해외)이면 "–". 표 하단 주석 "기대세율은 국내(KR) 기준율과 비교하며, 해외는 국가 판별 불가로 생략."
- 옵셔널이므로 구 아카이브(필드 없음)에서도 열은 "–"로 안전.

## 5. Tests
**Backend** — `ExpectedTaxComparisonTest`(순수): isoOf("국내")="KR"·isoOf("해외")=null; compare(15.4, 15.4)→편차 0.00·flag false; compare(20.0, 15.4)→편차 4.60·flag true; compare(15.8, 15.4)→편차 0.40·flag false(경계 <0.5); compare(effRate, null)→(null,null,false). `DividendInterestReportGeneratorTest` 확장: 국내 배당(실효율≠기준율) → byCountry 국내 행에 expectedTaxRate·taxDeviationPp·taxFlagged; 해외 행은 expectedTaxRate null. FakeTaxRateRepo(KR DIVIDEND 15.4 반환) 추가, `generator()` 헬퍼 4-arg로 갱신. 기존 단언 불변.
**Frontend**: `npx tsc --noEmit` clean.

## 6. Rollout
- **스키마 변경 없음**(tax_rates는 #51에서 이미 생성·시드) → 추가 마이그레이션 불필요. main 병합 → 배포.

## 7. Affected Files
**BE**: (신규) `application/usecase/ExpectedTaxComparison.kt`, (수정) `application/usecase/DividendInterestReportGenerator.kt`, (test) 신규 `ExpectedTaxComparisonTest.kt` + 수정 `DividendInterestReportGeneratorTest.kt`.
**FE**: (수정) `types/dividend-report.ts`, `components/dividend-report/ByCountryTable.tsx`, (필요 시) `[id]/page.tsx` 무변경(ByCountryTable 내부 확장).

## 8. Out of Scope (후속)
해외 국가 판별(심볼→거래소/국가 매핑), 이자/분배(INTEREST/DISTRIBUTION) 대조, 예상 세액 재계산·환급 추정, USER용 세율 조회 API.
