# R-06 현금 조정표·정합검증 후속 — Design Spec

- **Date**: 2026-07-30
- **Status**: Approved (design), pending implementation
- **Scope**: 현금흐름 보고서(R-06)에 **기초/기말 현금 조정표 + 정합검증** 추가 — BE 계산 + `CashflowReportGenerator` 통합 + FE 조정표 섹션. (SCR-RPT-09 ② 현금 조정표)
- **Depends on**: 없음 — `main`에서 분기.
- **Out of scope (후속)**: 환전·계좌간이체 유형행, 특이거래 섹션(미결제·대규모이동·미분류), 워터폴 차트, 통화별 컬럼, 거래일/결제일 토글.

## 1. Background

`CashflowReportGenerator`(R-06, #41)는 현재 유입/유출/순흐름·byType·monthly·details만 산출하고 헤더에 "v1 제외: 기초/기말 조정·정합검증(월초 잔고 부재)"를 명시. 명세 SCR-RPT-09 ② 현금 조정표: **기초 현금 + 유형별 증감 + 기말 현금(=기초+Σ증감) + ✓정합검증(계산 기말 = 실제 잔고, 불일치 시 차액·숨김 금지)**. 처리규칙 #1: "기초+흐름합계=기말 대조가 화면에 항상 표시".

**핵심 난점**: 월초 잔고 스냅샷이 없다. → **전체 이력에서 기초잔고를 재구성**한다(스냅샷 불필요).

## 2. Decisions (확정)

| 항목 | 결정 |
|---|---|
| 기초잔고 | 전체 이력(date < period.start)의 순현금이동 **재구성**. 기존 포트를 wide 범위(EPOCH ~ start−1)로 조회, 신규 포트 불필요 |
| 기말(계산) | 기초 + netFlow(기간) (= 순현금이동 ≤ end) |
| 실제 현금 | 현재 `ua_assets` CASH 자산 `currentValueInKrw(fx)` 합계(KRW) — 독립 소스 |
| 정합검증 대상 | 실제 현금(현재 잔고). **당월/최신 한정** 유의미 |
| reconcilable 판정 | **period.end 이후 현금활동(flows·trades) 부재** 이면 true (clock 불필요·결정적) |
| 통화 | KRW 취급(기존 관례) |

## 3. Backend Design (module: `unified-asset`, `CashflowReportGenerator`)

### 3.0 생성자 확장
현재 `(cashFlowRepository, tradeSource, accountRepository)`에 추가:
```kotlin
    private val assetRepository: AssetRepository,   // 실제 현금(현재 CASH 잔고)
    private val fx: FxConverter,                    // KRW 환산
```

### 3.1 기초/기말/실제/정합 계산
`generate` 안, 기존 period 집계 이후:
- 순현금 헬퍼(기존 inflow/outflow 로직 추출):
  ```kotlin
  // flows·trades → 순현금이동(KRW): 입금−출금 + 매도−매수 + 배당 − 수수료·세금
  fun netCash(fs: List<CashFlow>, ts: List<TradeCashRecord>): BigDecimal { ... }
  ```
  (기존 generate의 유입/유출 계산을 이 헬퍼로 재사용해 DRY.)
- **기초**: 전체 이력 조회 후 순현금:
  ```kotlin
  val EPOCH = LocalDate.of(1970, 1, 1)
  val beforeFlows  = cashFlowRepository.findByUserIdAndPeriod(userId, EPOCH, period.start.minusDays(1))
  val beforeTrades = tradeSource.findTrades(userId, EPOCH, period.start.minusDays(1))
  val openingBalance = netCash(beforeFlows, beforeTrades)
  ```
- **기말(계산)**: `val closingCalculated = openingBalance + netFlow` (netFlow는 기존 = 기간 순현금).
- **실제 현금(현재)**:
  ```kotlin
  val actualCash = assetRepository.findByUserId(userId)
      .filter { it.type == AssetType.CASH }
      .fold(BigDecimal.ZERO) { a, asset -> a + asset.currentValueInKrw(fx) }
  ```
- **reconcilable** (period.end 이후 현금활동 부재):
  ```kotlin
  val FAR = LocalDate.of(9999, 12, 31)
  val afterFlows  = cashFlowRepository.findByUserIdAndPeriod(userId, period.end.plusDays(1), FAR)
  val afterTrades = tradeSource.findTrades(userId, period.end.plusDays(1), FAR)
  val reconcilable = afterFlows.isEmpty() && afterTrades.isEmpty()
  ```
- **차액·정합**:
  ```kotlin
  val difference = actualCash - closingCalculated        // reconcilable일 때만 의미
  val reconciled = reconcilable && difference.abs() < BigDecimal.ONE   // <1 KRW 노이즈 허용
  ```

### 3.2 Body 추가 (`reconciliation`)
기존 body(summary·byType·monthly·details)에 추가:
```kotlin
"reconciliation" to mapOf(
    "openingBalance" to openingBalance,
    "changes" to byType,                 // 유형별 증감(기존 byType 재사용 · 부호 포함)
    "closingCalculated" to closingCalculated,
    "actualCash" to actualCash,
    "difference" to difference,
    "reconcilable" to reconcilable,
    "reconciled" to reconciled,
),
```
- 조정표 정합: `openingBalance + Σ(byType.amount) == closingCalculated` (byType amount는 부호 포함 → 합 = netFlow). 항상 성립(구성상 항등식) → 화면 "기초+흐름=기말" 표시.
- **차액**은 reconcilable일 때 미포착 환전·이체·특이거래를 드러냄(숨김 금지, AC 준수).
- **한계 note**: 기초는 재구성값(브로커 동기화 등 미기록 현금은 차액에 반영). 과거월(reconcilable=false)은 실제잔고 대조 생략.

## 4. Frontend Design (cashflow-report 상세)

- 타입 `types/cashflow-report.ts` 확장:
  ```ts
  export interface CashflowReconciliation {
    openingBalance: number
    changes: CashflowByTypeRow[]
    closingCalculated: number
    actualCash: number
    difference: number
    reconcilable: boolean
    reconciled: boolean
  }
  ```
  `CashflowReportBody`에 `reconciliation?: CashflowReconciliation` (구 아카이브 호환 위해 옵셔널).
- 신규 컴포넌트 `components/cashflow-report/CashflowReconciliation.tsx`:
  - 조정표: 기초 현금 → 유형별 증감 행(±색) → **기말 현금(계산)**.
  - 정합검증 행: `reconcilable`이면 `reconciled` 시 녹색 "✓ 정합"(차액 0), 불일치 시 적색 + `difference`(±색, "미포착 흐름 추정"); `reconcilable=false`면 회색 "과거 기간 — 실제 잔고 대조 생략".
- `[id]/page.tsx`: summary 다음에 `{body.reconciliation && <CashflowReconciliation data={body.reconciliation} />}` 렌더. 금액은 `fmtKrw`(×100 금지), 부호 색은 `pctColor`.

## 5. Tests

**Backend (unified-asset)** — `CashflowReportGeneratorTest` 확장(fake repos/source):
- 기초 재구성: 기간 이전 입금 100 + 매수 40 → openingBalance = 60. 기간 순흐름 반영 → closingCalculated = 60 + netFlow.
- 정합(reconcilable): period.end 이후 활동 없음 + actualCash == closingCalculated → `reconciled=true`, difference≈0.
- 불일치: actualCash ≠ closingCalculated (미포착 흐름) → `reconciled=false`, difference = 실제−계산.
- reconcilable=false: period.end 이후 flow/trade 존재 → `reconcilable=false`(과거월).
- 항등식: `openingBalance + Σ(changes.amount) == closingCalculated`.
- 기존 테스트 보존(생성자 시그니처 변경 → fake assetRepository/fx 주입, reconciliation 필드 무시하는 기존 단언 유지).

**Frontend**: `npx tsc --noEmit` clean.

## 6. Rollout / 배포 순서
- **스키마 변경 없음** → 운영 마이그레이션 불필요.
- main 병합 → Render 자동배포(BE) → FE 배포.
- 검증: 현금흐름 있는 계정 → CASHFLOW 리포트 생성 → 조정표(기초/증감/기말)·정합검증 badge 확인. 당월(이후 활동 없음)은 reconcilable·차액 표시, 과거월은 대조 생략 안내.

## 7. Affected Files (요약)

**Backend — unified-asset**
- (수정) `application/usecase/CashflowReportGenerator.kt` (assetRepository/fx 주입 + 조정표/정합 계산 + reconciliation body)
- (test 수정) `application/usecase/CashflowReportGeneratorTest.kt`

**Frontend**
- (수정) `types/cashflow-report.ts`
- (신규) `components/cashflow-report/CashflowReconciliation.tsx`
- (수정) `app/unified/reports/cashflow-report/[id]/page.tsx`

## 8. Out of Scope (후속)
- 환전·계좌간이체 유형행(현재 미기록 → 차액에 반영), 특이거래 섹션(미결제·대규모이동·미분류 흐름).
- 워터폴 차트, 통화별 기초/기말 컬럼, 거래일/결제일(Value/Settle) 토글.
- 월초 잔고 스냅샷 인프라(확정 시 재구성 대신 스냅샷 기준 대조).
