package com.allfolio.trade.infrastructure.outbox

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
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
}
