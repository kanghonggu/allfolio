# R-05 월간 변동 diff (SCR-RPT-08 ⑤, Phase A) — Design Spec

- **Date**: 2026-07-30
- **Status**: Approved (design), pending implementation
- **Scope**: 월말 보유명세서(R-05)에 **월간 변동**(신규 편입·전량 매도·수량 변동) 추가 — BE 계산 + `HoldingsReportGenerator` 통합 + FE 보유명세 화면. (SCR-RPT-08 ⑤ 월간 변동)
- **Depends on**: R-05 당월 실현손익(#53, merged to main) — `trades`·`realizedBySymbol`·`FifoRealizedPnlCalculator` 재사용. `main`에서 분기.
- **Out of scope (후속)**: 지역별 그룹핑, Excel/ISIN, 통화 정규화.

## 1. Background

`HoldingsReportGenerator`(#40, #53 확장)는 보유 명세·당월 실현손익을 산출한다. 헤더에 "v1 제외: 월간 변동 diff". 명세 SCR-RPT-08 ⑤ 월간 변동: **신규 편입**(당월 첫 매수, 편입일·편입가) / **전량 매도**(보유 종료, 청산일·실현손익) / **수량 변동**(증감 수량·순매수금액).

**전월 스냅샷 부재 블로커**: 거래이력(ua_stock_trades)으로 **period.start 시점 수량을 재구성**해 우회(스냅샷 불필요). #53이 이미 `trades`(전 계좌)·`realizedBySymbol`(당월 FIFO)·`nameBySymbol`을 generate()에서 계산해 둠 → 재사용.

## 2. Decisions (확정)

| 항목 | 결정 |
|---|---|
| start 시점 수량 | `< period.start` 거래 순수량 재구성(스냅샷 불필요) |
| 분류 | 신규편입(qtyBefore≤0 && qtyEnd>0) / 전량매도(qtyBefore>0 && qtyEnd≤0) / 수량변동(qtyBefore>0 && qtyEnd>0 && 기간순수량≠0) |
| 라운드트립 | 당월 편입+청산(qtyBefore≤0 && qtyEnd≤0 && 기간 매수 존재) → **전량매도**로 분류(실현손익 표시) |
| 전량매도 실현손익 | `realizedBySymbol`(#53 당월 FIFO) 재사용 |
| 수량 판정 | BUY/CREDIT_BUY +, SELL/CREDIT_SELL −, DIVIDEND/MARGIN·period.end 이후 제외 |
| 통화 | KRW 취급(순매수금액), 편입가는 원통화(기존 관례) |
| 커버리지 | 수동 STOCK 계좌 거래만(브로커 동기화 → 변동 없음) |

## 3. Backend Design (module: `unified-asset`)

### 3.1 월간 변동 계산기 (신규, 순수)
`application/usecase/MonthlyChangeCalculator.kt`:
```kotlin
data class NewEntry(val symbol: String, val name: String, val firstBuyDate: LocalDate, val buyPrice: BigDecimal)
data class SoldOut(val symbol: String, val name: String, val soldOutDate: LocalDate, val realizedPnl: BigDecimal)
data class QtyChange(val symbol: String, val name: String, val netQty: BigDecimal, val netBuyAmount: BigDecimal)
data class MonthlyChange(val newEntries: List<NewEntry>, val soldOut: List<SoldOut>, val qtyChanges: List<QtyChange>)

object MonthlyChangeCalculator {
    fun build(
        trades: List<StockTrade>,
        period: ReportPeriod,
        realizedBySymbol: Map<String, BigDecimal>,
        nameBySymbol: Map<String, String>,
    ): MonthlyChange { ... }
}
```
- 심볼별(BUY/SELL류·symbol non-null 거래 그룹):
  - `qtyBefore` = `tradedAt < period.start` 순수량. `qtyEnd` = `tradedAt ≤ period.end` 순수량. `periodTrades` = `[start, end]` 거래(오름차순).
  - `periodTrades` 비면 skip(변동 없음).
  - **신규편입**: `qtyBefore ≤ 0 && qtyEnd > 0` → firstBuyDate=기간 첫 BUY tradedAt, buyPrice=그 BUY price.
  - **전량매도**: `qtyEnd ≤ 0 && (qtyBefore > 0 || periodTrades.any{BUY})` → soldOutDate=기간 내 수량 0 도달 마지막 날짜(없으면 마지막 거래일), realizedPnl=`realizedBySymbol[sym] ?: 0`.
  - **수량변동**: `qtyBefore > 0 && qtyEnd > 0 && (qtyEnd − qtyBefore) ≠ 0` → netQty=qtyEnd−qtyBefore, netBuyAmount=Σ(기간 BUY totalAmount) − Σ(기간 SELL totalAmount).
  - (분류는 상호배타: 위 순서로 첫 매칭. 신규편입 vs 전량매도는 qtyEnd 부호로 갈림.)
- name = `nameBySymbol[sym] ?: sym`. 각 리스트 정렬(예: soldOut·newEntry는 날짜, qtyChange는 |netBuyAmount| 내림차순 — 구현 시 결정).

### 3.2 생성기 통합 — `HoldingsReportGenerator`
- generate()에서 이미 계산된 `trades`, `realizedBySymbol`, `nameBySymbol` 활용:
  ```kotlin
  val monthlyChange = MonthlyChangeCalculator.build(trades, period, realizedBySymbol, nameBySymbol)
  ```
- body에 추가(신규 주입·생성자 시그니처 변경 없음):
  ```kotlin
  "monthlyChange" to mapOf(
      "newEntries" to monthlyChange.newEntries.map { mapOf("symbol" to it.symbol, "name" to it.name,
          "firstBuyDate" to it.firstBuyDate.toString(), "buyPrice" to it.buyPrice) },
      "soldOut" to monthlyChange.soldOut.map { mapOf("symbol" to it.symbol, "name" to it.name,
          "soldOutDate" to it.soldOutDate.toString(), "realizedPnl" to it.realizedPnl) },
      "qtyChanges" to monthlyChange.qtyChanges.map { mapOf("symbol" to it.symbol, "name" to it.name,
          "netQty" to it.netQty, "netBuyAmount" to it.netBuyAmount) },
  ),
  ```
- `note`는 유지(#53의 "당월 실현손익..." 문구). 자산 0(빈 보고서) 경로에도 `monthlyChange` 빈 구조 포함.

## 4. Frontend Design (보유명세 상세)

- 타입 `types/holdings-report.ts` 확장:
  ```ts
  export interface HoldingNewEntry { symbol: string; name: string; firstBuyDate: string; buyPrice: number }
  export interface HoldingSoldOut { symbol: string; name: string; soldOutDate: string; realizedPnl: number }
  export interface HoldingQtyChange { symbol: string; name: string; netQty: number; netBuyAmount: number }
  export interface HoldingMonthlyChange { newEntries: HoldingNewEntry[]; soldOut: HoldingSoldOut[]; qtyChanges: HoldingQtyChange[] }
  ```
  `HoldingsReportBody`에 `monthlyChange?: HoldingMonthlyChange`(옵셔널 — 구 아카이브 호환).
- 신규 컴포넌트 `components/holdings-report/MonthlyChange.tsx`: 3 하위 표(신규 편입 / 전량 매도[실현손익 ±색] / 수량 변동[증감·순매수금액 ±색]). 각 빈 배열이면 해당 표 생략, 셋 다 비면 섹션 자체 생략(또는 "변동 없음").
- `[id]/page.tsx`: `{body.monthlyChange && <MonthlyChange data={body.monthlyChange} />}` (예: HoldingsGrid 다음). 금액 `fmtKrw`(×100 금지), 수량 `.toLocaleString`/기존 num 포맷.

## 5. Tests

**Backend (unified-asset)** — `MonthlyChangeCalculatorTest`(순수):
- 신규편입: qtyBefore 0 + 당월 매수 → newEntries(firstBuyDate·buyPrice).
- 전량매도: qtyBefore>0 + 당월 전량매도 → soldOut(soldOutDate·realizedPnl from map).
- 수량변동: qtyBefore>0 유지 + 당월 추가매수 → qtyChanges(netQty·netBuyAmount).
- 라운드트립: 당월 매수+전량매도 → soldOut로 분류.
- 거래 없음/기간 밖만 → 빈 결과. DIVIDEND/MARGIN 무시.

**Backend** — `HoldingsReportGeneratorTest` 확장: monthlyChange 섹션(newEntries/soldOut/qtyChanges) 검증. 기존 테스트는 빈 trades로 monthlyChange 빈 구조만 추가되고 불변.

**Frontend**: `npx tsc --noEmit` clean.

## 6. Rollout / 배포 순서
- **스키마 변경 없음** → 운영 마이그레이션 불필요.
- main 병합 → Render 자동배포(BE) → FE 배포.
- 검증: 수동 STOCK 계좌 당월 매수·매도 거래 계정 → HOLDINGS 리포트 → 월간 변동(신규편입·전량매도·수량변동) 확인.

## 7. Affected Files (요약)

**Backend — unified-asset**
- (신규) `application/usecase/MonthlyChangeCalculator.kt`
- (수정) `application/usecase/HoldingsReportGenerator.kt` (monthlyChange 통합 — 기존 trades/realized 재사용)
- (test 신규) `application/usecase/MonthlyChangeCalculatorTest.kt`
- (test 수정) `application/usecase/HoldingsReportGeneratorTest.kt`

**Frontend**
- (수정) `types/holdings-report.ts`
- (신규) `components/holdings-report/MonthlyChange.tsx`
- (수정) `app/unified/reports/holdings-report/[id]/page.tsx`

## 8. Out of Scope (후속)
- 지역별 그룹핑(북미·유럽·아태·일본), Excel 출력·ISIN.
- 통화 정규화(외화 종목 순매수금액·편입가 원통화 정확도) — KRW/원통화 혼용 한계 유지.
- 브로커 동기화 계좌 거래이력 기반 변동(거래행 부재).
