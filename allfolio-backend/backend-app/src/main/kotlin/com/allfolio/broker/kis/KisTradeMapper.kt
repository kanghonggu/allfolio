package com.allfolio.broker.kis

import com.allfolio.trade.application.RecordTradeCommand
import com.allfolio.trade.domain.TradeType
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

object KisTradeMapper {

    private val DATE_FMT     = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val DATETIME_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    fun toCommand(item: KisOrderItem, portfolioId: UUID, userId: UUID): RecordTradeCommand? {
        val qty   = item.filledQty.toBigDecimalOrNull() ?: return null
        val price = item.avgPrice.toBigDecimalOrNull()  ?: return null
        if (qty <= BigDecimal.ZERO || price <= BigDecimal.ZERO) return null

        val tradeType = when (item.sideCode) {
            "02" -> TradeType.BUY
            "01" -> TradeType.SELL
            else -> return null
        }

        val executedAt = runCatching {
            val dateTimeStr = item.orderDate + item.orderTime.padEnd(6, '0')
            LocalDateTime.parse(dateTimeStr, DATETIME_FMT)
        }.getOrElse { LocalDateTime.now() }

        // KIS 종목코드를 결정론적 UUID로 변환 (Binance와 동일 방식)
        val assetId = assetId(item.stockCode)

        return RecordTradeCommand(
            tenantId        = userId,
            portfolioId     = portfolioId,
            brokerType      = "KIS",
            externalTradeId = item.orderNo,
            assetId         = assetId,
            tradeType       = tradeType,
            quantity        = qty,
            price           = price,
            fee             = BigDecimal.ZERO,
            tradeCurrency   = "KRW",
            executedAt      = executedAt,
        )
    }

    /** 종목코드 → 결정론적 UUID (동일 코드 = 동일 UUID 보장) */
    fun assetId(stockCode: String): UUID =
        UUID.nameUUIDFromBytes("KIS:$stockCode".toByteArray())
}
