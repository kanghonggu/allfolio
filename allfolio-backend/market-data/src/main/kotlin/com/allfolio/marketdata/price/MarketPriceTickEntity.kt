package com.allfolio.marketdata.price

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(
    name = "market_price_tick",
    indexes = [
        Index(name = "idx_mpt_symbol_ts",   columnList = "symbol, tick_timestamp DESC"),
        Index(name = "idx_mpt_exchange_ts", columnList = "exchange, tick_timestamp DESC"),
    ]
)
class MarketPriceTickEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, length = 20)
    val exchange: String,

    @Column(nullable = false, length = 20)
    val symbol: String,

    @Column(nullable = false, precision = 30, scale = 10)
    val price: BigDecimal,

    @Column(nullable = false, precision = 30, scale = 10)
    val volume: BigDecimal = BigDecimal.ZERO,

    @Column(name = "tick_timestamp", nullable = false)
    val tickTimestamp: Instant,

    @Column(name = "received_at", nullable = false)
    val receivedAt: Instant = Instant.now(),
)
