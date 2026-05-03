package com.allfolio.broker.kiwoom

import com.allfolio.trade.application.RecordTradeCommand
import com.allfolio.trade.domain.TradeType
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

object KiwoomTradeMapper {

    private val DATETIME_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    fun toCommand(item: KiwoomOrderItem, portfolioId: UUID, userId: UUID): RecordTradeCommand? {
        val qty   = item.filledQty.toBigDecimalOrNull()   ?: return null
        val price = item.filledPrice.toBigDecimalOrNull() ?: return null
        val fee   = item.fee.toBigDecimalOrNull()         ?: BigDecimal.ZERO
        if (qty <= BigDecimal.ZERO || price <= BigDecimal.ZERO) return null

        val tradeType = when (item.orderType.uppercase()) {
            "BUY"  -> TradeType.BUY
            "SELL" -> TradeType.SELL
            else   -> return null
        }

        val executedAt = runCatching {
            LocalDateTime.parse(item.orderDate + item.orderTime.padEnd(6, '0'), DATETIME_FMT)
        }.getOrElse { LocalDateTime.now() }

        return RecordTradeCommand(
            tenantId        = userId,
            portfolioId     = portfolioId,
            brokerType      = "KIWOOM",
            externalTradeId = item.orderNo,
            assetId         = assetId(item.stockCode),
            tradeType       = tradeType,
            quantity        = qty,
            price           = price,
            fee             = fee,
            tradeCurrency   = "KRW",
            executedAt      = executedAt,
        )
    }

    fun assetId(stockCode: String): UUID =
        UUID.nameUUIDFromBytes("KIWOOM:$stockCode".toByteArray())
}
