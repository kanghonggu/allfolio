package com.allfolio.trade.infrastructure.outbox

enum class OutboxStatus {
    PENDING,
    PROCESSED,
    /** Kafka 전파까지 완료 (PROCESSED의 상위 상태) */
    PROCESSED_KAFKA,
    FAILED,
    /** MAX_RETRIES 초과 — 더 이상 재시도하지 않음 */
    DEAD,
}
