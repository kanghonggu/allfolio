package com.allfolio.trade.application

import com.allfolio.trade.domain.TradeType
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * Trade 기록 커맨드
 *
 * Application Layer 진입점.
 * DTO와 분리 — Controller에서 Command로 변환 후 UseCase에 전달.
 */
data class RecordTradeCommand(
    val tenantId: UUID,
    val portfolioId: UUID,
    /** 브로커 연동 거래 중복 방지. null = 직접 입력 거래 */
    val brokerType: String? = null,
    val externalTradeId: String? = null,
    val assetId: UUID,
    val tradeType: TradeType,
    val quantity: BigDecimal,
    val price: BigDecimal,
    val fee: BigDecimal,
    val tradeCurrency: String,
    val executedAt: LocalDateTime,
)
