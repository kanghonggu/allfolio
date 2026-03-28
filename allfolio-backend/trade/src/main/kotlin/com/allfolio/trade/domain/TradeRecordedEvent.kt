package com.allfolio.trade.domain

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Trade 저장 완료 도메인 이벤트
 *
 * Spring ApplicationEvent로 발행 — @TransactionalEventListener(AFTER_COMMIT) 수신
 * outboxEventId: Listener가 PENDING → PROCESSED 전이 시 사용
 */
data class TradeRecordedEvent(
    val outboxEventId: UUID,
    val tenantId: UUID,
    val portfolioId: UUID,
    val assetId: UUID,
    val price: BigDecimal,
    val tradeDate: LocalDate,
)
