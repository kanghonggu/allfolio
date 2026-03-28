package com.allfolio.trade.infrastructure.entity

import com.allfolio.trade.domain.TradeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * trade_raw — INSERT ONLY
 *
 * 설계 원칙:
 * - UPDATE 금지 (@Immutable)
 * - DELETE 금지 (Repository에 delete 메서드 없음)
 * - 모든 Snapshot은 이 테이블 기반으로 재계산 가능
 *
 * 인덱스 전략:
 * - (portfolioId, executedAt) : 포트폴리오별 시간순 조회 (핵심 쿼리)
 * - (portfolioId, assetId)    : 자산별 포지션 계산용
 */
@Entity
@Immutable
@Table(
    name = "trade_raw",
    indexes = [
        Index(name = "idx_trade_raw_portfolio_executed", columnList = "portfolio_id, executed_at"),
        Index(name = "idx_trade_raw_portfolio_asset",    columnList = "portfolio_id, asset_id"),
        Index(name = "idx_trade_raw_asset",              columnList = "asset_id"),
        Index(name = "idx_trade_raw_broker_dedup",       columnList = "broker_type, external_trade_id", unique = true),
    ]
)
class TradeRawEntity(

    @Id
    @Column(columnDefinition = "uuid", updatable = false, nullable = false)
    val id: UUID,

    @Column(name = "portfolio_id", columnDefinition = "uuid", nullable = false, updatable = false)
    val portfolioId: UUID,

    @Column(name = "asset_id", columnDefinition = "uuid", nullable = false, updatable = false)
    val assetId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "trade_type", nullable = false, updatable = false, length = 10)
    val tradeType: TradeType,

    @Column(nullable = false, updatable = false, precision = 30, scale = 10)
    val quantity: BigDecimal,

    @Column(nullable = false, updatable = false, precision = 30, scale = 10)
    val price: BigDecimal,

    @Column(nullable = false, updatable = false, precision = 30, scale = 10)
    val fee: BigDecimal,

    @Column(name = "trade_currency", nullable = false, updatable = false, length = 10)
    val tradeCurrency: String,

    @Column(name = "executed_at", nullable = false, updatable = false)
    val executedAt: LocalDateTime,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime,

    /** 브로커 연동 거래 dedup — null이면 직접 입력 거래 */
    @Column(name = "broker_type", length = 20, updatable = false)
    val brokerType: String? = null,

    @Column(name = "external_trade_id", length = 100, updatable = false)
    val externalTradeId: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TradeRawEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String =
        "TradeRawEntity(id=$id, portfolioId=$portfolioId, assetId=$assetId, " +
        "type=$tradeType, qty=$quantity, price=$price, executedAt=$executedAt)"
}
