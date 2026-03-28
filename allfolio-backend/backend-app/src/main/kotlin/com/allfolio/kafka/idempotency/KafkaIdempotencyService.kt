package com.allfolio.kafka.idempotency

import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Kafka Consumer 멱등성 서비스
 *
 * 왜 별도 @Component인가:
 * - Spring AOP 프록시 한계: 같은 클래스 내부 호출은 @Transactional 동작 안 함
 * - REQUIRES_NEW는 별도 Bean에서만 실제 새 TX 보장
 *
 * tryMarkProcessed:
 * - REQUIRES_NEW TX → INSERT → 즉시 커밋
 * - DataIntegrityViolationException → false (중복)
 * - 중복 TX 오염 방지: DuplicateKeyException이 outer TX에 전파되지 않음
 *
 * unmarkProcessed:
 * - 처리 실패 시 보상 트랜잭션 — INSERT 롤백 대신 명시적 DELETE
 * - 이후 Kafka retry에서 INSERT 재시도 가능하게 만듦
 */
@Service
class KafkaIdempotencyService(
    private val repository: KafkaProcessedEventRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 이벤트를 처리 완료로 마킹 (INSERT 기반)
     * @return true = 신규 이벤트, false = 중복 (이미 처리됨)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun tryMarkProcessed(eventId: String): Boolean {
        return try {
            repository.saveAndFlush(KafkaProcessedEventEntity(eventId))
            true
        } catch (e: DataIntegrityViolationException) {
            log.debug("[Idempotency] duplicate eventId={}", eventId)
            false
        }
    }

    /**
     * 처리 실패 시 보상 액션 — 마커 제거 → Kafka retry가 재처리 가능하게
     * REQUIRES_NEW: 실패 후 outer TX(없더라도) 독립된 DELETE 커밋
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun unmarkProcessed(eventId: String) {
        runCatching { repository.deleteById(eventId) }
            .onFailure { log.warn("[Idempotency] unmark failed eventId={}", eventId, it) }
    }
}
