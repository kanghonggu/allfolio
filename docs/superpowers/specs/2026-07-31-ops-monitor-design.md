# Outbox·DLQ 현황 API + 모니터링 화면 (AF-7 · AF-8) 설계

날짜: 2026-07-31 · Phase: P1 가시화 · 노션 태스크 №7(BE)·№8(FE) · 관련: 메뉴구조도 MN-700, FR-CMMN-003 · 의존: ADMIN role(#50, 완료)

## 목적

거래 이벤트 처리의 두 실패 저장소를 ADMIN이 볼 수 있게 하고, 죽은 건을 화면에서 재처리한다.

- AF-7 완료 조건: 집계/목록/재처리 3종 API, ADMIN 전용, 재처리 시 retryCount 보존.
- AF-8 완료 조건: DEAD 건 화면에서 재처리 → 상태 전이 확인 가능. 30초 자동 새로고침.

## 현행 (탐색 결과)

| 저장소 | 위치 | 상태 모델 | 자동 재처리 | 수동 액션 |
|---|---|---|---|---|
| `outbox_event` 테이블 | trade 모듈 `OutboxEventEntity`/`OutboxRepository` | PENDING/PROCESSED/FAILED/DEAD, retryCount(MAX 5) | `OutboxEventProcessor` 30초 폴링(PENDING/FAILED, retry<5, FOR UPDATE SKIP LOCKED) | 없음 — DEAD는 영원히 방치 |
| Redis DLQ | backend-app `DlqService` (`dlq:trade:{broker}`·`dlq:dead:{broker}` TTL 7일) | FailedTradeEvent(retryCount) | `DlqWorker` 5초 폴링(main 큐만) | 없음 — dead 리스트 방치 |

ADMIN 게이트는 `/api/admin/**` → `hasRole('ADMIN')` 기존 인프라(#50). 어드민 컨트롤러는 `backend-app/api/admin/*AdminController` 패턴.

## 설계 — 스키마 무변경

### BE (AF-7)

**1) OutboxRepository 확장** (trade 모듈):
- `countByStatus(status): Long` (파생)
- 목록: `@Query`로 status 필수 + eventType/from/to 옵셔널(null 무시), createdAt DESC, LIMIT은 Pageable로.
- `findDeadByIdsForUpdate(ids)`: `WHERE id IN (:ids) AND status='DEAD' FOR UPDATE SKIP LOCKED` (native).

**2) OutboxEventProcessor 리팩터 + 재처리**:
- 그룹 트리거 핵심(`payload 파싱→그룹→snapshotTrigger→상태 전이`)을 유지하되, 실패 시 전이 정책을 콜백으로 분리.
- `reprocessDead(ids): OutboxReprocessResult(processed, failed, skipped)` 신설 — DEAD만 로드(위 쿼리), **기존 폴러 경로 재사용**(같은 트리거·같은 멱등성), 성공→PROCESSED, 실패→**DEAD 유지 + errorMessage 갱신 + retryCount 보존**(1회성 수동 시도). skipped = 요청 id 중 DEAD 아님/미존재/락 스킵.
- 진입 API와 폴러가 같은 행을 잡는 경합은 FOR UPDATE SKIP LOCKED로 무해(폴러는 DEAD를 안 잡음).

**3) DlqService 확장**:
- `peekDead(broker, limit=50): List<FailedTradeEvent>` — LRANGE(비파괴).
- `requeueDead(broker): Int` — dead 리스트에서 LPOP→main RPUSH 루프, 이동 건수 반환. **retryCount 보존** → Worker 증가 로직상 실패 시 즉시 dead 복귀(=1회성 수동 기회, KDoc 명시).

**4) `api/admin/OpsAdminController`** (`/api/admin/ops`):
- `GET /summary` → `{outbox: {pending, processed, failed, dead}, dlq: [{broker, waiting, dead}]}` (브로커별, 0/0은 생략 없이 전체 반환)
- `GET /outbox?status=&eventType=&from=&to=&limit=50` → 목록(id, aggregateType, aggregateId, eventType, status, retryCount, errorMessage, createdAt, processedAt — payload는 상세 무겁고 민감할 수 있어 목록 제외, `GET /outbox/{id}`로 단건 상세(payload 포함))
- `POST /outbox/reprocess {ids: [...]}` → `{processed, failed, skipped}` (≤100건/회)
- `GET /dlq/dead?broker=` → dead 이벤트 목록
- `POST /dlq/requeue {broker}` → `{requeued}`

### FE (AF-8) — `/unified/admin/ops`

- `useRequireAdmin` + NavBar amber 링크 "운영 모니터링" (기존 어드민 링크 패턴).
- **카운트 카드**: PENDING/FAILED/DEAD/PROCESSED 4장 + DLQ(대기 합/데드 합) 2장. DEAD·FAILED·데드 >0이면 빨간 강조.
- **Outbox 그리드**: 필터(상태 셀렉트, 이벤트타입 텍스트, 기간 from/to date). 행: 시각·이벤트타입·집계ID·상태·재시도·오류. DEAD 행만 체크박스 → "선택 재처리" 버튼 → reprocess API → 결과 토스트(문구) + 목록/서머리 invalidate → **상태 전이 확인**. 행 클릭 시 단건 상세(payload JSON) 펼침.
- **DLQ 섹션**: 브로커별 dead 목록 테이블 + "전체 재큐" 버튼(브로커별).
- **30초 자동 새로고침**: react-query `refetchInterval: 30_000` (summary·목록 공통).
- `lib/ops-admin-api.ts`(`createOpsAdminApi(token)`) + `types/ops.ts`.

## 대안 검토

- **A. DEAD 재처리를 "PENDING으로 되돌리기"** — retryCount 보존 시 폴러 쿼리(retry<5)가 영원히 안 잡는 모순. retryCount 리셋은 완료 조건 위반. 기각.
- **B. (채택) 재처리 API가 폴러 경로를 직접 1회 실행** — "기존 폴러 경로 재사용(멱등)" 명세 그대로, retryCount 보존과 양립, 결과가 즉시 응답에 담겨 화면 전이 확인도 쉬움.
- **C. Redis dead requeue 시 retryCount 리셋** — 무한 수동 루프 가능성. 보존(1회성 기회)이 명세·안전 모두 부합. 기각.

## 테스트

- `OutboxEventProcessor.reprocessDead`: 성공 시 PROCESSED·retryCount 불변 / 실패 시 DEAD 유지·errorMessage 갱신·retryCount 불변 / DEAD 아닌 id는 skipped — 리포지토리·트리거 fake.
- 기존 `process()` 회귀: 리팩터 후 기존 동작(FAILED 증가·DEAD 전이) 불변 검증 테스트 추가.
- `DlqService.requeueDead`: pop한 만큼 main에 쌓이고 dead 비워짐(Redis는 fake/mock — StringRedisTemplate mock 대신 opsForList 시나리오 검증이 과하면 컨트롤러 위임 로직만 단위 검증하고 Redis 경로는 라이브 검증으로).

## 범위 밖 (후속)

- outbox_event 보존정책/아카이빙, Kafka DLQ(레디스 외) 노출, SSE 실시간 푸시(AF-31), PROCESSED 대량 페이지네이션.

## PR 구성

- PR A `feat/ops-monitor-api`(BE) → PR B `feat/ops-monitor-screen`(FE, 스택). **스키마 무변경 — 마이그레이션 불필요.** 머지는 사용자 게이트.
