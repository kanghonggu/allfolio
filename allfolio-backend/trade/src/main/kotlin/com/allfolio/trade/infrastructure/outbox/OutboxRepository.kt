package com.allfolio.trade.infrastructure.outbox

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime
import java.util.UUID

interface OutboxRepository : JpaRepository<OutboxEventEntity, UUID> {

    /**
     * 미처리 이벤트 배치 조회 — Processor 폴링용
     * createdAt ASC: 오래된 이벤트 우선 처리 (FIFO)
     */
    fun findTop100ByStatusOrderByCreatedAtAsc(status: OutboxStatus): List<OutboxEventEntity>

    /**
     * PENDING/FAILED 이벤트를 재시도 횟수 제한과 함께 조회 (다중 인스턴스 안전)
     *
     * FOR UPDATE SKIP LOCKED:
     * - 다른 인스턴스가 처리 중인 row는 건너뜀 → 중복 처리 방지
     * - 락 대기 없음 → 논블로킹
     *
     * 적용 조건: status IN ('PENDING','FAILED') AND retry_count < maxRetries
     */
    @Query(
        value = """
            SELECT * FROM outbox_event
            WHERE status IN ('PENDING', 'FAILED')
              AND retry_count < :maxRetries
            ORDER BY created_at ASC
            LIMIT 100
            FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true,
    )
    fun findRetryableForUpdate(@Param("maxRetries") maxRetries: Int): List<OutboxEventEntity>

    // ── 어드민 모니터링 (AF-7) ──

    fun countByStatus(status: OutboxStatus): Long

    /** 어드민 목록 — status 필수, eventType/from/to는 null이면 무시. 최신순. */
    @Query(
        value = """
            SELECT * FROM outbox_event
            WHERE status = :status
              AND (CAST(:eventType AS varchar) IS NULL OR event_type = :eventType)
              AND (CAST(:from AS timestamp) IS NULL OR created_at >= CAST(:from AS timestamp))
              AND (CAST(:to AS timestamp) IS NULL OR created_at <= CAST(:to AS timestamp))
            ORDER BY created_at DESC
        """,
        nativeQuery = true,
    )
    fun findForAdmin(
        @Param("status") status: String,
        @Param("eventType") eventType: String?,
        @Param("from") from: LocalDateTime?,
        @Param("to") to: LocalDateTime?,
        pageable: Pageable,
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
}
