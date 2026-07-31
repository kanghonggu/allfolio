# Outbox·DLQ 모니터링 (AF-7 · AF-8) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** outbox_event·Redis DLQ 현황을 ADMIN API로 노출하고 DEAD 건을 화면에서 재처리한다.

**Architecture:** trade 모듈 `OutboxRepository` 쿼리 확장 + `OutboxEventProcessor`에 폴러 경로 재사용 `reprocessDead`(retryCount 보존) + `DlqService` peek/requeue + `api/admin/OpsAdminController`(기존 `/api/admin/**` hasRole ADMIN 게이트). FE는 `/unified/admin/ops`(useRequireAdmin, 30초 refetchInterval).

**Tech Stack:** Kotlin/Spring(JPA·Redis), JUnit5 + fake/Mockito, Next.js + react-query. **스키마 무변경.**

**스펙:** `docs/superpowers/specs/2026-07-31-ops-monitor-design.md`

---

## PR A — `feat/ops-monitor-api` (BE)

### Task 0: 브랜치
- [ ] `git checkout -b feat/ops-monitor-api main`

### Task 1: OutboxRepository 쿼리 확장 (trade 모듈)
**Files:** Modify `trade/src/main/kotlin/com/allfolio/trade/infrastructure/outbox/OutboxRepository.kt`

- [ ] 추가:
```kotlin
fun countByStatus(status: OutboxStatus): Long

/** 어드민 목록 — status 필수, eventType/from/to는 null이면 무시. 최신순. */
@Query(
    """
    SELECT e FROM OutboxEventEntity e
    WHERE e.status = :status
      AND (:eventType IS NULL OR e.eventType = :eventType)
      AND (CAST(:from AS timestamp) IS NULL OR e.createdAt >= :from)
      AND (CAST(:to AS timestamp) IS NULL OR e.createdAt <= :to)
    ORDER BY e.createdAt DESC
    """
)
fun findForAdmin(
    @Param("status") status: OutboxStatus,
    @Param("eventType") eventType: String?,
    @Param("from") from: java.time.LocalDateTime?,
    @Param("to") to: java.time.LocalDateTime?,
    pageable: org.springframework.data.domain.Pageable,
): List<OutboxEventEntity>

/** DEAD 재처리 대상 잠금 조회 — 폴러와 동일한 SKIP LOCKED 규약. */
@Query(
    value = """
        SELECT * FROM outbox_event
        WHERE id IN (:ids) AND status = 'DEAD'
        FOR UPDATE SKIP LOCKED
    """,
    nativeQuery = true,
)
fun findDeadByIdsForUpdate(@Param("ids") ids: List<UUID>): List<OutboxEventEntity>
```
- [ ] `./gradlew :trade:compileKotlin` → 그린, Commit — `feat(ops): outbox 어드민 조회 쿼리 (AF-7)`

### Task 2: OutboxEventProcessor 리팩터 + reprocessDead (TDD)
**Files:**
- Modify `backend-app/.../processor/OutboxEventProcessor.kt`
- Test 신규 `backend-app/src/test/kotlin/com/allfolio/processor/OutboxEventProcessorReprocessTest.kt`

- [ ] **실패 테스트**: Mockito mock(OutboxRepository·SnapshotTriggerService·BrokerMetrics)으로
  - 성공 경로: DEAD 2건(같은 그룹) → trigger 1회, 상태 PROCESSED, retryCount 불변, result.processed=2
  - 실패 경로: trigger가 throw → 상태 DEAD 유지, errorMessage "manual reprocess failed: ..." 갱신, retryCount 불변, result.failed=1
  - skipped: 요청 3개 중 락 조회가 2개만 반환 → skipped=1
- [ ] **구현**: `process()`의 그룹 처리 블록을 `handleGroups(events, onGroupFailure: (List<OutboxEventEntity>, Exception) -> Unit)`로 추출.
  - 기존 `process()`는 onGroupFailure에서 retry 증가/FAILED/DEAD 전이(현행 그대로).
  - 신규:
```kotlin
data class OutboxReprocessResult(val processed: Int, val failed: Int, val skipped: Int)

@Transactional
fun reprocessDead(ids: List<UUID>): OutboxReprocessResult {
    val events = outboxRepository.findDeadByIdsForUpdate(ids)
    val skipped = ids.size - events.size
    if (events.isEmpty()) return OutboxReprocessResult(0, 0, skipped)
    var processed = 0; var failed = 0
    handleGroups(events) { group, e ->
        failed += group.size
        group.forEach { it.errorMessage = "manual reprocess failed: ${e.message?.take(450)}" }
        // retryCount·DEAD 상태 보존 — 1회성 수동 시도
    }
    processed = events.count { it.status == OutboxStatus.PROCESSED }
    return OutboxReprocessResult(processed, failed, skipped)
}
```
- [ ] 기존 `process()` 회귀 테스트 1개(FAILED retry 증가·MAX 도달 시 DEAD) 같은 파일에 추가
- [ ] `./gradlew :backend-app:test --tests "*.OutboxEventProcessorReprocessTest"` → PASS, Commit — `feat(ops): DEAD outbox 수동 재처리 — 폴러 경로 재사용 (AF-7)`

### Task 3: DlqService peek/requeue (TDD 간이)
**Files:** Modify `backend-app/.../dlq/DlqService.kt`, Test `backend-app/src/test/kotlin/com/allfolio/dlq/DlqServiceRequeueTest.kt`

- [ ] 구현:
```kotlin
/** dead 리스트 비파괴 조회 (어드민 화면용). */
fun peekDead(brokerType: BrokerType, limit: Long = 50): List<FailedTradeEvent> =
    runCatching {
        stringRedisTemplate.opsForList().range(deadKey(brokerType.name), 0, limit - 1)
            .orEmpty().mapNotNull { json ->
                runCatching { objectMapper.readValue(json, FailedTradeEvent::class.java) }.getOrNull()
            }
    }.getOrDefault(emptyList())

/**
 * dead → main 재큐 (어드민 수동). retryCount 보존 — Worker가 실패 시 즉시 dead 복귀하는
 * 1회성 수동 기회. 이동 건수 반환.
 */
fun requeueDead(brokerType: BrokerType): Int {
    var moved = 0
    while (true) {
        val json = runCatching {
            stringRedisTemplate.opsForList().leftPop(deadKey(brokerType.name))
        }.getOrNull() ?: break
        stringRedisTemplate.opsForList().rightPush(dlqKey(brokerType.name), json)
        moved++
        if (moved >= MAX_REQUEUE) break
    }
    return moved
}
// companion에 const val MAX_REQUEUE = 500
```
- [ ] 테스트: `StringRedisTemplate`·`ListOperations` Mockito mock — leftPop이 2회 JSON 반환 후 null → rightPush 2회·moved=2 / peekDead 역직렬화 실패 항목 skip
- [ ] `./gradlew :backend-app:test --tests "*.DlqServiceRequeueTest"` → PASS, Commit — `feat(ops): DLQ dead peek·requeue (AF-7)`

### Task 4: OpsAdminController
**Files:** Create `backend-app/src/main/kotlin/com/allfolio/api/admin/OpsAdminController.kt`

- [ ] 구현 (TaxRateAdminController 패턴):
```kotlin
package com.allfolio.api.admin

import com.allfolio.broker.BrokerType
import com.allfolio.dlq.DlqService
import com.allfolio.dlq.FailedTradeEvent
import com.allfolio.processor.OutboxEventProcessor
import com.allfolio.processor.OutboxReprocessResult
import com.allfolio.trade.infrastructure.outbox.OutboxRepository
import com.allfolio.trade.infrastructure.outbox.OutboxStatus
import org.springframework.data.domain.PageRequest
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/api/admin/ops")
class OpsAdminController(
    private val outboxRepository: OutboxRepository,
    private val outboxProcessor: OutboxEventProcessor,
    private val dlqService: DlqService,
) {
    @GetMapping("/summary")
    fun summary(): OpsSummaryResponse = OpsSummaryResponse(
        outbox = OutboxStatus.entries.associate { it.name to outboxRepository.countByStatus(it) },
        dlq = BrokerType.entries.map {
            DlqBrokerSummary(it.name, dlqService.size(it), dlqService.deadSize(it))
        },
    )

    @GetMapping("/outbox")
    fun outboxList(
        @RequestParam status: OutboxStatus,
        @RequestParam(required = false) eventType: String?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): List<OutboxEventSummary> = outboxRepository.findForAdmin(
        status, eventType?.takeIf { it.isNotBlank() },
        from?.atStartOfDay(), to?.plusDays(1)?.atStartOfDay()?.minusNanos(1),
        PageRequest.of(0, limit.coerceIn(1, 200)),
    ).map { e ->
        OutboxEventSummary(e.id, e.aggregateType, e.aggregateId, e.eventType,
            e.status.name, e.retryCount, e.errorMessage, e.createdAt.toString(), e.processedAt?.toString())
    }

    @GetMapping("/outbox/{id}")
    fun outboxDetail(@PathVariable id: UUID): OutboxEventDetail {
        val e = outboxRepository.findById(id).orElseThrow { NoSuchElementException("outbox event not found: $id") }
        return OutboxEventDetail(e.id, e.aggregateType, e.aggregateId, e.eventType, e.status.name,
            e.retryCount, e.errorMessage, e.createdAt.toString(), e.processedAt?.toString(), e.payload)
    }

    @PostMapping("/outbox/reprocess")
    fun reprocess(@RequestBody req: ReprocessRequest): OutboxReprocessResult {
        require(req.ids.isNotEmpty() && req.ids.size <= 100) { "ids는 1~100건이어야 합니다" }
        return outboxProcessor.reprocessDead(req.ids)
    }

    @GetMapping("/dlq/dead")
    fun dlqDead(@RequestParam broker: BrokerType): List<FailedTradeEvent> =
        dlqService.peekDead(broker)

    @PostMapping("/dlq/requeue")
    fun dlqRequeue(@RequestBody req: DlqRequeueRequest): DlqRequeueResponse =
        DlqRequeueResponse(dlqService.requeueDead(req.broker))
}

data class OpsSummaryResponse(val outbox: Map<String, Long>, val dlq: List<DlqBrokerSummary>)
data class DlqBrokerSummary(val broker: String, val waiting: Long, val dead: Long)
data class OutboxEventSummary(
    val id: UUID, val aggregateType: String, val aggregateId: UUID, val eventType: String,
    val status: String, val retryCount: Int, val errorMessage: String?,
    val createdAt: String, val processedAt: String?,
)
data class OutboxEventDetail(
    val id: UUID, val aggregateType: String, val aggregateId: UUID, val eventType: String,
    val status: String, val retryCount: Int, val errorMessage: String?,
    val createdAt: String, val processedAt: String?, val payload: String,
)
data class ReprocessRequest(val ids: List<UUID>)
data class DlqRequeueRequest(val broker: BrokerType)
data class DlqRequeueResponse(val requeued: Int)
```
(참고: LocalDateTime 직렬화 이슈 회피 위해 문자열 변환 — jacksonObjectMapper JSR310 미등록 교훈은 report body 한정이지만 API 응답은 Spring MVC ObjectMapper라 JSR310 등록됨. 그래도 기존 컨벤션과 톤 맞춰 toString 사용.)
- [ ] `./gradlew :backend-app:test :backend-app:compileKotlin` → 그린, Commit — `feat(ops): Outbox·DLQ 어드민 API (AF-7)`

### Task 5: PR A
- [ ] push + `gh pr create` — 제목 `feat(ops): AF-7 Outbox·DLQ 현황 API (ADMIN)`, 스키마 무변경 명시

---

## PR B — `feat/ops-monitor-screen` (FE, A 위 스택)

### Task 6: 타입 + API 클라이언트
**Files:** Create `frontend/allfolio_app/types/ops.ts`, `frontend/allfolio_app/lib/ops-admin-api.ts`

- [ ] `types/ops.ts`: OpsSummary/DlqBrokerSummary/OutboxEventSummary/OutboxEventDetail/ReprocessResult/FailedDlqEvent (BE 응답 필드 그대로, 날짜는 string)
- [ ] `ops-admin-api.ts`: `createOpsAdminApi(token)` — summary()/outboxList(params)/outboxDetail(id)/reprocess(ids)/dlqDead(broker)/dlqRequeue(broker) (tax-rate-admin-api 패턴)
- [ ] Commit — `feat(ops): 운영 모니터링 API 클라이언트 (AF-8)`

### Task 7: 화면 + NavBar
**Files:** Create `app/unified/admin/ops/page.tsx`, Modify `components/NavBar.tsx`

- [ ] 페이지: useRequireAdmin. 섹션 —
  1. 카운트 카드 6장(PENDING/FAILED/DEAD/PROCESSED + DLQ 대기합/데드합, DEAD·FAILED·데드>0 빨강)
  2. Outbox 그리드: 필터(상태 select 기본 DEAD, eventType text, from/to date) + 행(시각·타입·상태·재시도·오류) + DEAD 행 체크박스 + "선택 재처리(N)" 버튼 → 결과 문구(`처리 N · 실패 N · 스킵 N`) + invalidate. 행 클릭 → payload JSON 펼침(outboxDetail)
  3. DLQ 섹션: summary의 브로커별 (waiting/dead>0만 표시), dead>0 브로커는 dead 목록 테이블 + "전체 재큐" 버튼
- [ ] 쿼리: summary·목록 모두 `refetchInterval: 30_000`
- [ ] NavBar isAdmin 블록에 `/unified/admin/ops` "운영 모니터링" 링크 추가
- [ ] `npx tsc --noEmit` → 그린, Commit — `feat(ops): Outbox·DLQ 모니터링 화면 (AF-8)`

### Task 8: PR B
- [ ] push + `gh pr create --base feat/ops-monitor-api` — 스택 주의(머지 시 재타겟)

---

## 검증
- [ ] BE: `:trade:compileKotlin`·`:backend-app:test` 그린 / FE: tsc 그린
- [ ] 라이브: 로컬 스택 기동(마이그레이션 로컬 적용됨·`.env` env 주입 방식) → outbox_event에 DEAD 행 시드 → 화면에서 선택 재처리 → PROCESSED 전이 확인(AF-8 완료 조건). ADMIN 계정 필요: 로컬 DB에서 livetest role을 ADMIN으로 UPDATE 후 재로그인 토큰 주입, 검증 후 원복
- [ ] 노션 №7·№8·메모리 갱신
