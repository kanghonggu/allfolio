package com.allfolio.broker.toss

import com.allfolio.broker.BrokerType
import com.allfolio.trade.application.RecordTradeCommand
import com.allfolio.trade.domain.TradeType
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Toss DTO → RecordTradeCommand 변환 (Anti-Corruption Layer)
 *
 * 종목코드 → assetId: UUID.nameUUIDFromBytes("toss-asset:{stockCode}")
 * 실운영 시 종목 마스터 테이블 기반 매핑으로 교체 가능.
 */
object TossTradeMapper {

    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    fun assetId(stockCode: String): UUID =
        UUID.nameUUIDFromBytes("toss-asset:$stockCode".toByteArray(Charsets.UTF_8))

    fun toCommand(
        dto: TossTradeDto,
        tenantId: UUID,
        portfolioId: UUID,
    ): RecordTradeCommand = RecordTradeCommand(
        tenantId        = tenantId,
        portfolioId     = portfolioId,
        assetId         = assetId(dto.stockCode),
        tradeType       = if (dto.orderType.equals("BUY", ignoreCase = true)) TradeType.BUY else TradeType.SELL,
        quantity        = dto.orderedQuantity,
        price           = dto.executedPrice,
        fee             = dto.commissionAmount,
        tradeCurrency   = dto.currencyCode,
        executedAt      = LocalDateTime.parse("${dto.orderDate}${dto.orderTime}", DATE_FMT),
        brokerType      = BrokerType.TOSS.name,
        externalTradeId = dto.orderId,
    )
}
