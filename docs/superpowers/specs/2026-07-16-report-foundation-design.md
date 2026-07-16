# 리포트 공통 기반 (report_archive + as-of 생성 프레임) — 설계

- 날짜: 2026-07-16
- 태스크: ALLFOLIO 이식 개발 태스크 DB #32 (R1 리포트 MVP, 규모 M, BE+DB)
- 근거 문서: 노션 「ALLFOLIO 기관급 리포트 명세서 (초안) — 7종」 §0 "기관급을 만드는 4가지 규율"
- 브랜치: `feat/report-foundation`

## 1. 목적

R-01~R-07 기관급 리포트 7종이 공유하는 기반 계층을 만든다. 리포트 종류별 엔진(#33 TWR/MWR, #36 월간 운용보고서, #38 배당, #39 비용)은 이 프레임에 **생성기(Generator)로 꽂히기만** 하면 §0의 규율 4가지를 자동으로 얻는다:

| 규율 | 이 태스크에서의 구현 |
|---|---|
| 기준일 확정 (as-of) | 생성기가 스냅샷(`performance_daily`/`position_daily`) 기준으로 계산하고 `as_of_date`를 반환 → 아카이브에 고정. 조회는 항상 아카이브 본문을 반환하므로 조회 시점마다 숫자가 바뀌지 않음 |
| 정기 생성 + 보관 | `report_archive` 테이블 (기준기간·본문 JSON·PDF 자리). 과거 보고서 재현 = 아카이브 조회 |
| 검증 게이트 | `ReportValidationGate` 포트 — v1 구현은 계좌 동기화 상태 검사(ERROR/미동기화 → 경고). 경고가 있으면 보고서 status=WARNING(경고 배지), 본문은 생성. P2 대사 도입 시 "대사 미해소" 검사를 같은 포트에 추가하는 연결점 |
| 표준 양식 | 본문은 구조화 JSON으로 보관 → 웹 뷰와 PDF(#37)가 같은 본문을 렌더링. PDF 바이트 컬럼은 이번에 자리만 마련 |

이번 태스크는 프레임만 제공한다. **생성기 구현은 0개** — 첫 생성기는 #33(수익률)에서 등록된다.

## 2. 기존 구조 위에서의 위치

- `report` 모듈(현재 ESG 도메인만 존재)에 프레임의 domain/application/infrastructure를 추가한다. snapshot 모듈과 동일하게 spring-data-jpa 의존성을 추가한다.
- `unified-asset` 모듈은 이미 `:report`에 의존한다. 사용자 컨텍스트가 필요한 두 조각 — 검증 게이트 구현(계좌 sync 상태)과 REST 컨트롤러 — 는 unified-asset에 둔다 (기존 `ReportController`와 같은 위치, `X-User-Id` 헤더 관례).
- unified-asset 관례를 따른다: **tenant_id = portfolio_id = userId** (사용자=포트폴리오 단위). 리포트는 사용자 단위로 생성·보관한다.

## 3. DDL — `report_archive` (init.sql 추가)

```sql
CREATE TABLE IF NOT EXISTS report_archive (
    id            UUID         PRIMARY KEY,           -- 앱 생성 (UUID.randomUUID)
    user_id       UUID         NOT NULL,
    report_type   VARCHAR(30)  NOT NULL,              -- ReportType enum name
    period_start  DATE         NOT NULL,
    period_end    DATE         NOT NULL,
    as_of_date    DATE         NOT NULL,              -- 생성에 사용된 스냅샷 최종일
    status        VARCHAR(20)  NOT NULL,              -- FINAL | WARNING
    warnings      JSONB        NOT NULL DEFAULT '[]', -- [{code, message}]
    body          JSONB        NOT NULL,              -- 리포트 본문 (구조화 JSON)
    pdf           BYTEA,                              -- #37에서 사용, 지금은 NULL
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_report_archive UNIQUE (user_id, report_type, period_start, period_end)
);

CREATE INDEX IF NOT EXISTS idx_report_archive_user
    ON report_archive (user_id, report_type, period_end DESC);
```

- 같은 (사용자, 종류, 기간) 재생성 시 **UPSERT 덮어쓰기**. 개인 서비스에서는 데이터 재동기화 후 재생성이 필요하므로 명시적 재생성을 허용하되, 조회는 항상 마지막 확정본을 반환한다 (버전 이력은 YAGNI).
- Neon 운영 DB에는 배포 후 같은 DDL을 수동 적용한다 (기존 관례: init.sql은 IF NOT EXISTS 멱등).

## 4. report 모듈 — 프레임

### domain (`com.allfolio.report.domain.archive`)

- `ReportType` — `MONTHLY_REPORT`(R-01), `RETURNS`(R-02), `DIVIDEND_INTEREST`(R-03), `COST`(R-04), `HOLDINGS`(R-05), `CASHFLOW`(R-06), `ESG_SCREENING`(R-07)
- `ReportPeriod(start: LocalDate, end: LocalDate)` — 팩토리 `monthly(year, month)`, `quarterly(year, quarter)`. start ≤ end 검증
- `ReportWarning(code: String, message: String)`
- `ReportStatus` — `FINAL`, `WARNING`
- `ReportArchive` — id, userId, type, period, asOfDate, status, warnings, bodyJson(String), createdAt. 팩토리 `create(...)`: warnings 비면 FINAL, 있으면 WARNING

### application (`com.allfolio.report.application`)

포트 3개 + 유스케이스 1개:

```kotlin
interface ReportBodyGenerator {
    val type: ReportType
    fun generate(userId: UUID, period: ReportPeriod): GeneratedReport
    // GeneratedReport(asOfDate: LocalDate, bodyJson: String)
}

interface ReportValidationGate {
    fun check(userId: UUID, period: ReportPeriod): List<ReportWarning>
}

interface ReportArchiveRepository {
    fun upsert(archive: ReportArchive): ReportArchive
    fun findById(id: UUID): ReportArchive?
    fun findAll(userId: UUID, type: ReportType?): List<ReportArchive>  // body 제외 메타 조회는 컨트롤러 DTO에서 처리
}
```

- `GenerateReportUseCase.generate(userId, type, period)`:
  1. `generators[type]` 없으면 `UnsupportedReportTypeException`
  2. `gate.check(...)` → warnings
  3. `generator.generate(...)` → asOfDate + bodyJson
  4. `ReportArchive.create(...)` → `repository.upsert(...)` 후 반환
- 생성기 목록은 스프링이 `List<ReportBodyGenerator>`로 주입 (타입 중복 시 기동 실패로 방어).

### infrastructure (`com.allfolio.report.infrastructure`)

- `ReportArchiveEntity` — body/warnings는 `@JdbcTypeCode(SqlTypes.JSON)` String 매핑
- `ReportArchiveJpaRepository` + `ReportArchiveRepositoryImpl` (UPSERT는 unique 제약 기준 select-then-save; 단일 사용자 동시성은 무시 가능 수준)

## 5. unified-asset 모듈 — 게이트 구현 + API

### `AccountSyncValidationGate` (application/usecase)

사용자의 전 계좌를 조회해:
- `status == ERROR` → `SYNC_ERROR` 경고 ("{계좌명} 동기화 실패 상태")
- `lastSyncedAt == null` → `NEVER_SYNCED` 경고
- `lastSyncedAt < period.end 자정` → `STALE_SYNC` 경고 (기간 말 이후 동기화된 적 없음)

MANUAL/CSV 계좌는 동기화 개념이 없으므로 STALE 검사에서 제외 (`SYNCABLE` 판정은 `DailyAccountSyncer.SYNC_ELIGIBLE_PROVIDERS` 상수 재사용).

### `ReportArchiveController` (`/api/reports/archive`)

| 메서드 | 경로 | 동작 |
|---|---|---|
| POST | `/api/reports/archive/generate` | body `{type, year, month}` → 생성+보관, 메타 응답 |
| GET | `/api/reports/archive?type=` | 내 아카이브 메타 목록 (body 제외) |
| GET | `/api/reports/archive/{id}` | 본문 포함 단건 — **소유권 검증** (내 것 아니면 404) |

`X-User-Id` 헤더 관례, 생성기 없는 타입 POST는 400.

## 6. 테스트

- domain: `ReportPeriod` 검증, `ReportArchive.create`의 FINAL/WARNING 판정
- application: `GenerateReportUseCase` — fake generator/gate/repository로 정상 생성, 경고 시 WARNING, 미지원 타입 예외, 재생성 UPSERT 경로
- unified-asset: `AccountSyncValidationGate` — ERROR/미동기화/STALE/정상/MANUAL 제외 케이스
- 컨트롤러는 기존 관례(unified-asset에 컨트롤러 단위 테스트 부재)에 맞춰 유스케이스 계층까지만 단위 테스트. 실 API는 로컬 기동으로 스모크 확인

## 7. 제외 (YAGNI / 후속 태스크)

- PDF 생성·저장 (#37), 실제 생성기 (#33·#36·#38·#39), 정기 자동 생성 스케줄(월 배치 — 첫 생성기 등장 후), 대사 미해소 게이트(P2), 버전 이력, FE 화면 (#34·#37)
