package com.allfolio.unifiedasset.infrastructure.adapter

import com.allfolio.unifiedasset.application.port.StockTradeRepository
import com.allfolio.unifiedasset.application.port.SyncAdapter
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.StockTradeType
import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.asset.AssetCategory
import com.allfolio.unifiedasset.domain.asset.AssetSourceType
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.domain.asset.ValuationMethod
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * STOCK 계좌 동기화 어댑터
 *
 * ua_stock_trades를 읽어 종목별 포지션을 계산하고 Asset을 생성한다.
 * - BUY/SELL 거래를 종목별로 그룹화 (시간순 처리)
 * - 순 수량(BUY - SELL) > 0 인 종목만 자산으로 등록
 * - 평균 매수가: 이동평균법 — BUY 시 (보유금액 + 매수금액) / (보유수량 + 매수수량)
 *   SELL 시 평균가 유지, 전량 매도 후 재매수 시 신규 평균가로 초기화
 * - currentValue: 6자리 숫자 종목코드 → FSC API(금융위원회), 해외종목 → Yahoo Finance, 없으면 평균 매수가
 */
@Component
class StockSyncAdapter(
    private val stockTradeRepository: StockTradeRepository,
    private val yahooFinanceClient: YahooFinanceClient,
    private val fscStockClient: FscStockClient,
) : SyncAdapter {

    private val log = LoggerFactory.getLogger(javaClass)

    override val supportedProvider = AccountProvider.STOCK

    override fun sync(account: Account): List<Asset> {
        val trades = stockTradeRepository.findByAccountId(account.id)
            .sortedBy { it.tradedAt }

        // 이동평균법: BUY마다 (현재보유금액 + 신규매수금액) / (현재수량 + 신규수량) 으로 갱신
        data class Position(
            val stockName: String,
            val symbol: String?,
            var quantity: BigDecimal = BigDecimal.ZERO,
            var avgCost: BigDecimal = BigDecimal.ZERO,
        )

        val positions = mutableMapOf<String, Position>()

        for (trade in trades) {
            val key = trade.symbol ?: trade.stockName
            val pos = positions.getOrPut(key) {
                Position(stockName = trade.stockName, symbol = trade.symbol)
            }

            when (trade.tradeType) {
                StockTradeType.BUY, StockTradeType.CREDIT_BUY -> {
                    val cost = if (trade.totalAmount > BigDecimal.ZERO) trade.totalAmount
                               else trade.price.multiply(trade.quantity)
                    val newQty = pos.quantity + trade.quantity
                    // 이동평균: (기존보유금액 + 신규매수금액) / 신규총수량
                    pos.avgCost = (pos.quantity.multiply(pos.avgCost) + cost)
                        .divide(newQty, 4, RoundingMode.HALF_UP)
                    pos.quantity = newQty
                }
                StockTradeType.SELL, StockTradeType.CREDIT_SELL -> {
                    pos.quantity -= trade.quantity
                    // 전량 매도 시 평균가 초기화 (이후 재매수면 새 평균가로 시작)
                    if (pos.quantity <= BigDecimal.ZERO) {
                        pos.quantity = BigDecimal.ZERO
                        pos.avgCost = BigDecimal.ZERO
                    }
                }
                else -> { /* DIVIDEND, MARGIN 등 수량 변동 없음 */ }
            }
        }

        return positions.values
            .filter { it.quantity > BigDecimal.ZERO }
            .map { pos ->
                val livePrice = pos.symbol?.let { sym ->
                    fetchLivePrice(sym, pos.stockName, account.id.toString())
                } ?: run {
                    log.warn(
                        "[StockSync] symbol 없음 — 원가를 현재가로 사용 (수익률 0%): stockName={}, accountId={}. " +
                        "거래 입력 시 종목코드(예: 005930)를 입력하면 실시간 수익률을 조회할 수 있습니다.",
                        pos.stockName, account.id,
                    )
                    null
                }

                val currentPrice = livePrice ?: pos.avgCost
                val currentValue = currentPrice.multiply(pos.quantity).setScale(2, RoundingMode.HALF_UP)

                Asset.create(
                    userId          = account.userId,
                    accountId       = account.id,
                    category        = AssetCategory.FINANCIAL,
                    type            = AssetType.STOCK,
                    sourceType      = AssetSourceType.STOCK_API,
                    name            = pos.stockName,
                    symbol          = pos.symbol,
                    quantity        = pos.quantity,
                    purchasePrice   = pos.avgCost,
                    currentValue    = currentValue,
                    currency        = account.currency,
                    valuationMethod = if (livePrice != null) ValuationMethod.MARKET_PRICE else ValuationMethod.USER_INPUT,
                )
            }
    }

    // 6자리 숫자 코드 → FSC API(KR), 그 외 → Yahoo Finance
    private fun fetchLivePrice(symbol: String, stockName: String, accountId: String): BigDecimal? {
        val isKrCode = symbol.matches(Regex("\\d{6}"))
        val price = if (isKrCode) {
            fscStockClient.getPrice(symbol) ?: yahooFinanceClient.getPrice("$symbol.KS")
                ?: yahooFinanceClient.getPrice("$symbol.KQ")
        } else {
            yahooFinanceClient.getPrice(symbol)
        }
        if (price == null) log.warn(
            "[StockSync] 실시간 시세 조회 실패: symbol={}, stockName={}, accountId={}",
            symbol, stockName, accountId,
        )
        return price
    }
}
