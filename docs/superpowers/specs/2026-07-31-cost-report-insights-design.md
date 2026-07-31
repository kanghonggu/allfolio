# R-04 비용 인사이트 (사실형, Phase A) — Design Spec

- **Date**: 2026-07-31
- **Status**: Approved (design), pending implementation
- **Scope**: 비용 보고서(R-04)에 **사실형 비용 인사이트**(기존 집계값의 파생 하이라이트) 추가 — BE 계산 + `CostReportGenerator` 통합 + FE. **투자/재무 조언 아님**(서술형 사실만).
- **Depends on**: 없음 — `main`에서 분기(CostReportGenerator 수정하는 미머지 PR 없음).
- **Out of scope (후속)**: 개인화 추천/조언 문구, 브로커 bp 벤치마크 비교(외부 기준 데이터 부재), 환전·파생 비용, 전월 대비(단일 월 아카이브라 리포트 내 부재).

## 1. Background

`CostReportGenerator`(#39/#41)는 총비용·매매수수료·거래세·costRatio(총비용/평균NAV)·연환산 TER·costVsProfit·byBroker·byType·monthly를 산출. 헤더 "v1 제외: ...인사이트". spec §후속: "인사이트 자동문구·bp 비교 ❌ 후속 — 개인화 룰". 

**안전 경계**: 자동 인사이트 문구는 개인화 투자/재무 조언이 될 수 있음 → **서술형 사실만**(권고/조언 금지). 기존 집계값을 사람이 읽기 쉬운 하이라이트로 재구성.

## 2. Decisions (확정)

| 항목 | 결정 |
|---|---|
| 성격 | 사실형 하이라이트만(조언·추천 없음) |
| 소스 | 기존 summary/byBroker/byType 파생(신규 데이터 없음) |
| 항목 | 연환산 TER(bp)·기간 비용률(bp)·최대 비용 처(브로커+비중)·비용 구성(수수료/세금 비중)·투자손익 대비 비용 |
| null 처리 | costRatio/ter/costVsProfit null(NAV·손익 부재)이면 해당 인사이트 생략 |

## 3. Backend Design (module: `unified-asset`)

### 3.1 인사이트 계산기 (신규, 순수)
`application/usecase/CostInsightCalculator.kt`:
```kotlin
data class CostInsight(val label: String, val value: String, val detail: String?)

object CostInsightCalculator {
    fun build(
        totalCost: BigDecimal, brokerFee: BigDecimal, tradingTax: BigDecimal,
        costRatio: BigDecimal?, annualizedTer: BigDecimal?, costVsProfit: BigDecimal?,
        topBrokerName: String?, topBrokerWeight: BigDecimal?,
    ): List<CostInsight> { ... }
}
```
- TER: `annualizedTer != null` → `CostInsight("연환산 TER", "${bp(annualizedTer)}bp", "비용률 연환산")`. `bp(x) = x.multiply(100).setScale(0, HALF_UP)`(비용률 %→bp).
- 비용률: `costRatio != null` → `CostInsight("기간 비용률", "${costRatio}% (${bp(costRatio)}bp)", null)`.
- 최대 비용 처: `topBrokerName != null` → `CostInsight("최대 비용 처", topBrokerName, "전체 비용의 ${topBrokerWeight}%")`.
- 비용 구성: `totalCost > 0` → `CostInsight("비용 구성", "매매수수료 ${pct(brokerFee,totalCost)}% · 거래세 ${pct(tradingTax,totalCost)}%", null)`.
- 투자손익 대비: `costVsProfit != null` → `CostInsight("투자손익 대비 비용", "${costVsProfit}%", null)`.
- `pct(a,b)`: b>0이면 a/b×100(2자리), 아니면 0. 순서: TER→비용률→최대비용처→비용구성→투자손익대비.

### 3.2 생성기 통합 — `CostReportGenerator`
- body 생성 직전, 기존 값들(totalCost/brokerFee/tradingTax/costRatio/ter/costVsProfit/byBroker) 활용:
  ```kotlin
  val topBroker = byBroker.firstOrNull()
  val insights = CostInsightCalculator.build(
      totalCost, brokerFee, tradingTax, costRatio, ter, costVsProfit,
      topBroker?.get("broker") as String?, topBroker?.get("weight") as BigDecimal?,
  )
  ```
- body에 `"insights" to insights.map { mapOf("label" to it.label, "value" to it.value, "detail" to it.detail) }` 추가. KDoc "v1 제외" 갱신.

## 4. Frontend Design (cost-report 상세)
- 타입 `types/cost-report.ts`: `CostInsight { label; value; detail: string | null }`, body에 `insights?: CostInsight[]`(옵셔널).
- 신규 컴포넌트 `components/cost-report/CostInsights.tsx`: 인사이트 카드/리스트(label·value·detail). 빈 배열이면 섹션 생략.
- `[id]/page.tsx`: `{body.insights && body.insights.length > 0 && <CostInsights items={body.insights} />}` (요약 근처).

## 5. Tests
**Backend** — `CostInsightCalculatorTest`(순수): TER bp 변환·비용률·최대 비용 처(비중)·비용 구성 비율·투자손익 대비·null 생략(costRatio/ter/costVsProfit null → 해당 항목 없음). `CostReportGeneratorTest` 확장: insights 섹션 존재·주요 항목. 기존 테스트 불변(insights 추가만).
**Frontend**: `npx tsc --noEmit` clean.

## 6. Rollout
- **스키마 변경 없음** → 마이그레이션 불필요. main 병합 → 배포.

## 7. Affected Files
**BE**: (신규) `application/usecase/CostInsightCalculator.kt`, (수정) `application/usecase/CostReportGenerator.kt`, (test) 신규 `CostInsightCalculatorTest.kt` + 수정 `CostReportGeneratorTest.kt`.
**FE**: (수정) `types/cost-report.ts`, (신규) `components/cost-report/CostInsights.tsx`, (수정) `app/unified/reports/cost-report/[id]/page.tsx`.

## 8. Out of Scope (후속)
개인화 추천/조언, 브로커 bp 벤치마크(외부 기준), 환전·파생 비용, 전월 대비 추이(다중 리포트 비교), 원통화 병기.
