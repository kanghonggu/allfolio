# 코드 룰 기반 대사·검증 엔진 v2 설계 (P2 BE 코어) — 파킹 스펙 개정판

- 작성일: 2026-07-31 (원본: [`2026-07-15-reconciliation-engine-design.md`](./2026-07-15-reconciliation-engine-design.md), 파킹.
  원래는 `feat/reconciliation-engine` 브랜치에만 있었고 2026-08-21에 이 디렉터리로 옮겼다 — 브랜치는 정리했다)
- 상태: 개정 초안 — **사용자 리뷰 후 착수** (P2 #12~#17 대응)
- 관련 모듈: `reconciliation`(신규), `backend-app`(조립)

## 개정 사유 (파킹 스펙 대비 달라진 것)

이식 원칙 "비즈니스 로직만 이식, 기술은 ALLFOLIO 기존 방식(해시 대사 등 배제)"에 맞춰 4가지를 수정한다.

| # | 파킹 스펙(v1) | 개정(v2) | 이유 |
|---|---|---|---|
| 1 | 2단계 해시(F)→데이터(G) 대사 | **직접 비교 단일 패스** | 해시 선별은 수만 행 기관 규모 최적화. 개인 서비스의 user×symbol 집계는 수십 행 — 전 행 필드 비교가 더 단순하고 결과도 동일. `SHA-256` 코드·해시 불일치 케이스 전부 제거 |
| 2 | 룰=DB 행(`recon_rule`, JSONB params, 버저닝, Flyway 시드) | **룰=코드(Spring 빈)** | `ReportBodyGenerator`·`SyncAdapter`와 동일한 List 주입 OCP 패턴. 파라미터는 코드 상수(필요 시 application.yml). recon_rule 테이블·JSONB 스키마 검증·룰 CRUD API·버저닝 전부 삭제 — 룰 변경=코드 리뷰+배포가 개인 규모에 더 안전 |
| 3 | Flyway | **자립형 마이그레이션 컨벤션** | 이 저장소는 ddl-auto:none + init.sql + `docs/superpowers/migrations/*.sql` 수동 실행 (Flyway 미사용) |
| 4 | KD ADMIN 관리(전역) | **KD USER-scoped**(user_id 컬럼, 본인 CRUD) | 대사 실행이 본인 스코프이므로 "내 계좌의 알려진 차이"도 본인 데이터. exclusion-lists(#52) USER-scoped 패턴 재사용. ADMIN 의존 완전 제거 |

유지되는 것: user×symbol 집계 대사(계좌 매핑 부재 대응), KD 흡수(숨김 아님·분리 집계), run/summary/detail 이력 구조, Redis 분산 락, 데이터 오류 vs 시스템 오류 격리, detail 100건 상한, USER 본인 스코프 실행.

## 배경 / 목표 (원본 유지, 요약)

브로커 동기화 결과(`ua_assets`)와 거래 원장 기반 내부 계산(`position_daily`)이 맞는지 검증하는 장치가 없다. 목표:

1. 코드 룰 검증·대사 엔진 — 룰 추가=빈 추가(OCP)
2. `ua_assets` vs `position_daily` 포지션 **직접 비교** 대사 (user×symbol 수량 집계)
3. USER-scoped Known Difference로 허용 차이 등록 (흡수 건은 구분 표시, 숨김 아님)
4. 대사 중 동기화 경합 차단 (Redis 분산 락)
5. 실행 이력·드릴다운 API (P2 FE 4종·P3 마감 단계의 토대)

비목표(YAGNI): 계좌 단위 대사(매핑 테이블 도입 후 v3), 평가액 대사(시세 시점 오탐 — KD 운영 경험 후), 승인 워크플로우, KD 만료 푸시(P3 SSE), 룰 관리 화면(룰이 코드이므로 소멸).

## 설계

### 1. 모듈 배치 (원본 유지)

```
common → reconciliation(신규) → backend-app(조립)
```
`reconciliation`은 unified-asset·snapshot에 코드 의존 없음 — 두 원천은 읽기 전용 네이티브 쿼리("데이터로만 연결" 패턴). 진입점: backend-app 컨트롤러 + 추후 P3 마감 단계.

### 2. 스키마 — 3테이블 (v1의 5개에서 recon_rule 삭제·KD user 스코프화)

```sql
CREATE TABLE IF NOT EXISTS recon_run (
    id              UUID         NOT NULL,
    user_id         UUID         NOT NULL,
    run_date        DATE         NOT NULL,
    run_type        VARCHAR(20)  NOT NULL,  -- VALIDATION / RECONCILIATION / ALL
    status          VARCHAR(20)  NOT NULL,  -- RUNNING / COMPLETED / FAILED
    trigger_type    VARCHAR(20)  NOT NULL,  -- MANUAL / SCHEDULED
    internal_as_of  DATE,
    external_as_of  TIMESTAMP,
    started_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    finished_at     TIMESTAMP,
    CONSTRAINT pk_recon_run PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_recon_run_user ON recon_run (user_id, run_date DESC);

CREATE TABLE IF NOT EXISTS recon_result_summary (
    id              UUID         NOT NULL,
    run_id          UUID         NOT NULL,
    rule_code       VARCHAR(50)  NOT NULL,  -- 코드 룰 식별자 (FK 없음 — 룰은 코드)
    status          VARCHAR(20)  NOT NULL,  -- PASSED / DIFF_FOUND / FAILED
    checked_cnt     INT          NOT NULL DEFAULT 0,
    diff_cnt        INT          NOT NULL DEFAULT 0,
    kd_absorbed_cnt INT          NOT NULL DEFAULT 0,
    error_msg       VARCHAR(500),
    elapsed_ms      BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_recon_result_summary PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_recon_summary_run ON recon_result_summary (run_id);

CREATE TABLE IF NOT EXISTS recon_result_detail (
    id             UUID          NOT NULL,
    summary_id     UUID          NOT NULL,
    symbol         VARCHAR(50),
    field_name     VARCHAR(30),
    diff_type      VARCHAR(30)   NOT NULL,  -- VALUE_MISMATCH / MISSING_INTERNAL / MISSING_EXTERNAL / RULE_VIOLATION
    internal_value NUMERIC(30,10),
    external_value NUMERIC(30,10),
    diff_value     NUMERIC(30,10),
    extras         TEXT,                    -- JSON 문자열 (브로커·계좌 문맥 등)
    kd_id          UUID,                    -- 흡수한 KD (nullable, 숨김 아님)
    CONSTRAINT pk_recon_result_detail PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_recon_detail_summary ON recon_result_detail (summary_id);

CREATE TABLE IF NOT EXISTS recon_kd (
    id            UUID          NOT NULL,
    user_id       UUID          NOT NULL,   -- USER-scoped (v2)
    kd_code       VARCHAR(50)   NOT NULL,
    target_symbol VARCHAR(50),              -- null = 와일드카드
    target_field  VARCHAR(30),              -- null = 와일드카드
    value_type    VARCHAR(10)   NOT NULL,   -- ABS / RATIO
    allow_value   NUMERIC(30,10) NOT NULL,
    reason        VARCHAR(300)  NOT NULL,
    apld_strt_dt  DATE          NOT NULL,
    apld_end_dt   DATE          NOT NULL DEFAULT '9999-12-31',
    use_yn        BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_recon_kd PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_recon_kd_user ON recon_kd (user_id);
```

- v1의 `target_broker` 컬럼 제거 — user×symbol 집계 대사에 브로커 문맥이 없어 v1에서도 사실상 매칭 불가였음(오퍼레이터 혼란 소지). 계좌 단위 대사(v3) 때 재도입.
- extras는 JSONB 대신 TEXT(JSON 문자열) — 기존 report_archive body 방식과 동일, 쿼리 필요성 없음.
- KD 버저닝은 유지(만료·수정 이력): 수정 = 기존 행 `apld_end_dt` 마감 + 신규 INSERT (#51 세율마스터와 동일 패턴).
- 배포: `infra/postgres/init.sql` 추가 + `docs/superpowers/migrations/2026-XX-XX-recon.sql` 자립형·멱등(신규 테이블 무해). **BE 배포 전 Neon 수동 실행.**

### 3. 코드 룰 엔진 (#13)

```kotlin
interface ReconRule {
    val code: String              // recon_result_summary.rule_code
    val kind: RuleKind            // VALIDATION / RECONCILIATION
    fun execute(ctx: ReconContext): RuleResult   // ctx: userId, runDate, 읽기 전용 쿼리 포트
}

@Component class NegativeQuantityRule : ReconRule { ... }      // ua_assets 음수 수량
@Component class StaleSyncRule : ReconRule { ... }             // ua_sync_logs 기반(AF-9 산출물 활용) — lastLog ERROR 또는 lastSyncedAt > 26h
@Component class DuplicateTradeRule : ReconRule { ... }        // trade_raw 중복 후보 (lookback 7일, 상수)
@Component class SnapshotMissingRule : ReconRule { ... }       // 거래 있는 포트폴리오의 기준일 position_daily 부재
@Component class PositionReconRule : ReconRule { ... }         // 직접 비교 대사 (아래 §4)
```

- `ReconEngine`이 `List<ReconRule>` 주입 → runType에 맞는 kind 필터 → 순차 실행. 룰 예외는 해당 summary만 FAILED 격리(runCatching), 나머지 계속. 전 룰 실패 시 run FAILED.
- 임계값(26h, lookback 7일 등)은 룰 클래스 companion 상수. 조정 필요해지면 application.yml `@ConfigurationProperties`로 승격(그때도 코드 배포 없이 환경변수 변경으로 가능).
- v1의 "룰 저장 파라미터 스키마 검증"은 룰이 코드가 되면서 컴파일러+테스트가 대체 — 소멸.

### 4. 직접 비교 대사 (#14·#15 통합)

1. **정규화**: 양쪽 user×symbol 수량 집계 로드 — symbol 대문자·트림, quantity scale 10, 수량 0 행 제외(청산 vs 브로커 미표시 오탐 방지).
   - **내부 측 symbol 해석(착수 시점 정정)**: 자산 마스터 테이블은 존재하지 않는다. assetId는 브로커별 결정론 파생 — `KIS:{code}`·`toss-asset:{code}`·`samsung-asset:{isin}`·`binance-asset:{base}` 등을 `UUID.nameUUIDFromBytes`한 값(원본: KisTradeMapper 등). 따라서 **외부(ua_assets) 심볼 × 계좌 provider로 기대 assetId를 파생해 내부(position_daily) 행과 매칭**한다(역방향 해석 불가 — 단방향 해시). 파생 규칙은 reconciliation 모듈 `AssetIdDeriver`에 재구현(코드 의존 없이 데이터 계약으로 간주, KDoc에 원본 파일 명시). 어느 외부 심볼과도 매칭되지 않는 내부 asset_id는 UUID 그대로 `MISSING_EXTERNAL` detail에 기록(extras에 assetId).
2. **비교(단일 패스)**: 심볼 합집합 순회 —
   - 양쪽 존재 & quantity 불일치 → `VALUE_MISMATCH` (internal, external, diff 기록)
   - 내부만 존재 → `MISSING_EXTERNAL`, 외부만 존재 → `MISSING_INTERNAL`
   - v2 비교 필드는 **quantity만** (평가액은 비목표).
3. **시점 정합(알려진 제약, 원본 유지)**: `ua_assets`는 현재 상태 테이블 → 외부 측 시점은 "마지막 동기화 기준". run에 `internal_as_of`(스냅샷 date)·`external_as_of`(min lastSyncedAt) 기록해 화면 표시. 정석 실행은 자정 배치 [재동기화→NAV] 체인 뒤(P3 S040 편입 시).

### 5. KD 매칭·흡수 (#16, USER-scoped 외 원본 유지)

- 매칭: 본인(user_id) KD 중 (target_symbol, target_field — null 와일드카드) 일치 & run_date ∈ 유효기간 & use_yn.
- 판정: `ABS`: |diff| ≤ allow_value / `RATIO`: |diff| / |internal| ≤ allow_value (internal 0이면 ABS만).
- 적용: detail 적재 시 kd_id 기록(행은 남김 — 숨김 아님), summary는 diff_cnt·kd_absorbed_cnt 분리 집계.
- 만료 임박은 조회 응답 계산 필드(푸시는 P3).

### 6. 동기화 잠금 (#17, 원본 유지)

- Redis 락 `recon:lock:{userId}`, TTL 5분. 획득 실패(동기화 진행 중) → 실행 거부 + 명확한 오류. 동기화(수동 Sync·자정 배치)도 시작 전 같은 락 확인. Redis 장애 시 안전 우선 거부.

### 7. API 표면 (전부 USER 본인 스코프 — ADMIN 의존 0)

| 엔드포인트 | 설명 |
|---|---|
| `POST /api/recon/runs` | 실행 (body: runDate, runType). 락 확인 후 동기 실행(수십 행 규모 — 비동기 불필요) |
| `GET /api/recon/runs?from=&to=` | 실행 목록 |
| `GET /api/recon/runs/{id}` | 요약 (summary 목록 + as-of 시점) |
| `GET /api/recon/runs/{id}/details?ruleCode=&symbol=` | 드릴다운 |
| `GET/POST/DELETE /api/recon/kds` | 본인 KD CRUD (수정=버저닝: 마감+신규) |

X-User-Id 소유권 검증은 기존 unified-asset 컨트롤러 패턴.

### 8. 실행 상태·오류 (원본 유지)

RUNNING→COMPLETED/FAILED. 같은 기준일 재실행=새 run(이력 격리). detail 룰당 상한 100건(초과분은 diff_cnt에만). 데이터 오류(룰이 찾은 차이=정상 결과) vs 시스템 오류(executor 예외=summary FAILED) 분리.

### 9. 테스트 (TDD, 원본 유지 + 해시 테스트 소멸)

- 단위(순수, 외부 의존 0): 정규화(스케일·0수량·심볼 정리), 직접 비교(불일치/한쪽 부재), KD 매칭·판정(ABS/RATIO·와일드카드·기간 경계·internal 0), detail 100건 절단, 룰 격리(한 룰 예외가 나머지 안 막음)
- 컨트롤러: 소유권 검증(기존 SecurityTest 스타일)
- 라이브: 시드 계좌로 run 실행 → 의도적 차이(ua_assets 수량 조작) → VALUE_MISMATCH → KD 등록 → 재실행 시 흡수 집계 확인

## 구현 순서 (P2 태스크 재매핑)

| 노션 | v1 계획 | v2 계획 |
|---|---|---|
| #12 | recon_ 5테이블 + 모듈 골격 | recon_ **3테이블(run/summary/detail)+KD** + `reconciliation` 모듈 골격 + 마이그레이션 |
| #13 | 룰 저장소+RuleEngine+검증 룰 4종 | **ReconEngine + 코드 룰 4종**(NegativeQuantity/StaleSync/DuplicateTrade/SnapshotMissing) |
| #14+#15 | 해시(F)+데이터(G) 대사 | **PositionReconRule 직접 비교** (한 PR로 통합) |
| #16 | KD 로직 | KD USER CRUD + 매칭·흡수 |
| #17 | 동기화 잠금 | 동일 (Redis 락 + 동기화 측 확인) |
| FE #18~21 | 화면 4종 | #20 KD 관리 화면은 USER 화면으로 변경, **#21 검증결과·#18 실행현황·#19 드릴다운을 탭 1페이지로 통합 검토**(개인 규모 — FE 스펙 때 결정). 룰 관리 화면은 소멸 |
