package com.allfolio.trade.infrastructure.outbox

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

/**
 * Outbox 이벤트 테이블
 *
 * 설계 원칙:
 * - @Immutable 금지 — status, processedAt, errorMessage 변경 필요
 * - Trade INSERT와 같은 트랜잭션에서 INSERT
 * - Processor가 PENDING → PROCESSED/FAILED 로 상태 전이
 * - Kafka 연동 시: Debezium CDC 소스로 대체 가능
 */
@Entity
@Table(
    name = "outbox_event",
    indexes = [
        Index(name = "idx_outbox_status_created", columnList = "status, created_at"),
    ]
)
class OutboxEventEntity(

    @Id
    @Column(columnDefinition = "uuid", nullable = false, updatable = false)
    val id: UUID,

    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 50)
    val aggregateType: String,

    @Column(name = "aggregate_id", columnDefinition = "uuid", nullable = false, updatable = false)
    val aggregateId: UUID,

    @Column(name = "event_type", nullable = false, updatable = false, length = 100)
    val eventType: String,

    /** JSON 직렬화된 이벤트 페이로드 */
    @Column(nullable = false, updatable = false, columnDefinition = "text")
    val payload: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: OutboxStatus,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime,

    /** 재시도 횟수 — MAX_RETRIES 이상이면 DEAD 전이 */
    @Column(name = "retry_count", nullable = false)
    var retryCount: Int = 0,

    @Column(name = "processed_at")
    var processedAt: LocalDateTime? = null,

    @Column(name = "error_message", length = 500)
    var errorMessage: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OutboxEventEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
