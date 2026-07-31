# R-06 특이거래 (SCR-RPT-09 ⑥, Phase A) — Design Spec

- **Date**: 2026-07-31
- **Status**: Approved (design), pending implementation
- **Scope**: 현금흐름 보고서(R-06)에 **특이거래**(대규모 이동 + 미분류 흐름) 추가 — BE 계산 + `CashflowReportGenerator` 통합 + FE 화면. (SCR-RPT-09 ⑥ 특이거래)
- **Depends on**: R-06 현금 정합검증(#54, merged to main) — `CashflowReportGenerator`의 `assetRepository`/`fx` 주입 재사용. `main`에서 분기.
- **Out of scope (후속)**: 미결제 건(결제일 데이터 부재), 환전·계좌간이체(전용 원장·FlowType 부재), 임계치 설정 UI.

## 1. Background

`CashflowReportGenerator`(#41, #54 확장)는 유입/유출·조정표·정합검증을 산출한다. 헤더에 "v1 제외: 특이거래". 명세 SCR-RPT-09 ⑥: 미결제 / 대규모 이동(자산 대비 임계치 기본 10%) / 미분류 흐름(유형 매핑 실패).

**데이터 제약**: `cash_flow`는 DEPOSIT/WITHDRAWAL만, `ua_stock_trades`는 tradedAt만(결제일 없음). 따라서 가능한 것은 **대규모 이동**(금액 임계)과 **미분류 흐름**(미매핑 거래유형, 예: MARGIN — 현재 details에서 `else→null`로 누락)뿐. 미결제·환전/이체는 데이터 부재로 후속.

## 2. Decisions (확정)

| 항목 | 결정 |
|---|---|
| 대규모 이동 | 개별 이동 |금액KRW| ≥ **총자산KRW × thresholdRatio(기본 0.10)**. 총자산 0이면 생략 |
| 총자산 | `assetRepository.findByUserId` 전 자산 `currentValueInKrw(fx)` 합(#54 주입 재사용) |
| 미분류 흐름 | `tradeType ∉ {BUY, CREDIT_BUY, SELL, CREDIT_SELL, DIVIDEND}` (MARGIN 등) |
| 대상 | 기간(period) flows·trades (기존 generate의 period-bounded 데이터) |
| 통화 | KRW 취급(amountKrw / totalAmount) |
| 후속 | 미결제·환전·계좌간이체·임계치 설정 |

## 3. Backend Design (module: `unified-asset`)

### 3.1 특이거래 계산기 (신규, 순수)
`application/usecase/SpecialTransactionCalculator.kt`:
```kotlin
data class SpecialMovement(val date: LocalDate, val account: String, val type: String, val description: String, val amountKrw: BigDecimal)
data class UnclassifiedFlow(val date: LocalDate, val account: String, val tradeType: String, val amountKrw: BigDecimal)
data class SpecialTransactions(val largeMovements: List<SpecialMovement>, val unclassified: List<UnclassifiedFlow>)

object SpecialTransactionCalculator {
    private val BUY = setOf("BUY", "CREDIT_BUY")
    private val SELL = setOf("SELL", "CREDIT_SELL")
    private val KNOWN = BUY + SELL + setOf("DIVIDEND")

    fun build(
        flows: List<CashFlow>,
        trades: List<TradeCashRecord>,
        acctNames: Map<UUID, String>,
        totalAssetsKrw: BigDecimal,
        thresholdRatio: BigDecimal = BigDecimal("0.10"),
    ): SpecialTransactions { ... }
}
```
- **largeMovements**: `threshold = totalAssetsKrw * thresholdRatio`. `threshold.signum() > 0` 일 때만:
  - flows: `|amountKrw| ≥ threshold` → `SpecialMovement(flowDate, acctNames[accountId]?:"-", DEPOSIT?"입금":"출금", memo?:type, DEPOSIT? amountKrw : amountKrw.negate())`.
  - trades: `|totalAmount| ≥ threshold` → type/부호: BUY류 "매수대금"/−, SELL류 "매도대금"/+, DIVIDEND "배당·이자"/+, else "기타"/+. `SpecialMovement(tradeDate, accountName, type, stockName, signed)`.
  - `|amountKrw|` 내림차순.
- **unclassified**: `trades.filter { it.tradeType !in KNOWN }` → `UnclassifiedFlow(tradeDate, accountName, tradeType, totalAmount)`. 날짜순. (cash_flow는 전부 매핑되어 미분류 없음.)

### 3.2 생성기 통합 — `CashflowReportGenerator`
- `assetRepository.findByUserId(userId)`를 **1회 조회로 hoist**(현재 actualCash 계산 inline) → 재사용:
  ```kotlin
  val assets = assetRepository.findByUserId(userId)
  val totalAssetsKrw = assets.fold(BigDecimal.ZERO) { a, x -> a + x.currentValueInKrw(fx) }
  val actualCash = assets.filter { it.type == AssetType.CASH }.fold(BigDecimal.ZERO) { a, x -> a + x.currentValueInKrw(fx) }
  ```
  (기존 `actualCash` 계산부를 이 형태로 치환 — reconciliation 동작 불변.)
- `acctNames`(기존 존재) 활용:
  ```kotlin
  val special = SpecialTransactionCalculator.build(flows, trades, acctNames, totalAssetsKrw)
  ```
- body에 추가:
  ```kotlin
  "specialTransactions" to mapOf(
      "thresholdRatio" to BigDecimal("0.10"),
      "largeMovements" to special.largeMovements.map { mapOf("date" to it.date.toString(), "account" to it.account,
          "type" to it.type, "description" to it.description, "amountKrw" to it.amountKrw) },
      "unclassified" to special.unclassified.map { mapOf("date" to it.date.toString(), "account" to it.account,
          "tradeType" to it.tradeType, "amountKrw" to it.amountKrw) },
  ),
  ```
- KDoc "v1 제외: ...특이거래" → "특이거래(대규모 이동·미분류) 포함. 후속: 미결제·환전·계좌간이체."

### 4. Frontend Design (cashflow 상세)

- 타입 `types/cashflow-report.ts` 확장:
  ```ts
  export interface CashflowLargeMovement { date: string; account: string; type: string; description: string; amountKrw: number }
  export interface CashflowUnclassified { date: string; account: string; tradeType: string; amountKrw: number }
  export interface CashflowSpecialTransactions { thresholdRatio: number; largeMovements: CashflowLargeMovement[]; unclassified: CashflowUnclassified[] }
  ```
  `CashflowReportBody`에 `specialTransactions?: CashflowSpecialTransactions`(옵셔널 — 구 아카이브 호환).
- 신규 컴포넌트 `components/cashflow-report/SpecialTransactions.tsx`: 대규모 이동 표(금액 ±색, 헤더에 "자산 {thresholdRatio×100}% 이상" 안내) + 미분류 흐름 표. 둘 다 비면 "특이거래 없음".
- `[id]/page.tsx`: `{body.specialTransactions && <SpecialTransactions data={body.specialTransactions} />}` (예: reconciliation/details 다음). `fmtKrw`/`pctColor`(×100 금지).

## 5. Tests

**Backend (unified-asset)** — `SpecialTransactionCalculatorTest`(순수):
- 대규모 이동: 총자산 1,000,000·ratio 0.10 → threshold 100,000. 150,000 입금 flag, 50,000 미flag.
- 총자산 0 → largeMovements 빈 배열(0 나눗셈 회피).
- 미분류: MARGIN 거래 → unclassified. BUY/SELL/DIVIDEND는 unclassified 아님.
- 부호: 출금/매수 음수, 입금/매도/배당 양수.
- 정렬: largeMovements |금액| 내림차순.

**Backend** — `CashflowReportGeneratorTest` 확장: specialTransactions 섹션(대규모 이동·미분류) 검증. 기존 테스트는 자산/거래 소액이라 빈 구조만 추가되고 summary/byType/reconciliation 단언 불변.

**Frontend**: `npx tsc --noEmit` clean.

## 6. Rollout / 배포 순서
- **스키마 변경 없음** → 운영 마이그레이션 불필요.
- main 병합 → Render 자동배포(BE) → FE 배포.
- 검증: 자산 대비 큰 입출금/매매 또는 MARGIN 거래 있는 계정 → CASHFLOW 리포트 → 특이거래(대규모 이동·미분류) 확인.

## 7. Affected Files (요약)

**Backend — unified-asset**
- (신규) `application/usecase/SpecialTransactionCalculator.kt`
- (수정) `application/usecase/CashflowReportGenerator.kt` (assets hoist + special 통합)
- (test 신규) `application/usecase/SpecialTransactionCalculatorTest.kt`
- (test 수정) `application/usecase/CashflowReportGeneratorTest.kt`

**Frontend**
- (수정) `types/cashflow-report.ts`
- (신규) `components/cashflow-report/SpecialTransactions.tsx`
- (수정) `app/unified/reports/cashflow-report/[id]/page.tsx`

## 8. Out of Scope (후속)
- 미결제 건(결제일/settle-date 데이터 부재).
- 환전·계좌간이체 유형행(전용 원장·FlowType 부재 — #54 정합 차액이 미포착분을 이미 드러냄).
- 대규모 임계치 사용자 설정 UI(현재 10% 고정).
