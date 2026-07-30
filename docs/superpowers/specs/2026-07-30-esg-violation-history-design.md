# R-07 위반 이력 (SCR-RPT-10) 후속 — Design Spec

- **Date**: 2026-07-30
- **Status**: Approved (design), pending implementation
- **Scope**: ESG 스크리닝 보고서(R-07)에 **위반 이력 타임라인 + 편입일/등록전후 배지** 추가 — BE 계산 + `EsgScreeningReportGenerator` 통합 + FE esg-screening 화면. (SCR-RPT-10 ③ 편입일 · ④ 위반 이력 타임라인)
- **Depends on**: 배제리스트(#52, merged to main) — 위반 판정 소스(프리셋 ∪ 유저 active 리스트) + 리스트 등록일(`ua_exclusion_items.added_at`). `main`에서 분기.
- **Out of scope (후속)**: 리스트 제외 이벤트(#52 하드삭제 → 감사이력 부재), 신규매수 감시 알림 로그(알림 인프라 부재), 국가/ISIN 정밀 매칭, 대규모 이동 플래그.

## 1. Background

`EsgScreeningReportGenerator`(#42, #52 확장)는 프리셋 ∪ 유저 active 리스트로 위반 종목(현재 보유 ∩ 배제소스)을 산출(`violations`, `screening`)한다. 헤더에 "제외(후속): 위반 이력·감시로그·편입일"을 명시. 명세 SCR-RPT-10: ③ 그리드 컬럼 5 "편입일 — 리스트 등록 전부터 보유 vs 등록 후 매수 배지", ④ 위반 이력 타임라인 "발생/해소 이벤트(신규 매수/리스트 추가/전량 매도/리스트 제외)".

**데이터 제약**: 편입/청산은 `ua_stock_trades` 수량추적(수동 STOCK 계좌만). 리스트 등록일은 #52 `added_at`. 리스트 제외·감시 알림은 추적 인프라 부재 → 후속.

## 2. Decisions (확정)

| 항목 | 결정 |
|---|---|
| 위반 판정 소스 | 프리셋 ∪ 유저 active 리스트(기존 lookup 재사용) |
| 이벤트 범위 | **편입**(qty 0→+, 첫 매수)·**청산**(qty +→0)·**리스트등록**(유저리스트 added_at). 리스트제외·감시알림 후속 |
| 편입/청산 소스 | `ua_stock_trades` 수량추적(BUY/CREDIT_BUY +, SELL/CREDIT_SELL −, DIVIDEND/MARGIN 제외). FIFO 불필요 |
| 커버리지 | 거래이력 있는 심볼만(수동 STOCK). 브로커 동기화 → 이벤트 없음 |
| 등록전후 배지 | 유저리스트: firstBuy < added_at → "등록전보유", ≥ → "등록후매수"; 프리셋 → "프리셋"; 거래없음 → "-" |
| 통화 | 금액 비관여(이벤트는 날짜·종목 기준) |

## 3. Backend Design (module: `unified-asset`)

### 3.1 위반 이력 계산기 (신규, 순수)
`application/usecase/ViolationHistoryCalculator.kt`:
```kotlin
data class SymbolViolationInfo(val firstBuyDate: LocalDate?, val sinceListed: String)
data class ViolationEvent(val date: LocalDate, val symbol: String, val name: String, val event: String, val note: String)
data class ViolationHistory(val perSymbol: Map<String, SymbolViolationInfo>, val events: List<ViolationEvent>)

object ViolationHistoryCalculator {
    // sourceSymbols: 배제소스 심볼 집합(lookup.keys). nameBySymbol: 표시명(거래 stockName 우선).
    // listedAtBySymbol: 유저리스트 등록일(min added_at.toLocalDate). trades: 사용자 전체 StockTrade.
    fun build(
        sourceSymbols: Set<String>,
        trades: List<StockTrade>,
        listedAtBySymbol: Map<String, LocalDate>,
        nameBySymbol: Map<String, String>,
        period: ReportPeriod,
    ): ViolationHistory { ... }
}
```
- 심볼별(sourceSymbols ∩ 거래보유): `tradedAt ≤ period.end` 거래를 (tradedAt, createdAt) 오름차순으로 수량추적:
  - `BUY`/`CREDIT_BUY`: qty += quantity. qty가 0(이하)→양수 전환 시 **편입** 이벤트(그 날짜).
  - `SELL`/`CREDIT_SELL`: qty −= quantity. qty가 양수→0(이하) 전환 시 **청산** 이벤트.
  - `DIVIDEND`/`MARGIN` 제외.
  - `firstBuyDate` = 첫 편입 날짜(없으면 null).
- 리스트등록 이벤트: `listedAtBySymbol[sym]` 존재하고 `≤ period.end` 면 **리스트등록**(그 날짜).
- `sinceListed`: 위 배지 규칙.
- `events`: 전 심볼 이벤트 flatten, `date` 오름차순(동일 날짜는 symbol/event 보조정렬).
- `perSymbol`: sourceSymbols ∩ (거래 있음 또는 listedAt 있음)에 대한 `SymbolViolationInfo`.

### 3.2 생성기 통합 — `EsgScreeningReportGenerator`
- 생성자에 `accountRepository: AccountRepository`, `stockTradeRepository: StockTradeRepository` 추가.
- 기존 `lookup`(프리셋 ∪ 유저 active) 구성 이후:
  ```kotlin
  val trades = accountRepository.findByUserId(userId).flatMap { stockTradeRepository.findByAccountId(it.id) }
  val listedAtBySymbol = exclusionRepo.findActiveByUser(userId)
      .flatMap { it.items }.groupBy { it.symbol }
      .mapValues { (_, items) -> items.minOf { it.addedAt.toLocalDate() } }
  val nameBySymbol = /* 위반 asset.name + trade.stockName 병합, asset 우선 */
  val history = ViolationHistoryCalculator.build(lookup.keys, trades, listedAtBySymbol, nameBySymbol, period)
  ```
- `violations` 각 항목에 `firstBuyDate`·`sinceListed` 추가(`history.perSymbol[symbol]`, 없으면 null/"-").
- Body에 신규 `violationHistory` 추가: `history.events.map { {date, symbol, name, event, note} }`.
- 총평가액 0(emptyReport) 경로는 `violationHistory: []` 포함.

## 4. Frontend Design (esg-screening 상세)

- 타입 `types/esg-screening.ts`(또는 해당 리포트 바디 타입) 확장:
  - `violations[]`에 `firstBuyDate: string | null`, `sinceListed: string` 추가.
  - 신규 `ViolationEvent { date, symbol, name, event, note }`, body에 `violationHistory: ViolationEvent[]`(옵셔널 — 구 아카이브 호환).
- violations 그리드(SCR-RPT-10 결과 화면)에 **편입일** 컬럼 + `sinceListed` 배지("등록전보유"/"등록후매수"/"프리셋").
- 신규 **위반 이력 타임라인** 섹션: 날짜·종목·이벤트(편입/청산/리스트등록 색상 태그)·비고. `violationHistory` 없거나 빈 배열이면 "이력 없음" 안내.
- 진입: 기존 esg-screening 상세 뷰어(`app/unified/reports/esg-screening/[id]/page.tsx`)에 섹션 추가.

## 5. Tests

**Backend (unified-asset)** — `ViolationHistoryCalculatorTest`(순수):
- 편입: 첫 매수 → 편입 이벤트·firstBuyDate.
- 청산: 전량 매도 → 청산 이벤트.
- 재편입: 매수→전량매도→재매수 → 편입·청산·편입 3이벤트.
- 리스트등록: listedAt 존재 → 리스트등록 이벤트.
- 배지: firstBuy < listedAt → "등록전보유", ≥ → "등록후매수", 프리셋(listedAt 없음) → "프리셋", 거래없음 → "-".
- DIVIDEND/MARGIN 제외, period.end 이후 거래 제외.

**Backend** — `EsgScreeningReportGeneratorTest` 확장: fake account/stockTrade repo로 violations 편입일/배지 + violationHistory 섹션 검증. 기존 테스트는 빈 fake 주입으로 보존(생성자 시그니처 변경 대응, violationHistory 빈 배열).

**Frontend**: `npx tsc --noEmit` clean.

## 6. Rollout / 배포 순서
- **스키마 변경 없음**(기존 ua_stock_trades·ua_exclusion 재사용) → 운영 마이그레이션 불필요.
- main 병합 → Render 자동배포(BE) → FE 배포.
- 검증: 수동 STOCK 계좌 + 배제리스트에 보유 심볼 등록 → ESG 스크리닝 리포트 생성 → violations 편입일/배지 + 위반 이력 타임라인(편입/청산/리스트등록) 확인.

## 7. Affected Files (요약)

**Backend — unified-asset**
- (신규) `application/usecase/ViolationHistoryCalculator.kt`
- (수정) `application/usecase/EsgScreeningReportGenerator.kt` (account/stockTrade repo 주입 + history 통합)
- (test 신규) `application/usecase/ViolationHistoryCalculatorTest.kt`
- (test 수정) `application/usecase/EsgScreeningReportGeneratorTest.kt`

**Frontend**
- (수정) esg-screening 바디 타입 파일 + 상세 페이지 `app/unified/reports/esg-screening/[id]/page.tsx`
- (신규 또는 인라인) 위반 이력 타임라인 렌더

## 8. Out of Scope (후속)
- 리스트 제외 이벤트 — #52 하드삭제(감사이력 없음). 소프트삭제/감사 인프라 필요.
- 신규매수 감시 알림 로그(⑤) — 알림 발송 인프라 부재.
- 국가/ISIN 정밀 매칭, 대규모 이동 플래그(자산 대비 임계치).
- 브로커 동기화 계좌 거래이력 기반 이벤트(거래행 부재).
