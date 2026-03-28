package com.allfolio.external.crypto

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable
import java.time.LocalDateTime
import java.util.UUID

/**
 * Binance 거래 동기화 커서
 *
 * (portfolioId, symbol) 기준으로 마지막으로 동기화한 Binance trade ID를 저장.
 * 다음 sync 시 fromId = lastTradeId + 1 → 중복 없이 신규 거래만 조회.
 */
@Embeddable
data class BinanceSyncCursorId(
    @Column(name = "portfolio_id", columnDefinition = "uuid") val portfolioId: UUID = UUID.randomUUID(),
    @Column(name = "symbol", length = 20)                     val symbol: String    = "",
) : Serializable

@Entity
@Table(name = "binance_sync_cursor")
class BinanceSyncCursorEntity(

    @EmbeddedId
    val id: BinanceSyncCursorId,

    /** 마지막으로 처리된 Binance trade ID. 0 = 최초 (미동기화) */
    @Column(name = "last_trade_id", nullable = false)
    var lastTradeId: Long = 0L,

    @Column(name = "synced_count", nullable = false)
    var syncedCount: Long = 0L,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)
