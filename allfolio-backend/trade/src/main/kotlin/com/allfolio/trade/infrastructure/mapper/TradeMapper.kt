package com.allfolio.trade.infrastructure.mapper

import com.allfolio.trade.domain.TradeId
import com.allfolio.trade.domain.TradeRaw
import com.allfolio.trade.infrastructure.entity.TradeRawEntity

/**
 * TradeRaw 도메인 ↔ JPA Entity 변환
 *
 * 원칙:
 * - toEntity : 도메인 → Entity (저장 시)
 * - toDomain : Entity → 도메인 (조회 후 재구성)
 */
object TradeMapper {

    fun toEntity(domain: TradeRaw, brokerType: String? = null, externalTradeId: String? = null): TradeRawEntity = TradeRawEntity(
        id              = domain.id.value,
        portfolioId     = domain.portfolioId,
        assetId         = domain.assetId,
        tradeType       = domain.tradeType,
        quantity        = domain.quantity,
        price           = domain.price,
        fee             = domain.fee,
        tradeCurrency   = domain.tradeCurrency,
        executedAt      = domain.executedAt,
        createdAt       = domain.createdAt,
        brokerType      = brokerType,
        externalTradeId = externalTradeId,
    )

    fun toDomain(entity: TradeRawEntity): TradeRaw = TradeRaw.reconstruct(
        id            = TradeId.of(entity.id),
        portfolioId   = entity.portfolioId,
        assetId       = entity.assetId,
        tradeType     = entity.tradeType,
        quantity      = entity.quantity,
        price         = entity.price,
        fee           = entity.fee,
        tradeCurrency = entity.tradeCurrency,
        executedAt    = entity.executedAt,
        createdAt     = entity.createdAt,
    )

    fun toDomainList(entities: List<TradeRawEntity>): List<TradeRaw> =
        entities.map { toDomain(it) }
}
