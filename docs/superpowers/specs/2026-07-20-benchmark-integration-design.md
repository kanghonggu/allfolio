# 벤치마크 데이터 연동 + 사용자 BM 설정 — 설계

- 날짜: 2026-07-20
- 태스크: ALLFOLIO 이식 개발 태스크 DB #35 (R1 리포트 MVP, 규모 M, BE)
- 근거 문서: 리포트명세서 R-01·R-02, SCR-RPT-04 (cardBm·cardExcess·BM선·처리규칙 5)
- 브랜치: `feat/benchmark-integration`

## 1. 현황과 문제

- `benchmark_daily`(index_type, date, close_value)는 존재하나, `BenchmarkCollector`가 **KOSPI를 삼성전자(005930) 티크로 프록시** — 지수가 아님. BTC는 BTCUSDT 티크. 티크 폴러가 돌 때만 수집돼 구멍 많음
- `ReportService.benchmark`는 **하드코딩 합성 수치**("2024 approximate") 사용
- 사용자별 BM 선택 개념 없음 → R-01/R-02의 "BM 대비"를 만들 수 없음

## 2. 설계

### 2.1 실데이터 수집 — `BenchmarkSyncService` (unified-asset)

- 소스: **Yahoo Finance 차트 API** (기존 `YahooFinanceClient` 확장 — 무인증, 이미 사용 중)
- 지수 3종: `SPX`(^GSPC), `KOSPI`(^KS11), `BTC`(BTC-USD). enum `BenchmarkType` (+`getDashboardUseCase`가 쓰는 기존 KOSPI/BTC 타입 문자열과 호환)
- `YahooFinanceClient.getDailyHistory(ticker, range)` 추가: `interval=1d&range=1y`의 timestamp/close 배열 파싱
- 동작:
  - **백필**: 매일 01:10 스케줄 + `ApplicationReadyEvent` 시 — 지수별 최신 저장일 확인, 30일 이상 비면 range=1y, 아니면 range=1mo 수집 → UPSERT (`ON CONFLICT (index_type, date) DO UPDATE`)
  - benchmark_daily 접근은 JdbcTemplate (unified-asset은 snapshot 모듈 비의존 — PerformanceSnapshotService 관례)
- 기존 `BenchmarkCollector`(backend-app) **삭제** — 부정확한 프록시 대체. `benchmark_daily`를 읽는 `GetDashboardUseCase`는 타입 문자열(KOSPI/BTC) 유지로 무변경 호환

### 2.2 사용자 BM 설정

DDL:

```sql
CREATE TABLE IF NOT EXISTS user_benchmark (
    user_id     UUID        NOT NULL,
    index_type  VARCHAR(20) NOT NULL,   -- SPX | KOSPI | BTC
    updated_at  TIMESTAMP   NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_user_benchmark PRIMARY KEY (user_id)
);
```

- API: `GET /api/benchmark-config`(미설정 시 `{indexType: null}`), `PUT /api/benchmark-config` body `{indexType: "SPX" | null}` (null이면 행 삭제 = 해제)
- 계정 파기에 `deleteUserBenchmark` 추가
- 저장/조회는 JdbcTemplate UPSERT (단일 컬럼 설정 테이블에 JPA는 과함)

### 2.3 R-02 연동 — `GetReturnsAnalysisUseCase` 확장

`ReturnsAnalysis`에 `benchmark: BenchmarkComparison?` 추가:

```kotlin
data class BenchmarkComparison(
    val indexType: String,           // SPX 등
    val periodReturn: BigDecimal?,   // 기간 내 첫 종가 대비 마지막 종가 수익률
    val excessReturn: BigDecimal?,   // twr − periodReturn (둘 다 있을 때)
    val series: List<NavPoint>,      // BM을 포트폴리오 기초 NAV로 정규화한 값 — NAV 곡선에 같은 축으로 겹침
)
```

- 사용자 BM 미설정 or 기간 내 BM 종가 2건 미만 → `benchmark = null` (화면은 기존처럼 숨김)
- 정규화: `value_t = startNav × (close_t / close_first)`

### 2.4 FE — 수익률 화면 확장 (SCR-RPT-04 잔여 활성화)

- 기간 선택바 옆 BM 셀렉트(미설정/S&P 500/KOSPI/BTC) — 변경 시 PUT 후 분석 쿼리 invalidate
- BM 설정 시: 요약 카드에 `BM 수익률`·`초과수익`(±색, %p) 추가, NAV 곡선에 BM 정규화 라인(회색 점선) 추가
- BM 미설정 시: 카드·선 숨김 (기존 동작 유지)

## 3. 테스트·검증

- `BenchmarkSyncServiceTest`: fake client/jdbc로 백필 범위 판정(빈 DB→1y, 최신→1mo), UPSERT 호출
- `GetReturnsAnalysisUseCaseTest` 확장: BM 설정 시 periodReturn·excess·정규화 시계열, 미설정 시 null, BM 데이터 부족 시 null
- FE: tsc + next build
- 스모크: 실 Yahoo API로 백필 실행(로컬) → benchmark_daily 채워짐 확인 → 화면에서 BM 선·카드 확인

## 4. 제외

- R-01 월간보고서의 BM 섹션(#36에서 이 데이터 사용), BM 구성종목(오버/언더웨이트 — R-01 4번 섹션, 후속), 커스텀/복합 BM, 세션 내 다중 BM 비교
