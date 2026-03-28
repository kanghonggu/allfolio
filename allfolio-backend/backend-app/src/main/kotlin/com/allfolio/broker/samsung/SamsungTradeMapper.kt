package com.allfolio.broker.samsung

import com.allfolio.broker.BrokerType
import com.allfolio.trade.application.RecordTradeCommand
import com.allfolio.trade.domain.TradeType
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Samsung DTO → RecordTradeCommand 변환 (Anti-Corruption Layer)
 *
 * buySellGb: "01"=매도(SELL), "02"=매수(BUY)
 * assetId: UUID.nameUUIDFromBytes("samsung-asset:{isinCd}")
 */
object SamsungTradeMapper {

    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    fun assetId(isinCd: String): UUID =
        UUID.nameUUIDFromBytes("samsung-asset:$isinCd".toByteArray(Charsets.UTF_8))

    fun toCommand(
        dto: SamsungTradeDto,
        tenantId: UUID,
        portfolioId: UUID,
    ): RecordTradeCommand = RecordTradeCommand(
        tenantId        = tenantId,
        portfolioId     = portfolioId,
        assetId         = assetId(dto.isinCd),
        tradeType       = if (dto.buySellGb == "02") TradeType.BUY else TradeType.SELL,
        quantity        = dto.ccldQty,
        price           = dto.ccldPrc,
        fee             = dto.commAmt,
        tradeCurrency   = dto.ccldCrnc,
        executedAt      = LocalDateTime.parse("${dto.ordDt}${dto.ordTm}", DATE_FMT),
        brokerType      = BrokerType.SAMSUNG.name,
        externalTradeId = dto.ordNo,
    )
}
