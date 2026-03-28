package com.allfolio.broker

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable
import java.time.LocalDateTime
import java.util.UUID

/**
 * 브로커 동기화 공통 커서
 *
 * BinanceSyncCursorEntity를 대체하는 범용 구조.
 * cursor_value 의미는 브로커마다 다름:
 *   BINANCE → lastTradeId (Long as String)
 *   TOSS    → lastOrderId or yyyyMMddHHmmss
 */
@Embeddable
data class BrokerSyncStateId(
    @Column(name = "portfolio_id", columnDefinition = "uuid") val portfolioId: UUID   = UUID.randomUUID(),
    @Column(name = "broker_type",  length = 20)               val brokerType: String  = "",
    @Column(name = "account_id",   length = 100)              val accountId: String   = "",
) : Serializable

@Entity
@Table(name = "broker_sync_state")
class BrokerSyncStateEntity(

    @EmbeddedId
    val id: BrokerSyncStateId,

    @Column(name = "cursor_value", length = 200, nullable = false)
    var cursorValue: String = "",

    @Column(name = "synced_count", nullable = false)
    var syncedCount: Long = 0L,

    @Column(name = "last_synced_at")
    var lastSyncedAt: LocalDateTime? = null,
)
