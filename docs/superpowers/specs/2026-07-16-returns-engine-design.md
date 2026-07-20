# TWR/MWR 수익률 계산 엔진 — 설계

- 날짜: 2026-07-16
- 태스크: ALLFOLIO 이식 개발 태스크 DB #33 (R1 리포트 MVP, 규모 L, BE)
- 근거 문서: 노션 「기관급 리포트 명세서」 R-02, 참조 원본 abor npsRor (NAV + 현금흐름 조정, 영업일 기준)
- 브랜치: `feat/returns-engine` (#32 `feat/report-foundation` 위 스택)
- 사용자 결정: 현금흐름 원장 신설(Option A, 2026-07-16 승인), 계좌별 수익률 v1 제외 승인

## 1. 목적과 범위

R-02 수익률 보고서의 계산 엔진. **모든 리포트의 숫자 공급원**이 되는 R1의 핵심 태스크.

- TWR(기본)·MWR(보조)를 병행 계산 — "포트폴리오 운용 성과 vs 내 돈 기준 체감 수익률"
- 입출금 효과 분해: 기말 NAV = 기초 NAV + 순입출금 + 투자손익
- #32 프레임의 `ReportBodyGenerator`로 등록 → `POST /api/reports/archive/generate` type=RETURNS가 처음으로 동작
- **제외**: BM 대비(#35), 수익률 화면(#34), 계좌별 수익률(계좌별 NAV 시계열 부재 — performance_daily가 사용자 단위. 후속 태스크로), 자동 입출금 감지

## 2. 데이터 현황 (조사 결과)

- `performance_daily`: 사용자 단위 일별 KRW NAV (자정 배치 + sync 시 UPSERT, 2026-07-13부터 축적). `tenant_id = portfolio_id = userId` 관례
- 입출금 기록 **부재**: `TradeType`=BUY/SELL뿐, `StockTradeType`에도 DEPOSIT/WITHDRAWAL 없음, 계좌 sync는 잔고만 읽음 → **cash_flow 원장 신설**
- NAV 시계열에 구멍 가능(배치 실패일 등) → 엔진은 "관측일 사이 구간 수익률"로 처리 (일별이 아닌 구간 체인링킹)

## 3. DDL — `cash_flow` (init.sql 추가)

```sql
CREATE TABLE IF NOT EXISTS cash_flow (
    id           UUID           PRIMARY KEY,
    user_id      UUID           NOT NULL,
    account_id   UUID,                          -- 선택: 특정 계좌 귀속 (FK 없음, 계좌 삭제와 독립)
    flow_date    DATE           NOT NULL,
    flow_type    VARCHAR(20)    NOT NULL,       -- DEPOSIT | WITHDRAWAL
    amount       NUMERIC(30,10) NOT NULL,       -- 원통화 금액 (양수)
    currency     VARCHAR(10)    NOT NULL,
    amount_krw   NUMERIC(30,10) NOT NULL,       -- 기록 시점 환율로 고정 환산 (as-of 재현성)
    memo         VARCHAR(500),
    created_at   TIMESTAMP      NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_cash_flow_user_date
    ON cash_flow (user_id, flow_date);
```

- `amount_krw`를 기록 시점에 `FxConverter.toKrw()`로 고정한다. NAV가 KRW 합산이므로 조정도 KRW 기준이어야 하고, 조회 시점 환율 재적용으로 숫자가 바뀌면 §0 as-of 규율 위반
- 계정 파기: `AccountPurgeRepository.deleteCashFlow(userId)` 추가 (#32에서 배운 것)

## 4. unified-asset — 현금흐름 원장

### domain (`domain/cashflow/CashFlow.kt`)

- `FlowType` enum: `DEPOSIT`, `WITHDRAWAL`
- `CashFlow` — id, userId, accountId?, flowDate, type, amount(>0 검증), currency, amountKrw, memo, createdAt. 팩토리 `create(...)` + `reconstruct(...)`  (Account 패턴 동일)

### port + 어댑터

```kotlin
interface CashFlowRepository {
    fun save(cashFlow: CashFlow): CashFlow
    fun findById(id: UUID): CashFlow?
    fun findByUserIdAndPeriod(userId: UUID, from: LocalDate, to: LocalDate): List<CashFlow>
    fun findByUserId(userId: UUID): List<CashFlow>
    fun delete(id: UUID)
}
```

JPA 엔티티/리포지토리는 AccountEntity 패턴. 저장 유스케이스 `RecordCashFlowUseCase`: `FxConverter.toKrw()`로 amountKrw 채워 저장.

### API — `CashFlowController` (`/api/cashflows`)

| 메서드 | 경로 | 동작 |
|---|---|---|
| POST | `/api/cashflows` | `{accountId?, flowDate, flowType, amount, currency, memo?}` 기록 |
| GET | `/api/cashflows?from=&to=` | 내 입출금 목록 (기간 선택) |
| DELETE | `/api/cashflows/{id}` | 삭제 — 소유권 검증(내 것 아니면 404) |

## 5. report 모듈 — 순수 계산 엔진 (`domain/returns/`)

입력·출력 모두 순수 값 객체. 스프링·DB 무관, 단위 테스트로 완전 검증.

```kotlin
data class NavPoint(val date: LocalDate, val nav: BigDecimal)
data class Flow(val date: LocalDate, val amountKrw: BigDecimal)  // 입금 양수, 출금 음수 (netKrw)

data class PeriodReturns(
    val twr: BigDecimal?,          // 계산 불가(관측 2개 미만) 시 null
    val mwr: BigDecimal?,          // XIRR 미수렴 시 null
    val startNav: BigDecimal?,
    val endNav: BigDecimal?,
    val netFlow: BigDecimal,       // 순입출금 (입금-출금)
    val investmentPnl: BigDecimal?, // endNav - startNav - netFlow
)

object ReturnsCalculator {
    fun calculate(navSeries: List<NavPoint>, flows: List<Flow>, from: LocalDate, to: LocalDate): PeriodReturns
}
```

### TWR — abor npsRor 방식 (구간 체인링킹)

관측일 t_0 < t_1 < ... < t_n (from~to 범위 내 NAV 관측일). 구간 (t_{i-1}, t_i]에 대해:

```
inflow_i  = Σ 입금 (t_{i-1} < date ≤ t_i)
outflow_i = Σ 출금 (같은 구간, 양수 표현)
r_i = (NAV_i − NAV_{i-1} − inflow_i + outflow_i) / (NAV_{i-1} + inflow_i)
TWR = Π(1 + r_i) − 1
```

- 분모 ≤ 0 구간(전액 출금 후 재입금 등)은 해당 구간 r=0 처리하고 경고 없이 건너뜀 (v1 단순화, 코드 주석 명시)
- from 시점 NAV = from 이후 첫 관측, to 시점 NAV = to 이전 마지막 관측

### MWR — XIRR

현금흐름 목록: `(t_0, −NAV_0)`, 각 플로우 `(date, −입금 | +출금)`, `(t_n, +NAV_n)`.
Newton-Raphson(초깃값 0.1) → 미수렴 시 이분법([-0.9999, 10]) 폴백 → 그래도 실패 시 null.
연율 기준(365일 지수)으로 풀고, **기간 수익률로 환산해 반환** (`(1+연율)^(days/365) − 1`) — TWR과 같은 잣대로 비교되도록.

### 기간 세트

`monthly` 기간 외에 R-02 표준 기간(1M/3M/6M/YTD/1Y/SI)은 생성기가 to 기준으로 from을 역산해 `calculate`를 반복 호출. SI의 from = 첫 NAV 관측일.

## 6. unified-asset — `ReturnsReportGenerator`

`ReportBodyGenerator` 구현 (`type = RETURNS`), 스프링 빈 등록만으로 #32 프레임에 연결.

- 조회: `performance_daily`에서 사용자 NAV 시계열(JdbcTemplate — PerformanceSnapshotService 관례), `CashFlowRepository`에서 플로우 (KRW = amountKrw, 출금은 음수화)
- 기간: 요청 period(월간) + 표준 기간 6종(1M/3M/6M/YTD/1Y/SI, to = period.end)
- `asOfDate` = period.end 이전 마지막 NAV 관측일
- NAV 관측 2개 미만이면 `InsufficientDataException` → 컨트롤러 400 ("스냅샷 축적 부족")
- 본문 JSON(웹/PDF 공용):

```json
{
  "period": {"twr": 0.021, "mwr": 0.019, "startNav": ..., "endNav": ..., "netFlow": ..., "investmentPnl": ...},
  "standard": {"1M": {...}, "3M": {...}, "6M": {...}, "YTD": {...}, "1Y": {...}, "SI": {...}},
  "flowDecomposition": {"startNav": ..., "netFlow": ..., "investmentPnl": ..., "endNav": ...},
  "navSeries": [{"date": "2026-06-01", "nav": ...}, ...]
}
```

## 7. 테스트

- `ReturnsCalculatorTest` (핵심): ①플로우 없는 단순 상승 ②기중 입금 시 TWR이 입금을 수익으로 안 잡음 ③출금 케이스 ④MWR과 TWR 괴리(입금 타이밍) ⑤관측 1개 → null ⑥XIRR 수렴(알려진 정답 대조) ⑦분해 항등식(endNav = startNav + netFlow + pnl)
- `CashFlow` 도메인: 양수 검증, KRW 환산 고정
- `RecordCashFlowUseCase`: FxConverter 호출·저장 (fake)
- `ReturnsReportGenerator`: fake 저장소로 body JSON 구조·asOfDate 검증
- 계정 파기 테스트에 deleteCashFlow 추가
- 스모크: 로컬 기동 → 유저 생성 → performance_daily·cash_flow 시드 → generate RETURNS → 아카이브 본문 수치 검산

## 8. 제외 (후속)

- BM 대비(#35에서 생성기 확장), 수익률 화면(#34), 계좌별 수익률(계좌별 NAV 스냅샷 신설 후), KIS 입출금 내역 자동 수집, 배당 재투자 구분(#38)
