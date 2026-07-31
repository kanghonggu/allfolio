# 브로커 동기화 상태 API + 화면 (AF-9 · AF-10) 설계

날짜: 2026-07-31 · Phase: P1 가시화 · 노션 태스크 №9(BE)·№10(FE) · 관련: 메뉴구조도 MN-300

## 목적

계좌별 동기화가 **언제·어떻게·왜 실패했는지**를 사용자가 볼 수 있게 한다.

- AF-9 완료 조건: 계좌 목록에 마지막 동기화 상태(시각·결과·수집 건수·실패 사유)를 표시할 수 있는 API.
- AF-10 완료 조건: 실패 계좌가 한눈에 보이고 재동기화를 트리거할 수 있는 화면.

## 현행 (탐색 결과)

| 이미 있음 | 없음 (이번 작업) |
|---|---|
| `Account.status`(ACTIVE/SYNCING/ERROR/INACTIVE) + `lastSyncedAt` | 동기화 **이력** 영속화 — 실패 사유·수집 건수는 서버 로그로만 남고 유실 |
| 수동 재동기화 `POST /api/unified/accounts/{id}/sync` | 계좌별 이력 조회 API |
| 계좌 목록 화면(상태 배지·Sync 버튼·lastSyncedAt) | 이력·실패 사유를 보여주는 화면(세션 휘발 `syncResults`뿐) |
| 자정 배치 `DailyNavScheduler` → `DailyAccountSyncer.syncAll()` | 스케줄 실행분의 결과 노출(현재 로그 파일 전용) |

동기화의 단일 초크포인트는 `SyncAccountUseCase.execute(accountId)` (`AccountSyncRunner` 구현체).
스케줄(`DailyAccountSyncer`)과 수동(`AccountController.sync`)이 모두 이 유스케이스를 지난다.

## 설계

### 데이터 모델 — `ua_sync_logs` (신규 테이블, 스키마 변경 有)

```sql
CREATE TABLE IF NOT EXISTS ua_sync_logs (
    id            UUID         NOT NULL,
    account_id    UUID         NOT NULL,
    user_id       UUID         NOT NULL,
    trigger_type  VARCHAR(20)  NOT NULL,   -- SCHEDULED / MANUAL
    status        VARCHAR(20)  NOT NULL,   -- SUCCESS / ERROR
    synced_count  INT          NOT NULL DEFAULT 0,
    error_message VARCHAR(500),
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_ua_sync_logs PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS idx_ua_sync_logs_account ON ua_sync_logs (account_id, created_at DESC);
```

- `trigger`는 예약어 회피를 위해 컬럼명 `trigger_type`.
- FK 제약은 걸지 않는다(기존 ua_* 테이블 컨벤션 동일 — soft reference). 계좌 삭제 시 애플리케이션에서 함께 삭제.
- 보존 정책: 현 단계 무제한(일 1회 배치 + 수동이라 증가량 미미). 필요 시 후속으로 보존기간 도입.
- **운영 Neon 마이그레이션**: `docs/superpowers/migrations/2026-07-31-sync-logs.sql` 자립형·멱등(CREATE IF NOT EXISTS). BE 배포 전 수동 실행(신규 테이블이라 무해). `infra/postgres/init.sql`에도 동일 정의 추가.

### 도메인·포트 (unified-asset 기존 패턴)

- `domain/sync/SyncLog`(create/reconstruct), `SyncTrigger`(SCHEDULED/MANUAL), `SyncLogStatus`(SUCCESS/ERROR). 에러 메시지는 500자 절단.
- `application/port/SyncLogRepository`:
  - `save(log: SyncLog): SyncLog`
  - `findByAccountId(accountId: UUID, limit: Int): List<SyncLog>` (created_at DESC)
  - `findLatestByUserId(userId: UUID): Map<UUID, SyncLog>` (계좌별 최신 1건)
  - `deleteByAccountId(accountId: UUID)`
- `infrastructure`: `SyncLogEntity` + `SyncLogJpaRepository` + `SyncLogRepositoryImpl` (Goal* 패턴 동일).

### 기록 지점 — `SyncAccountUseCase`

- `AccountSyncRunner.execute(accountId, trigger: SyncTrigger = MANUAL)`로 시그니처 확장.
  - `DailyAccountSyncer` → `SCHEDULED`, `AccountController.sync` → `MANUAL`.
- `SyncAccountUseCase.execute`의 모든 종료 경로(성공/어댑터 없음/계좌 없음/예외/민감정보 재연결)에서 `SyncLog` 저장.
  - 예외는 이미 내부 catch되므로 트랜잭션은 커밋되고 로그 행이 함께 남는다.
  - 로그 저장 자체가 실패해도 동기화 결과에는 영향 없게 `runCatching` 격리.
- `AccountController.delete`에서 `syncLogRepository.deleteByAccountId(id)` 추가(고아 로그 방지).

### API (AF-9)

1. `GET /api/unified/accounts/sync-status` — X-User-Id 기준, 계좌별 요약 1행:
   `{accountId, accountName, provider, status, lastSyncedAt, syncable, lastLog: {trigger, status, syncedCount, errorMessage, createdAt} | null}`
   - `syncable` = provider ∈ `DailyAccountSyncer.SYNC_ELIGIBLE_PROVIDERS`.
   - 구현: `findByUserId(accounts)` + `findLatestByUserId(logs)` 조합. 소유권은 userId 필터로 보장.
2. `GET /api/unified/accounts/{id}/sync-logs?limit=20` — 소유권 검증(`AuthorizationService.requireOwnedAccount`) 후 이력 목록. limit 상한 100.

### 화면 (AF-10) — `/unified/accounts/sync`

계좌 관리(`/unified/accounts`) 하위의 전용 "동기화 상태" 페이지(MN-300 하위). 기존 계좌 페이지에서 링크.

- sync-status API로 계좌별 카드/행: 상태 배지(기존 STATUS_STYLE 재사용), 마지막 동기화 시각, 마지막 결과(수집 N건 / 실패 사유), 트리거(스케줄/수동).
- **실패 계좌를 목록 상단에 정렬** + 상단에 "N개 계좌 실패" 요약 배너 → "실패가 한눈에".
- 행별 재동기화 버튼: 기존 `POST /accounts/{id}/sync` 재사용, 완료 후 sync-status 재조회(invalidate).
- 행 펼침 시 `sync-logs` 이력 목록(시각·트리거·결과·건수·사유).
- syncable=false(MANUAL/CSV/KIWOOM) 계좌는 "자동 동기화 대상 아님"으로 하단 그룹.
- `lib/unified-api.ts`에 `accounts.syncStatus()`·`accounts.syncLogs(id)` 추가, `types/unified.ts`에 타입 추가.

## 대안 검토

- **A. Account에 last_error 컬럼만 추가(로그 테이블 없이)** — 이력·수집건수 요건(노션 명세 "이력") 미충족. 기각.
- **B. backend-app broker_sync_state 확장** — 거래 커서 테이블이고 화면 대상인 ua_accounts(자산·계좌 메뉴)와 계층이 다름. AF-10이 자산·계좌 메뉴 하위이므로 unified-asset이 맞다. 기각.
- **C. (채택) ua_sync_logs 신규 + 초크포인트 기록** — 스케줄·수동 모두 한 곳에서 잡히고 기존 패턴(포트/임플·수기 fake 테스트)에 자연스럽다.

## 테스트

- `SyncAccountUseCase`: 성공/실패/어댑터없음/계좌없음 각 경로에서 로그 1건 저장(트리거·건수·사유 검증), 로그 저장 실패가 SyncResult에 영향 없음 — 기존 `SyncAccountUseCase*Test` 스타일(수기 fake).
- `DailyAccountSyncer`: `SCHEDULED` 트리거로 위임하는지.
- 컨트롤러 신규 2개 엔드포인트: 소유권·요약 조합 로직은 유스케이스로 빼서(`GetSyncStatusUseCase`) 순수 테스트.

## 범위 밖 (후속)

- 보존기간·페이지네이션 커서, backend-app 거래동기화(broker_sync_state) 상태 통합 노출, Outbox·DLQ 모니터링(AF-7·8), SSE 실시간 갱신.

## PR 구성

- PR A `feat/sync-status-api` (BE, **스키마 변경 → 마이그레이션 동봉**) → PR B `feat/sync-status-screen` (FE, A 위 스택).
- 머지·Neon 마이그레이션은 사용자 승인 게이트.
