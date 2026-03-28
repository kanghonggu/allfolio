package com.allfolio.kafka.idempotency

import org.springframework.data.jpa.repository.JpaRepository

/**
 * kafka_processed_event 저장소
 *
 * 멱등성 흐름:
 * 1. saveAndFlush(entity) → INSERT 시도
 * 2. 성공 → 신규 이벤트
 * 3. DataIntegrityViolationException → 중복 이벤트 → 무시
 *
 * SELECT 쿼리 사용 금지 — findById/existsById 호출 금지
 */
interface KafkaProcessedEventRepository : JpaRepository<KafkaProcessedEventEntity, String>
