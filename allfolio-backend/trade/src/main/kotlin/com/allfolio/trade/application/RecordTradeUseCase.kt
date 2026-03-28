package com.allfolio.trade.application

import com.allfolio.trade.domain.TradeId
import com.allfolio.trade.domain.TradeRaw
import com.allfolio.trade.domain.TradeRecordedEvent
import com.allfolio.trade.infrastructure.mapper.TradeMapper
import com.allfolio.trade.infrastructure.outbox.OutboxEventPublisher
import com.allfolio.trade.infrastructure.repository.TradeRawJpaRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 거래 기록 UseCase
 *
 * 트랜잭션 전략:
 * - 단일 트랜잭션: trade_raw INSERT + outbox_event INSERT (원자성 보장)
 * - Snapshot 생성은 OutboxEventProcessor가 비동기 처리 (OLTP / OLAP 분리)
 *
 * Outbox 패턴:
 * - Trade와 Event가 같은 트랜잭션 → 정합성 보장
 * - Processor 실패 시 재처리 가능 (PENDING 상태 유지)
 */
@Service
@Transactional
class RecordTradeUseCase(
    private val tradeRepository: TradeRawJpaRepository,
    private val outboxEventPublisher: OutboxEventPublisher,
    private val applicationEventPublisher: ApplicationEventPublisher,
) {
    fun record(command: RecordTradeCommand): TradeId {
        val trade = TradeRaw.create(
            portfolioId   = command.portfolioId,
            assetId       = command.assetId,
            tradeType     = command.tradeType,
            quantity      = command.quantity,
            price         = command.price,
            fee           = command.fee,
            tradeCurrency = command.tradeCurrency,
            executedAt    = command.executedAt,
        )

        tradeRepository.save(TradeMapper.toEntity(trade, command.brokerType, command.externalTradeId))

        // 같은 트랜잭션 내 Outbox 이벤트 저장 — Trade 커밋 실패 시 Event도 롤백
        val outboxEventId = outboxEventPublisher.publishTradeRecorded(
            tradeId     = trade.id.value,
            tenantId    = command.tenantId,
            portfolioId = command.portfolioId,
            assetId     = command.assetId,
            price       = command.price,
            tradeDate   = command.executedAt.toLocalDate(),
        )

        // AFTER_COMMIT 이벤트 발행 — 커밋 완료 후 TradeEventListener 수신
        applicationEventPublisher.publishEvent(
            TradeRecordedEvent(
                outboxEventId = outboxEventId,
                tenantId      = command.tenantId,
                portfolioId   = command.portfolioId,
                assetId       = command.assetId,
                price         = command.price,
                tradeDate     = command.executedAt.toLocalDate(),
            )
        )

        return trade.id
    }
}
