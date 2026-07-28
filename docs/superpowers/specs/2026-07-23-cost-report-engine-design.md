# 비용 보고서 생성 엔진 (R-04) — 설계

- 날짜: 2026-07-23
- 태스크: ALLFOLIO 이식 개발 태스크 DB #39 (R1 리포트 MVP, 규모 M, BE 부분) — **1단계: BE 엔진**. FE 화면(SCR-RPT-07)은 엔진 머지 후 2단계 별도.
- 근거 문서: 리포트명세서 R-04 · 화면정의서 SCR-RPT-07 (참조 원본: abor osFeeCost·feeCalc)
- 브랜치: `feat/cost-report-engine`
- 선행: #32 리포트 공통 기반(main), `ReportType.COST` enum(main 등록됨), #33 `GetReturnsAnalysisUseCase`(main)

## 1. 목적

R-04 비용 보고서의 **생성 엔진(BE)**. #32 프레임의 `ReportBodyGenerator`(type=COST)로 등록되어 `POST /api/reports/archive/generate {type: COST, year, month}`로 월간 확정본을 생성·보관한다. 매매수수료·거래세를 브로커/유형/월별로 집계하고 비용률·TER·수익대비를 산출한다. 화면+PDF는 #39 FE 단계.

## 2. 데이터 현실에 따른 v1 섹션 판정

명세 R-04(SCR-RPT-07, 비용 5종) 대비:

| 명세 요소 | v1 | 근거 |
|---|---|---|
| 매매수수료 | ✅ | `ua_stock_trades.fee` (비-DIVIDEND 거래) |
| 거래세·제세금 | ✅ | `ua_stock_trades.tax` (SELL 증권거래세 등, 비-DIVIDEND) |
| 브로커×유형 매트릭스 | ✅ | provider(계좌) × 유형, JOIN `ua_accounts` |
| 월별 추이 | ✅ | `TO_CHAR(traded_at,'YYYY-MM')` |
| 비용률 (총비용/평균NAV) | ✅ null-safe | #33 `GetReturnsAnalysisUseCase.navSeries` 평균 |
| 연환산 TER (비용률×365/일수) | ✅ null-safe | 계산 |
| 수익 대비 비용 | ✅ null-safe | `summary.investmentPnl` (손실/0/부족 시 null) |
| 환전 비용(FX 스프레드) | ❌ 후속 | 환전 거래·기준환율 이력 데이터 부재 |
| 파생·선물 수수료 | ❌ 후속 | 파생 거래유형·전용 fee 필드 없음 |
| 인사이트 자동문구·bp 비교 | ❌ 후속 | 개인화 룰(후속) |

**핵심 규칙 — DIVIDEND 제외**: R-04는 `trade_type != 'DIVIDEND'` 거래만 집계한다. 배당 원천징수(tax)는 R-03(#38) 전담 — 이중집계 방지(명세 처리규칙 #1).

## 3. 구조 (#38 패턴)

#38 배당 엔진과 동일한 헥사고날 구성. 순수 집계 생성기(fake 포트로 단위 테스트) + 포트 + JDBC 어댑터.

### 신규 파일

- **포트** `unified-asset/application/port/CostLedgerSource.kt`
  ```kotlin
  data class CostRecord(
      val tradeDate: LocalDate, val stockName: String, val symbol: String?,
      val accountName: String, val provider: String, val tradeType: String,
      val fee: BigDecimal, val tax: BigDecimal,   // 매매수수료, 거래세 (KRW 취급)
  ) { val total: BigDecimal get() = fee + tax }
  interface CostLedgerSource {
      /** [from, to] 구간의 비-DIVIDEND 거래 비용 (거래일 오름차순) */
      fun findCosts(userId: UUID, from: LocalDate, to: LocalDate): List<CostRecord>
  }
  ```
- **어댑터** `unified-asset/infrastructure/adapter/JdbcCostLedgerSource.kt` — `ua_stock_trades` JOIN `ua_accounts`, `trade_type <> 'DIVIDEND'`, `(fee > 0 OR tax > 0)`.
- **생성기** `unified-asset/application/usecase/CostReportGenerator.kt` — `ReportBodyGenerator`:
  - `override val type = ReportType.COST`
  - 주입: `CostLedgerSource`, `GetReturnsAnalysisUseCase`
  - `generate(userId, period)`:
    - `records = costLedger.findCosts(userId, period.start, period.end)`
    - `analysis = runCatching { returnsAnalysis.analyze(userId, period.start, period.end) }.getOrNull()` (NAV<2 등 예외 흡수)
    - `avgNav` = analysis?.navSeries 평균(있으면), `pnl` = analysis?.summary?.investmentPnl
    - 비용률·TER·수익대비를 null-safe 산출, 본문 JSON 조립, `asOfDate` = 기간 마지막 거래일(없으면 `period.end`)

### 본문 JSON

```json
{
  "summary": {
    "totalCost": …, "brokerFee": …, "tradingTax": …, "tradeCount": …,
    "costRatio": … | null,      // 총비용 / 평균NAV × 100 (0~100, avgNav 없으면 null)
    "annualizedTer": … | null,  // costRatio × 365 / 기간일수 (costRatio 없으면 null)
    "costVsProfit": … | null    // 총비용 / |investmentPnl| × 100 (pnl null/0이면 null)
  },
  "byType":   [ {"type":"매매수수료"|"거래세", "amount":…, "weight":…} ],   // weight=유형비중 0~100
  "byBroker": [ {"broker":…, "fee":…, "tax":…, "total":…, "weight":…} ],   // 브로커×유형 매트릭스
  "monthly":  [ {"month":"YYYY-MM", "brokerFee":…, "tradingTax":…, "total":…} ],
  "details":  [ {"date":…, "account":…, "provider":…, "tradeType":…, "stockName":…, "fee":…, "tax":…} ]
}
```

- **금액**: 통화 컬럼 부재 → KRW 취급
- **스케일**: `costRatio·annualizedTer·costVsProfit·weight`는 0~100 (FE `fmtPctScaled` 컨벤션)
- **기간일수**: `ChronoUnit.DAYS.between(period.start, period.end) + 1`
- **평균 NAV**: `navSeries.fold(ZERO){acc,p -> acc+p.nav} / navSeries.size` (navSeries 비어있으면 null → costRatio null)
- **날짜 직렬화**: `LocalDate` → `.toString()` (mapper에 JSR310 미등록 — #38 선례)

### 빈 데이터 처리

거래 0건은 정상 → 예외 없이 0/빈 배열 유효 보고서. `costRatio`·`costVsProfit`는 분모/데이터 유무에 따라 값 또는 null. as-of = `period.end`.

### 검증 게이트·아카이브

#32 `GenerateReportUseCase` 상속 — sync 상태 게이트가 warnings 부여, `ReportArchive.create` upsert.

## 4. 테스트·검증

`CostReportGeneratorTest` (fake `CostLedgerSource`, 실제/fake `GetReturnsAnalysisUseCase`):
- ① 총비용 = fee + tax 합, brokerFee/tradingTax 분리 정확
- ② **DIVIDEND 제외**: 포트가 비-DIVIDEND만 반환하는 전제 하 집계(포트 계약 검증은 어댑터 스모크)
- ③ byType(매매수수료/거래세) weight 합 ≈ 100
- ④ byBroker 매트릭스: provider별 fee·tax·total
- ⑤ 월별 집계(YYYY-MM) 정렬·유형별 합
- ⑥ 비용률 = 총비용/평균NAV×100; NAV 부족(analyze 예외)이면 costRatio·TER null
- ⑦ 수익대비 = 총비용/|pnl|×100; pnl null 또는 0이면 null
- ⑧ 거래 0건 → 예외 없는 유효 0 보고서

어댑터 SQL은 스모크: 로컬 → BUY/SELL(fee·tax) + DIVIDEND 시드 → `generate type=COST` → 본문 검산(DIVIDEND 비집계 확인), 재생성 upsert.

## 5. 제외 (후속)

환전 비용 추정(환전 거래·기준환율 이력 신규 필요), 파생·선물 수수료, 인사이트 자동문구·브로커 bp 비교, 원통화 병기. FE 화면(SCR-RPT-07)은 #39 2단계.
