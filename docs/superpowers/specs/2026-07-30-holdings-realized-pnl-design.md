# R-05 당월 실현손익 (FIFO) 후속 — Design Spec

- **Date**: 2026-07-30
- **Status**: Approved (design), pending implementation
- **Scope**: 월말 보유명세서(R-05)에 **당월 FIFO 실현손익** 추가 — BE 계산기 + `HoldingsReportGenerator` 통합 + FE 보유명세 화면 표시. (SCR-RPT-08 그리드 컬럼 10 "당월 실현손익")
- **Depends on**: 없음 — `main`에서 분기. (`trade` 모듈은 main 상시 존재.)
- **Out of scope (후속)**: 월간 변동 diff(신규편입·전량매도·수량변동 — 전월 확정 스냅샷 필요), 지역별 그룹핑(지역 마스터 부재), Excel/ISIN.

## 1. Background

`HoldingsReportGenerator`(R-05, #40)는 현재 `ua_assets` 현재보유만 사용하며 헤더에 "v1 제외: 당월 실현손익(FIFO)"를 명시. 유예 사유: "unified 경로에 FIFO 실현손익 엔진 미구현(레거시 pnl PositionEngine은 trade_raw 전용)". 명세서 SCR-RPT-08 그리드 컬럼 10 = **당월 매도분 FIFO 실현손익(KRW, ±색)**.

`trade` 모듈에 검증된 순수 도메인 `FifoCostEngine`(BUY: lot 추가 / SELL: FIFO 소진 + 실현손익 누적)이 이미 존재한다. 이 spec은 그걸 unified 경로에 재사용해 당월 실현손익을 산출·표시한다.

## 2. Decisions (확정)

| 항목 | 결정 |
|---|---|
| FIFO 재사용 | unified-asset → `trade` 모듈 의존(순환 없음). `FifoCostEngine.apply()` fold 재사용(TradeRaw 불필요) |
| 거래 데이터 | `ua_stock_trades`(수동 STOCK 계좌). `StockTradeRepository.findByAccountId` 사용자 계좌 순회. 신규 JDBC 소스 불필요 |
| 기간 의미 | **당월**(period.start~end). 심볼별 2-스냅샷 diff(start 직전 누적 realized 스냅샷) |
| 통화 | ua_stock_trades 통화 컬럼 부재 → **KRW 취급**(R-03/R-04 관례 동일) |
| 커버리지 | BUY/SELL 거래행 있는 심볼만(수동 STOCK). 브로커 동기화 보유 → 0. note 명시 |
| 표시 | holdings 컬럼 + summary 합계 + 별도 `realized` 섹션(전량매도 종목 포착) |

## 3. Backend Design (module: `unified-asset`)

### 3.0 Module dependency
`unified-asset/build.gradle.kts`에 추가:
```kotlin
implementation(project(":trade"))
```
(현재 common/esg/report만 의존. trade는 common만 의존 → 순환 없음. 사용 심볼: `com.allfolio.trade.domain.{FifoCostEngine, LotPosition, TradeType}` — 모두 순수 도메인.)

### 3.1 FIFO 실현손익 계산기 (신규, 순수)
`application/usecase/FifoRealizedPnlCalculator.kt` (object, DB 무관 순수 로직):
```kotlin
object FifoRealizedPnlCalculator {
    private val BUY_TYPES = setOf(StockTradeType.BUY, StockTradeType.CREDIT_BUY)
    private val SELL_TYPES = setOf(StockTradeType.SELL, StockTradeType.CREDIT_SELL)

    /** 심볼별 당월(period) FIFO 실현손익(KRW). trades는 전 계좌·전 기간(≤end) 혼합 허용. */
    fun calculate(trades: List<StockTrade>, period: ReportPeriod): Map<String, BigDecimal> =
        trades
            .filter { it.symbol != null && !it.tradedAt.isAfter(period.end) && (it.tradeType in BUY_TYPES || it.tradeType in SELL_TYPES) }
            .groupBy { it.symbol!! }
            .mapValues { (_, ts) -> monthRealized(ts, period) }

    private fun monthRealized(symbolTrades: List<StockTrade>, period: ReportPeriod): BigDecimal {
        val asc = symbolTrades.sortedWith(compareBy({ it.tradedAt }, { it.createdAt }))
        var pos = LotPosition.EMPTY
        var realizedBeforeStart = BigDecimal.ZERO
        var crossed = false
        for (t in asc) {
            if (!crossed && !t.tradedAt.isBefore(period.start)) { realizedBeforeStart = pos.realizedPnl; crossed = true }
            val tt = if (t.tradeType in BUY_TYPES) TradeType.BUY else TradeType.SELL
            pos = FifoCostEngine.apply(pos, tt, t.quantity, t.price, t.fee)
        }
        if (!crossed) realizedBeforeStart = pos.realizedPnl   // 전부 기간 이전 → 당월 0
        return pos.realizedPnl - realizedBeforeStart
    }
}
```
- **경계 정확성**: start 직전까지의 누적 realized를 스냅샷하고 최종에서 빼므로, 당월 매도가 이전 매수 lot을 소진해도 그 lot 원가가 정확히 반영된다.
- 과매도 clamp·수수료 차감은 `FifoCostEngine` 계약을 그대로 승계.

### 3.2 생성기 통합 — `HoldingsReportGenerator`
- 생성자에 `stockTradeRepository: StockTradeRepository` 추가.
- 거래 수집: `accounts.flatMap { stockTradeRepository.findByAccountId(it.id) }` → `FifoRealizedPnlCalculator.calculate(trades, period)` → `Map<symbol, 당월realized>`.
- 본문 추가:
  - `summary.realizedPnlKrw` = 맵 값 합계(전 심볼, **전량매도 포함**).
  - `holdings[].realizedPnl` = `realizedBySymbol[a.symbol] ?: 0`.
  - 신규 `realized` 섹션: 당월 realized ≠ 0 인 심볼 `[{symbol, name, realizedPnl}]`, `realizedPnl` 내림차순. name은 해당 심볼 최신 거래의 `stockName`(현재보유면 asset.name과 동일). 전량매도로 ua_assets에 없는 심볼도 포함.
  - `note`에 "당월 실현손익은 수동 입력 거래(ua_stock_trades) 기준" 한계 추가.
- 기존 섹션(holdings 정렬·byAccount·byType·cash·unrealizedPnlKrw)·asOf·pct 헬퍼 유지.

## 4. Frontend Design (보유명세 화면)

- 대상: `app/unified/reports/holdings-report/[id]/page.tsx`(상세 뷰어) + `types/holdings-report.ts`(본문 타입).
- 타입 확장: `HoldingsReportBody.summary.realizedPnlKrw: number`, `holdings[].realizedPnl: number`, 신규 `realized: { symbol; name; realizedPnl }[]`.
- 표시:
  - 요약에 **당월 실현손익 합계** 카드/항목(±색, KRW).
  - 보유 명세 그리드에 **당월 실현손익** 컬럼 추가(±색; 0은 회색/`-`).
  - (선택) realized 섹션을 별도 소표로 렌더(전량매도 포착) — 최소 요약 합계 + 그리드 컬럼이면 충분, 섹션은 여력 시.
- 스케일: realizedPnl은 KRW 금액 → 통화 포맷 그대로(×100 금지). 기존 unrealizedPnl 표기 패턴 재사용.

## 5. Tests

**Backend (unified-asset)**
- `FifoRealizedPnlCalculatorTest`(순수): (a) 당월 매수 후 부분매도 → FIFO 실현손익 정확. (b) **이전월 매수 + 당월 매도** → 당월 realized가 옛 lot 원가로 계산(경계 스냅샷 검증). (c) 당월 전량매도 → 심볼 realized 유지(현재보유 아님). (d) `DIVIDEND`/`MARGIN` 제외. (e) 매도 없음/거래 없음 → 0. (f) `CREDIT_BUY`/`CREDIT_SELL` 매핑.
- `HoldingsReportGeneratorTest` 확장: fake `StockTradeRepository`로 holdings realized 컬럼 + summary.realizedPnlKrw + realized 섹션(전량매도 포함) 검증. 기존 테스트는 빈 fake 주입으로 보존(생성자 시그니처 변경 대응).

**Frontend**: `npx tsc --noEmit` clean.

## 6. Rollout / 배포 순서
- **스키마 변경 없음**(기존 ua_stock_trades 재사용) → 운영 마이그레이션 불필요.
- main 병합 → Render 자동배포(BE) → FE 배포.
- 검증: 수동 STOCK 계좌에 당월 매수·매도 거래가 있는 계정으로 HOLDINGS 리포트 생성 → 본문 `summary.realizedPnlKrw`·`holdings[].realizedPnl`·`realized` 반영, FE 그리드 당월 실현손익 컬럼 확인.

## 7. Affected Files (요약)

**Backend — unified-asset**
- (신규) `application/usecase/FifoRealizedPnlCalculator.kt`
- (수정) `application/usecase/HoldingsReportGenerator.kt` (stockTradeRepository 주입 + realized 통합)
- (수정) `build.gradle.kts` (`implementation(project(":trade"))`)
- (test 신규) `application/usecase/FifoRealizedPnlCalculatorTest.kt`
- (test 수정) `application/usecase/HoldingsReportGeneratorTest.kt`

**Frontend**
- (수정) `types/holdings-report.ts`
- (수정) `app/unified/reports/holdings-report/[id]/page.tsx`

## 8. Out of Scope (후속)
- 월간 변동 diff(신규편입·전량매도·수량변동) — 전월 확정 월말 스냅샷 인프라 필요.
- 지역별 그룹핑(북미·유럽·아태·일본·기타) — 상장국·지역 마스터 필요.
- Excel 출력·ISIN·예수금 원장.
- ua_stock_trades 통화 정규화(외화 종목 실현손익의 원통화 정확도) — 현재 KRW 취급 한계 유지.
- 브로커 동기화 계좌의 거래이력 기반 실현손익(거래행 부재) — 현행 커버리지 밖.
