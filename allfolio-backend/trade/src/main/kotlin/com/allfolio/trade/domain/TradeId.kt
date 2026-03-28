package com.allfolio.trade.domain

import java.util.UUID

data class TradeId(val value: UUID) {

    override fun toString(): String = value.toString()

    companion object {
        fun newId(): TradeId = TradeId(UUID.randomUUID())

        fun of(value: String): TradeId = TradeId(UUID.fromString(value))

        fun of(value: UUID): TradeId = TradeId(value)
    }
}
