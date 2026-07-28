# 현금흐름 보고서 생성 엔진 (R-06) — 설계

- 날짜: 2026-07-28
- 태스크: ALLFOLIO 이식 개발 태스크 DB #41 (R2 확장, 규모 M, BE 부분) — **1단계: BE 엔진**. FE 화면(SCR-RPT-09)은 엔진 머지 후 2단계 별도.
- 근거 문서: 리포트명세서 R-06 · 화면정의서 SCR-RPT-09 (참조 원본: abor PD Cash 템플릿)
- 브랜치: `feat/cashflow-report-engine` (main에서 분기)
- 선행: #32 리포트 공통 기반(main), `ReportType.CASHFLOW` enum(main 등록됨), #33 `cash_flow` 원장·`CashFlowRepository`(main)

## 1. 목적

R-06 현금흐름 보고서의 **생성 엔진(BE)**. #32 프레임의 `ReportBodyGenerator`(type=CASHFLOW)로 등록되어 `POST /api/reports/archive/generate {type: CASHFLOW, year, month}`로 현금흐름 확정본을 생성·보관한다. 유형별 순증감(입금·출금·매수·매도·배당·수수료)과 순현금흐름·상세내역을 본문에 고정한다. 화면+PDF는 #41 FE 단계.

## 2. 데이터 현실에 따른 v1 섹션 판정

명세 R-06(SCR-RPT-09) 대비:

| 명세 요소 | v1 | 근거 |
|---|---|---|
| 유형별 순증감 (입금·출금·매수·매도·배당·수수료) | ✅ | `cash_flow` + `ua_stock_trades` |
| 순현금흐름 | ✅ | 유입 − 유출 |
| 월별 추이 (유입·유출·순흐름) | ✅ | 월별 집계 |
| 상세 내역 (거래일·계좌·유형·설명·금액) | ✅ | cash_flow + trades 통합 |
| 워터폴 (유형별) | ✅ | byType 부호 → FE 워터폴 |
| **기초/기말 조정표 + 정합 검증** | ❌ 후속 | 월초 현금잔고 스냅샷 부재 → 기초 잔고 불가(R-06 헤드라인 AC이나 데이터 없음) |
| 환전·계좌간 이체 | ❌ 후속 | 환전/이체 거래 데이터 없음 |
| 특이거래(미결제·대규모·미분류)·결제일 | ❌ 후속 | 결제일·미결제 상태 없음 |

## 3. 구조 (#38/#39 패턴)

두 소스 통합: `CashFlowRepository`(입금/출금, 기존) + 신규 `CashflowTradeSource`(매수/매도/배당 total_amount·fee·tax). 순수 집계 생성기(fake 포트 테스트).

### 신규 파일

- **포트** `unified-asset/application/port/CashflowTradeSource.kt`
  ```kotlin
  data class TradeCashRecord(
      val tradeDate: LocalDate, val tradeType: String, val stockName: String,
      val accountName: String, val totalAmount: BigDecimal, val fee: BigDecimal, val tax: BigDecimal,
  )
  interface CashflowTradeSource {
      /** [from, to] 구간의 모든 주식 거래 (현금흐름 분류용, 거래일 오름차순) */
      fun findTrades(userId: UUID, from: LocalDate, to: LocalDate): List<TradeCashRecord>
  }
  ```
- **어댑터** `unified-asset/infrastructure/adapter/JdbcCashflowTradeSource.kt` — `ua_stock_trades` JOIN `ua_accounts`, 기간 내 전 거래.
- **생성기** `unified-asset/application/usecase/CashflowReportGenerator.kt` — `ReportBodyGenerator`:
  - `override val type = ReportType.CASHFLOW`
  - 주입: `CashFlowRepository`, `CashflowTradeSource`, `AccountRepository`
  - `generate(userId, period)`:
    - `flows = cashFlowRepository.findByUserIdAndPeriod(userId, period.start, period.end)` (DEPOSIT/WITHDRAWAL)
    - `trades = cashflowTradeSource.findTrades(userId, period.start, period.end)`
    - `accountRepository.findByUserId` → cash_flow accountId 라벨 맵
    - 유형별 분류·집계, 본문 JSON 조립, `asOfDate` = 마지막 흐름/거래일(없으면 `period.end`)

### 유형 분류 (부호)

| 유형 | 소스 | 부호 | 계산 |
|---|---|---|---|
| 입금 | cash_flow DEPOSIT | + | Σ amountKrw |
| 출금 | cash_flow WITHDRAWAL | − | Σ amountKrw |
| 매수대금 | trades BUY·CREDIT_BUY | − | Σ totalAmount |
| 매도대금 | trades SELL·CREDIT_SELL | + | Σ totalAmount |
| 배당·이자 | trades DIVIDEND | + | Σ totalAmount |
| 수수료·세금 | 전 trades | − | Σ (fee+tax) |

- 유입 = 입금 + 매도대금 + 배당·이자, 유출 = 출금 + 매수대금 + 수수료·세금, 순흐름 = 유입 − 유출

### 본문 JSON

```json
{
  "summary": { "totalInflow": …, "totalOutflow": …, "netFlow": … },
  "byType":  [ {"type":"입금"|"출금"|"매수대금"|"매도대금"|"배당·이자"|"수수료·세금",
                "amount": … (부호 KRW), "direction":"IN"|"OUT"} ],   // 금액 0인 유형은 생략
  "monthly": [ {"month":"YYYY-MM", "inflow":…, "outflow":…, "net":…} ],
  "details": [ {"date":"YYYY-MM-DD", "account":…, "type":…, "description":…, "amount": … (부호 KRW)} ]
}
```

- **금액**: `cash_flow.amountKrw`(환율 고정) + trades `total_amount`(KRW 취급). 부호는 direction 반영(유출은 음수)
- **details**: cash_flow(입금/출금) + trades(매수/매도/배당) 통합, `date` 오름차순. `description`은 종목명(trades)·memo(cash_flow)
- **퍼센트 없음** → 스케일 이슈 없음. 부호 색상은 FE(`pctColor`)
- **날짜 직렬화**: `LocalDate` → `.toString()` (mapper JSR310 미등록 — #38 선례)

### 빈 데이터 처리

흐름·거래 0건은 정상 → 예외 없이 0/빈 배열 유효 보고서. as-of = `period.end`.

### 검증 게이트·아카이브

#32 `GenerateReportUseCase` 상속 — sync 상태 게이트, `ReportArchive.create` upsert.

## 4. 테스트·검증

`CashflowReportGeneratorTest` (fake `CashFlowRepository`/`CashflowTradeSource`/`AccountRepository`):
- ① 유형별 부호: 입금/매도/배당 +, 출금/매수/수수료세금 −
- ② 순현금흐름 = 유입 − 유출, summary totalInflow/totalOutflow 정확
- ③ 수수료·세금 = 전 거래 fee+tax 합
- ④ 월별 집계(YYYY-MM) inflow/outflow/net
- ⑤ details: cash_flow + trades 통합·날짜순, 부호 반영
- ⑥ DIVIDEND는 배당·이자 유형으로 분류(매도 아님)
- ⑦ 흐름·거래 0건 → 예외 없는 유효 0 보고서

어댑터 SQL은 스모크: 로컬 → cash_flow(입금/출금) + trades(BUY/SELL/DIVIDEND) 시드 → `generate type=CASHFLOW` → 본문 검산(유형 분류·순흐름·통합 details), 재생성 upsert.

## 5. 제외 (후속)

기초/기말 조정표·정합 검증(월초 잔고 스냅샷), 환전·계좌간 이체, 특이거래(미결제·대규모·미분류), 거래일/결제일 토글, 통화별 컬럼. FE 화면(SCR-RPT-09)은 #41 2단계.
