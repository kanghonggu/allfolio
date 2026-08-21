package com.allfolio.broker.kis

import com.allfolio.trade.application.RecordTradeCommand
import com.allfolio.trade.domain.TradeType
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

object KisTradeMapper {

    private val log = LoggerFactory.getLogger(javaClass)

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

        // 주문일시를 못 읽으면 **지어내지 않고 건너뛴다.**
        //
        // 여기 있던 `getOrElse { LocalDateTime.now() }`는 호스트 벽시계(운영 컨테이너는 UTC)라,
        // KIS가 준 한국 거래소 벽시계와 같은 컬럼에 섞였다. 게다가 RecordTradeUseCase가
        // `tradeDate = executedAt.toLocalDate()`로 날짜를 잘라 쓰므로 폴백이 걸린 거래는 날짜가
        // 하루 어긋날 수 있고, 그 날짜는 일별 스냅샷과 포지션 엔진의 입력이다.
        //
        // 건너뛴 항목은 다음 동기화에서 다시 시도된다. 계속 못 읽으면 포지션이 빠지는데, 그건
        // 대사(reconciliation)가 수량 불일치로 잡아낸다 — 지어낸 날짜는 아무도 못 잡는다.
        val executedAt = runCatching {
            val dateTimeStr = item.orderDate + item.orderTime.padEnd(6, '0')
            LocalDateTime.parse(dateTimeStr, DATETIME_FMT)
        }.getOrElse {
            log.warn(
                "[KisTradeMapper] 주문일시를 읽을 수 없어 건너뜀 orderNo={} ord_dt='{}' ord_tmd='{}'",
                item.orderNo, item.orderDate, item.orderTime,
            )
            return null
        }

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
