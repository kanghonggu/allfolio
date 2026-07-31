# R-06 환전/이체 Phase 2 — 현금흐름 리포트 전용 섹션 Design Spec

- **Date**: 2026-07-31
- **Status**: Approved (design), pending implementation
- **Scope**: 현금흐름 보고서(R-06)에 **환전/계좌간이체 전용 섹션** 추가 — Phase 1(BE)에서 1급 모델링된 내부이동(linkId 페어)을 linkId로 묶어 리포트에 표시. BE 계산(내부이동 페어 집계) + `CashflowReportGenerator` 통합 + FE 섹션.
- **Depends on**: **Phase 1 (#62, feat/cashflow-internal-flows)** — `FlowType.isInternal()`·`CashFlow.linkId` 사용. **#62 브랜치 위에 스택**(#62 머지 후 이 PR을 main 대상으로 재타겟/머지).
- **Out of scope**: FE 기록 폼(이체/환전 입력 UI — 현재 FE에 현금흐름 기록 화면 자체가 없음 → **Phase 3**, 별도 UI surface·nav 결정 필요), 정합 차액 워터폴 분해, 계좌간 환전.

## 1. Background

Phase 1(#62)은 환전/이체를 TRANSFER_*/FX_* + linkId 페어로 저장하고, 외부흐름 뷰(유입/유출·details·special)에서 **제외**했다(오분류 방지). 그러나 내부이동 자체는 리포트에 안 보인다. Phase 2는 이를 **전용 섹션**으로 되살려 "무엇이 내부이동으로 처리됐는지"를 투명하게 보여준다(#54 정합 차액의 상당 부분을 설명).

## 2. Decisions (확정)

| 항목 | 결정 |
|---|---|
| 소스 | period 내 flows 중 `isInternal()` 인 레그, `linkId`로 페어 그룹핑 |
| 이체 표기 | kind="계좌간이체", fromAccount(=TRANSFER_OUT.accountId 명)→toAccount(=TRANSFER_IN.accountId 명), amountKrw |
| 환전 표기 | kind="환전", fromCurrency/fromAmount(FX_OUT)→toCurrency/toAmount(FX_IN), amountKrw(=FX_OUT.amountKrw) |
| 금액 | amountKrw는 OUT 레그 기준(스프레드는 IN/OUT amountKrw 차이로 존재하나 v2에선 OUT 크기만 표기) |
| 정렬 | 날짜 내림차순 |
| 고아 레그 | linkId null 또는 페어 미완성(레그 1개) → 스킵(정상 경로에선 미발생, 방어적) |
| 빈 경우 | internalFlows 빈 배열 → FE 섹션 미표시 |

## 3. Backend Design (module: `unified-asset`)

### 3.1 내부이동 집계 계산기 (신규, 순수)
`application/usecase/InternalFlowCalculator.kt`:
```kotlin
data class InternalFlowEntry(
    val date: LocalDate,
    val kind: String,                 // "계좌간이체" | "환전"
    val fromAccount: String?,         // 이체
    val toAccount: String?,           // 이체
    val fromCurrency: String?,        // 환전
    val toCurrency: String?,          // 환전
    val fromAmount: BigDecimal?,      // 환전 원통화 금액
    val toAmount: BigDecimal?,        // 환전 대상통화 금액
    val amountKrw: BigDecimal,        // OUT 레그 KRW 환산
)

object InternalFlowCalculator {
    fun build(flows: List<CashFlow>, acctNames: Map<UUID, String>): List<InternalFlowEntry> {
        return flows.filter { it.type.isInternal() && it.linkId != null }
            .groupBy { it.linkId!! }
            .mapNotNull { (_, legs) ->
                val out = legs.firstOrNull { it.type == FlowType.TRANSFER_OUT || it.type == FlowType.FX_OUT }
                val inn = legs.firstOrNull { it.type == FlowType.TRANSFER_IN  || it.type == FlowType.FX_IN }
                if (out == null || inn == null) return@mapNotNull null   // 페어 미완성 스킵
                if (out.type == FlowType.TRANSFER_OUT) {
                    InternalFlowEntry(
                        date = out.flowDate, kind = "계좌간이체",
                        fromAccount = out.accountId?.let { acctNames[it] } ?: "-",
                        toAccount = inn.accountId?.let { acctNames[it] } ?: "-",
                        fromCurrency = null, toCurrency = null, fromAmount = null, toAmount = null,
                        amountKrw = out.amountKrw,
                    )
                } else {
                    InternalFlowEntry(
                        date = out.flowDate, kind = "환전",
                        fromAccount = null, toAccount = null,
                        fromCurrency = out.currency, toCurrency = inn.currency,
                        fromAmount = out.amount, toAmount = inn.amount,
                        amountKrw = out.amountKrw,
                    )
                }
            }
            .sortedByDescending { it.date }
    }
}
```

### 3.2 생성기 통합 — `CashflowReportGenerator`
- 기존 `flows`·`acctNames` 재사용:
  ```kotlin
  val internalFlows = InternalFlowCalculator.build(flows, acctNames)
  ```
- body에 추가:
  ```kotlin
  "internalFlows" to internalFlows.map { mapOf(
      "date" to it.date.toString(), "kind" to it.kind,
      "fromAccount" to it.fromAccount, "toAccount" to it.toAccount,
      "fromCurrency" to it.fromCurrency, "toCurrency" to it.toCurrency,
      "fromAmount" to it.fromAmount, "toAmount" to it.toAmount,
      "amountKrw" to it.amountKrw) },
  ```
- KDoc의 "특이거래(대규모 이동·미분류) 포함. 후속: ...환전·계좌간이체." → "환전·계좌간이체 전용 섹션 포함(Phase 2). 후속: 워터폴·정합 차액 분해."

## 4. Frontend Design (cashflow-report 상세)
- 타입 `types/cashflow-report.ts`: `CashflowInternalFlow { date; kind; fromAccount: string|null; toAccount: string|null; fromCurrency: string|null; toCurrency: string|null; fromAmount: number|null; toAmount: number|null; amountKrw: number }`, body에 `internalFlows?: CashflowInternalFlow[]`(옵셔널).
- 신규 컴포넌트 `components/cashflow-report/InternalFlows.tsx`: 표(날짜·유형 배지·내용[이체: from→to 계좌 / 환전: fromCcy fromAmount → toCcy toAmount]·KRW 금액). 빈/미존재 시 섹션 생략. 소제목 아래 "내부이동은 외부 유입/유출에서 제외되어 별도 표기됩니다" 주석.
- `[id]/page.tsx`: SpecialTransactions/Reconciliation 근처에 `{body.internalFlows && body.internalFlows.length > 0 && <InternalFlows rows={body.internalFlows} />}`. `fmtKrw`, ×100 없음.

## 5. Tests
**Backend** — `InternalFlowCalculatorTest`(순수): 이체 페어(TRANSFER_OUT+IN 동일 linkId → 계좌간이체·from/to 계좌명·amountKrw); 환전 페어(FX_OUT KRW + FX_IN USD → 환전·통화·금액); 다중 그룹 날짜 내림차순; 고아 레그(IN만) 스킵; 외부유형(DEPOSIT)은 미포함. `CashflowReportGeneratorTest` 확장: 내부유형 flow 포함 시 body.internalFlows에 페어 표기(외부 집계 불변 — Phase 1 단언 유지). 
**Frontend**: `npx tsc --noEmit` clean.

## 6. Rollout
- **스키마 변경 없음**(Phase 1의 link_id 사용) → 추가 마이그레이션 불필요. **#62 머지 후** 이 PR을 main으로 머지.

## 7. Affected Files
**BE**: (신규) `application/usecase/InternalFlowCalculator.kt`, (수정) `application/usecase/CashflowReportGenerator.kt`, (test) 신규 `InternalFlowCalculatorTest.kt` + 수정 `CashflowReportGeneratorTest.kt`.
**FE**: (수정) `types/cashflow-report.ts`, (신규) `components/cashflow-report/InternalFlows.tsx`, (수정) `app/unified/reports/cashflow-report/[id]/page.tsx`.

## 8. Out of Scope (Phase 3+)
FE 기록 폼(이체/환전 입력 — 신규 UI surface), 스프레드/수수료 명시 표기, 정합 차액 워터폴, 계좌간 환전, 내부이동 필터/기간 UI.
