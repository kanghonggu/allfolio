package com.allfolio.kafka.idempotency

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * Kafka Consumer 멱등성 마커 테이블
 *
 * 설계:
 * - event_id PRIMARY KEY → INSERT 충돌 시 DataIntegrityViolationException → 중복 감지
 * - SELECT 쿼리 없음 — INSERT 1회로 멱등성 처리
 * - updatable=false: 모든 컬럼 불변 (멱등성 마커는 수정 불필요)
 *
 * TTL 전략:
 * - outbox.trade 토픽 retention = 24h → 48h 이후 레코드 클린업 가능
 * - 현재: 수동 배치 삭제 권장 (운영 환경: pg_cron 또는 별도 CleanupJob)
 */
@Entity
@Table(name = "kafka_processed_event")
class KafkaProcessedEventEntity(

    /** outboxEventId.toString() — Kafka 메시지 고유 식별자 */
    @Id
    @Column(name = "event_id", nullable = false, length = 100, updatable = false)
    val eventId: String,

    @Column(name = "processed_at", nullable = false, updatable = false)
    val processedAt: LocalDateTime = LocalDateTime.now(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KafkaProcessedEventEntity) return false
        return eventId == other.eventId
    }
    override fun hashCode(): Int = eventId.hashCode()
}
