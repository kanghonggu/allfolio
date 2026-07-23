# 배당·이자 보고서 생성 엔진 (R-03) — 설계

- 날짜: 2026-07-23
- 태스크: ALLFOLIO 이식 개발 태스크 DB #38 (R1 리포트 MVP, 규모 M, BE 부분) — **1단계: BE 엔진**. FE 화면(SCR-RPT-05)은 엔진 머지 후 2단계 별도.
- 근거 문서: 리포트명세서 R-03 · 화면정의서 SCR-RPT-05 (참조 원본: abor 해외자산통계 배당금 수령현황·divTax)
- 브랜치: `feat/dividend-report-engine`
- 선행: #32 리포트 공통 기반(main), `ReportType.DIVIDEND_INTEREST` enum(main 등록됨)

## 1. 목적

R-03 배당·이자 보고서의 **생성 엔진(BE)**. #32 프레임의 `ReportBodyGenerator`(type=DIVIDEND_INTEREST)로 등록되어 `POST /api/reports/archive/generate {type: DIVIDEND_INTEREST, year, month}`로 월간 확정본을 생성·보관한다. 세전·원천징수·세후를 본문에 고정한다. 화면+PDF는 #38 FE 단계.

## 2. 데이터 현실에 따른 v1 섹션 판정

명세 R-03(SCR-RPT-05 7블록) 대비:

| 명세 블록 | v1 | 근거 |
|---|---|---|
| 요약 카드 (세전·원천징수+실효세율·세후·TTM수익률) | ✅ | `ua_stock_trades` 집계 + 현재 평가액(AssetRepository) |
| 수취 내역 (지급일·종목·계좌·세전·원천징수·세후) | ✅ | `ua_stock_trades` DIVIDEND 행 + `tax` 컬럼(1급 필드) |
| 월별 추이 (월별 세후) | ✅ | `TO_CHAR(traded_at,'YYYY-MM')` 집계 |
| 종목별 집계 | ✅ | 종목별 세전·징수·세후·비중 |
| 국가별 원천징수 요약 | ✅ 근사 | 종목 상장국 마스터 부재 → **티커 패턴 근사**: 숫자 티커=국내, 영문 티커=해외 2버킷 |
| 세율 마스터·기대세율 비교 (SCR-RPT-06 ADMIN) | ❌ 후속 | 신규 tax_rate 마스터·버저닝 + **ADMIN role(#3) 선행 필요**. 명세도 R2 의존 표기. v1은 실제 징수액만(abor 규칙 #2: 표시는 항상 실제 징수 기준) |
| 이자 섹션 | ❌ 후속 | `StockTradeType`에 INTEREST 없음 — 데이터 소스 부재 |
| 배당 캘린더(예상) | ❌ 후속 | 배당 스케줄 데이터 없음. 기준수량/DPS도 락일 보유수량 부재로 제외 |

## 3. 구조 (포트 기반 — 테스트 용이성)

기존 `DividendReportService`(raw JdbcTemplate, 세금 미포함, live `/reports/dividend` 전용)는 **그대로 유지**. 신규 엔진은 #36 `MonthlyReportGenerator`의 헥사고날 스타일을 따라 **순수 집계 + JDBC 어댑터 분리**로 구성해 fake 포트로 단위 테스트한다.

### 신규 파일

- **포트** `unified-asset/application/port/DividendLedgerSource.kt`
  ```kotlin
  data class DividendRecord(
      val payDate: LocalDate, val stockName: String, val symbol: String?,
      val accountName: String, val provider: String,
      val gross: BigDecimal, val tax: BigDecimal,   // 세전, 원천징수 (KRW 취급)
  )
  interface DividendLedgerSource {
      /** [from, to] 구간의 배당 수취 기록 (지급일 오름차순) */
      fun findDividends(userId: UUID, from: LocalDate, to: LocalDate): List<DividendRecord>
  }
  ```
- **어댑터** `unified-asset/infrastructure/adapter/JdbcDividendLedgerSource.kt` — `ua_stock_trades` JOIN `ua_accounts`로 `trade_type='DIVIDEND'` 조회. `net = gross - tax`는 도메인/집계에서 계산.
- **생성기** `unified-asset/application/usecase/DividendInterestReportGenerator.kt` — `ReportBodyGenerator` 구현:
  - `override val type = ReportType.DIVIDEND_INTEREST`
  - 주입: `DividendLedgerSource`, `AssetRepository`, `FxConverter`
  - `generate(userId, period)`:
    - 기간 배당 = `findDividends(userId, period.start, period.end)`
    - TTM 배당 = `findDividends(userId, period.end.minusYears(1), period.end)` (수익률 카드용)
    - 현재 평가액(KRW) = `assetRepository.findByUserId` → `currentValueInKrw(fx)` 합
    - 순수 집계로 본문 JSON 조립, `asOfDate` = 기간 내 마지막 지급일(없으면 `period.end`)

### 본문 JSON

```json
{
  "summary": {
    "grossTotal": …, "withholdingTax": …, "netTotal": …,
    "effectiveTaxRate": …,   // 징수/세전 × 100 (0~100 스케일, 세전 0이면 0)
    "receiptCount": …,
    "ttmYield": … | null     // TTM 세후 / 현재 평가액 × 100 (0~100 스케일, 평가액 0이면 null)
  },
  "receipts": [ {"payDate":…, "stockName":…, "symbol":…, "account":…, "gross":…, "tax":…, "net":…} ],
  "monthly":  [ {"month":"YYYY-MM", "net":…} ],
  "bySymbol": [ {"stockName":…, "symbol":…, "gross":…, "tax":…, "net":…, "weight":…} ],  // weight=세후비중 0~100
  "byCountry":[ {"country":"국내"|"해외", "gross":…, "tax":…, "net":…, "effectiveTaxRate":…} ]
}
```

- **금액**: `ua_stock_trades`에 통화 컬럼 없음 → 기존 `DividendReportService`와 동일하게 **KRW 취급**(원통화 병기는 후속)
- **퍼센트 스케일**: `effectiveTaxRate`·`weight`·`ttmYield`는 **0~100 스케일**로 산출(FE에서 `fmtPctScaled`/`.toFixed` 대상 — #37 컨벤션 승계). `null`은 데이터 부재 표기
- **국가 근사**: `symbol`이 `^[0-9]+$`이면 "국내", 그 외/공백이면 "해외"

### 빈 데이터 처리

배당 0건은 **정상 상태** → #36과 달리 `InsufficientDataException`을 던지지 않는다. `summary`는 0/`ttmYield`는 평가액 유무에 따라 값 또는 null, 배열들은 빈 배열. as-of = `period.end`.

### 검증 게이트·아카이브

#32 `GenerateReportUseCase` 상속 — sync 상태 게이트가 warnings 부여, `ReportArchive.create`로 upsert. 엔진은 게이트를 알지 못함(관심사 분리).

## 4. 테스트·검증

`DividendInterestReportGeneratorTest` (fake `DividendLedgerSource`/`AssetRepository`/`FxConverter`):
- ① 세전·원천징수·세후 합계 + 실효세율(징수/세전×100) 정확
- ② `net = gross − tax` 행별 정합
- ③ 종목별 weight(세후비중) 합 ≈ 100
- ④ 월별 집계(YYYY-MM) 정렬·세후 합
- ⑤ 국가 근사: 숫자 티커→국내, 영문 티커→해외 버킷팅
- ⑥ **배당 0건 → 예외 없이 0/빈 배열 유효 보고서**, ttmYield는 평가액 있으면 0, 없으면 null
- ⑦ TTM 수익률 = TTM 세후 / 평가액 × 100

어댑터 SQL은 스모크로 검증: 로컬 → DIVIDEND 시드(tax 포함) → `generate type=DIVIDEND_INTEREST` → 본문 검산, 재생성 upsert.

## 5. 제외 (후속)

세율 마스터·기대세율 비교(SCR-RPT-06, ADMIN role #3 선행), 이자 섹션, 배당 캘린더·예상, 원통화 병기, 기준수량/DPS, 전년 동월 비교. FE 화면(SCR-RPT-05)은 #38 2단계.
