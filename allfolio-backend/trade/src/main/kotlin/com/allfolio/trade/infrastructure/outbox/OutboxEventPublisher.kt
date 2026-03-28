package com.allfolio.trade.infrastructure.outbox

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * Trade 트랜잭션 내부에서 Outbox 이벤트를 INSERT한다.
 *
 * 설계 원칙:
 * - @Transactional 없음 — 호출자(UseCase)의 트랜잭션에 참여
 * - ObjectMapper를 로컬 singleton으로 보유 — 빈 주입 복잡성 제거
 * - Kafka 연동 시: saveAndFlush 후 Debezium CDC가 대체 가능
 */
@Component
class OutboxEventPublisher(
    private val outboxRepository: OutboxRepository,
) {
    /**
     * @return 저장된 OutboxEventEntity.id — TradeRecordedEvent에 포함하여
     *         AFTER_COMMIT Listener가 PROCESSED 로 전이하는 데 사용
     */
    fun publishTradeRecorded(
        tradeId: UUID,
        tenantId: UUID,
        portfolioId: UUID,
        assetId: UUID,
        price: BigDecimal,
        tradeDate: LocalDate,
    ): UUID {
        val payload = MAPPER.writeValueAsString(
            TradeRecordedPayload(tradeId, tenantId, portfolioId, assetId, price, tradeDate)
        )
        return outboxRepository.save(
            OutboxEventEntity(
                id            = UUID.randomUUID(),
                aggregateType = "TRADE",
                aggregateId   = tradeId,
                eventType     = EVENT_TYPE,
                payload       = payload,
                status        = OutboxStatus.PENDING,
                createdAt     = LocalDateTime.now(),
            )
        ).id
    }

    data class TradeRecordedPayload(
        val tradeId: UUID,
        val tenantId: UUID,
        val portfolioId: UUID,
        val assetId: UUID,
        val price: BigDecimal,
        val tradeDate: LocalDate,
    )

    companion object {
        const val EVENT_TYPE = "TRADE_RECORDED"

        /** 로컬 ObjectMapper — JavaTimeModule 포함, 빈 주입 불필요 */
        val MAPPER = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    }
}
