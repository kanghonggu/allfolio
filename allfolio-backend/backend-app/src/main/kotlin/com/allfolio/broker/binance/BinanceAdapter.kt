package com.allfolio.broker.binance

import com.allfolio.broker.BrokerAccountInfo
import com.allfolio.broker.BrokerAdapter
import com.allfolio.broker.BrokerTradeResult
import com.allfolio.broker.BrokerType
import com.allfolio.external.crypto.BinanceApiClient
import com.allfolio.external.crypto.BinanceProperties
import com.allfolio.external.crypto.BinanceTradeMapper
import com.allfolio.trade.application.RecordTradeCommand
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Binance BrokerAdapter 구현체
 *
 * 기존 BinanceApiClient / BinanceTradeMapper를 래핑.
 * BinanceSyncService는 수정하지 않고 그대로 유지 (레거시 경로).
 * 이 Adapter는 BrokerFacade 경로에서 사용.
 *
 * cursor = lastTradeId (Long as String, "0" = 최초 조회)
 */
@Component
class BinanceAdapter(
    private val binanceApiClient: BinanceApiClient,
    private val binanceProperties: BinanceProperties,
) : BrokerAdapter {

    override val brokerType = BrokerType.BINANCE

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @param accountId Binance symbol (e.g. "BTCUSDT")
     * @param cursor    마지막 Binance trade ID ("0" = 처음부터)
     */
    override fun fetchTrades(
        portfolioId: UUID,
        accountId: String,
        cursor: String,
    ): BrokerTradeResult {
        val fromId = cursor.toLongOrNull()?.takeIf { it > 0 }?.let { it + 1 } ?: 0L

        val trades = binanceApiClient.fetchMyTrades(
            symbol  = accountId,
            fromId  = fromId,
            limit   = 100,
        )

        if (trades.isEmpty()) return BrokerTradeResult(emptyList(), cursor)

        val commands: List<RecordTradeCommand> = trades.map { dto ->
            BinanceTradeMapper.toCommand(dto, binanceProperties.tenantId, portfolioId)
                .copy(
                    brokerType      = BrokerType.BINANCE.name,
                    externalTradeId = "${accountId}:${dto.id}",
                )
        }

        val nextCursor = trades.maxOf { it.id }.toString()
        log.info("[BinanceAdapter] fetched {} trades symbol={} cursor={}", trades.size, accountId, nextCursor)

        return BrokerTradeResult(commands, nextCursor)
    }

    /** Binance는 계좌 개념 없음 — 설정된 symbols를 accountId로 반환 */
    override fun fetchAccounts(userId: UUID): List<BrokerAccountInfo> =
        binanceProperties.symbolList().map { symbol ->
            BrokerAccountInfo(
                accountId   = symbol,
                accountName = "Binance $symbol",
                currency    = "USDT",
            )
        }
}
