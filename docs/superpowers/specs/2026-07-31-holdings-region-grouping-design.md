# R-05 지역 그룹핑 (통화 기반 지역 노출, Phase A) — Design Spec

- **Date**: 2026-07-31
- **Status**: Approved (design), pending implementation
- **Scope**: 보유명세 보고서(R-05)에 **지역 노출**(보유 자산을 통화 파생 지역으로 그룹핑) 추가 — BE 계산 + `HoldingsReportGenerator` 통합 + FE. 기관 리포트의 지역 배분(geographic allocation)에 해당하는 **사실형 집계**.
- **Depends on**: 없음 — `main`에서 분기(HoldingsReportGenerator는 #53/#56 머지 완료 상태, byAccount/byType 패턴 재사용).
- **데이터 제약(정직 명시)**: 자산에 **국가/거래소 필드 부재**. 유일한 지역 신호는 `Asset.currency`. 따라서 지역은 **통화에서 파생한 근사치**(예: USD→미국). ADR·다국적 노출은 근사 한계 → FE에 "통화 기준 추정" 명시.

## 1. Background

`HoldingsReportGenerator`(#40, #53/#56 확장)는 종목 명세·byAccount·byType·현금·실현손익·월간변동을 산출. KDoc "v1 제외: ...지역별 그룹핑". 명세 SCR-RPT-08: 지역/국가별 배분. 자산 모델은 country/exchange 미보유 → currency만 존재(KRW/USD/JPY/…). 통화→지역 매핑으로 coarse 지역 노출 제공.

## 2. Decisions (확정)

| 항목 | 결정 |
|---|---|
| 지역 신호 | `Asset.currency`(대문자 정규화) 파생 |
| 매핑 | KRW→국내, USD→미국, JPY→일본, EUR→유럽, CNY→중국, HKD→홍콩, GBP→영국, 그 외→기타 |
| 대상 | 전 자산(현금 포함 — 통화 노출 관점). KRW 환산 평가액 기준 |
| 산출 | region·valueKrw·weight(0~100)·holdingCount, valueKrw 내림차순 |
| 표기 | "통화 기준 추정 지역"임을 리포트/FE에 명시 |

## 3. Backend Design (module: `unified-asset`)

### 3.1 통화→지역 매퍼 (신규, 순수)
`application/usecase/CurrencyRegionMapper.kt`:
```kotlin
object CurrencyRegionMapper {
    private val MAP = mapOf(
        "KRW" to "국내", "USD" to "미국", "JPY" to "일본", "EUR" to "유럽",
        "CNY" to "중국", "HKD" to "홍콩", "GBP" to "영국",
    )
    /** 통화코드 → 지역 라벨. 미등록/공백 → "기타". */
    fun regionOf(currency: String?): String =
        currency?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let { MAP[it] ?: "기타" } ?: "기타"
}
```

### 3.2 생성기 통합 — `HoldingsReportGenerator`
- 기존 `valued`(List<Pair<Asset, BigDecimal(KRW)>>)·`totalKrw`·`pct` 재사용. `byType` 산출 근처에 추가:
  ```kotlin
  val byRegion = valued.groupBy { CurrencyRegionMapper.regionOf(it.first.currency) }
      .map { (region, g) ->
          val sum = g.fold(BigDecimal.ZERO) { acc, (_, v) -> acc + v }
          mapOf("region" to region, "valueKrw" to sum, "weight" to pct(sum, totalKrw), "holdingCount" to g.size)
      }.sortedByDescending { it["valueKrw"] as BigDecimal }
  ```
- body에 `"byRegion" to byRegion` 추가. KDoc "v1 제외: ...지역별 그룹핑" → "지역 노출(통화 파생) 포함. 후속: 국가/거래소 필드 기반 정밀 분류."

## 4. Frontend Design (holdings-report 상세)
- 타입 `types/holdings-report.ts`: `HoldingsByRegion { region: string; valueKrw: number; weight: number; holdingCount: number }`, body에 `byRegion?: HoldingsByRegion[]`(옵셔널 — 구 아카이브 호환).
- 신규 컴포넌트 `components/holdings-report/RegionExposure.tsx`: byType/byAccount와 동일 룩앤필 표(지역·평가액·비중·종목수), 소제목 아래 "통화 기준 추정" 안내 문구. 빈/미존재 시 섹션 생략.
- `[id]/page.tsx`: byType 섹션 근처에 `{body.byRegion && body.byRegion.length > 0 && <RegionExposure rows={body.byRegion} />}`. `fmtKrw`/`fmtPctScaled` 또는 `.toFixed(2)+'%'`(×100 금지 — weight 이미 0~100).

## 5. Tests
**Backend** — `CurrencyRegionMapperTest`(순수): KRW→국내·USD→미국·JPY→일본·소문자"usd"→미국·미등록"AUD"→기타·null/공백→기타. `HoldingsReportGeneratorTest` 확장: 서로 다른 통화(KRW·USD) 자산 → byRegion에 국내·미국 그룹, valueKrw 합·holdingCount·정렬 검증. 기존 단언 불변(byRegion 추가만).
**Frontend**: `npx tsc --noEmit` clean.

## 6. Rollout
- **스키마 변경 없음** → 마이그레이션 불필요. main 병합 → 배포.

## 7. Affected Files
**BE**: (신규) `application/usecase/CurrencyRegionMapper.kt`, (수정) `application/usecase/HoldingsReportGenerator.kt`, (test) 신규 `CurrencyRegionMapperTest.kt` + 수정 `HoldingsReportGeneratorTest.kt`.
**FE**: (수정) `types/holdings-report.ts`, (신규) `components/holdings-report/RegionExposure.tsx`, (수정) `app/unified/reports/holdings-report/[id]/page.tsx`.

## 8. Out of Scope (후속)
국가/거래소 필드 기반 정밀 지역 분류(자산 모델 확장), 섹터/산업 분류, ADR·다국적 매출 지역 귀속, 통화 헤지 노출.
