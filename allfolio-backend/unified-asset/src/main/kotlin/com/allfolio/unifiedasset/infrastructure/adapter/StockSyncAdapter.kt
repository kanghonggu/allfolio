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
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * STOCK 계좌 동기화 어댑터
 *
 * ua_stock_trades를 읽어 종목별 포지션을 계산하고 Asset을 생성한다.
 * - BUY/SELL 거래를 종목별로 그룹화
 * - 순 수량(BUY - SELL) > 0 인 종목만 자산으로 등록
 * - 평균 매수가 = Σ(BUY 금액) / Σ(BUY 수량)  (가중평균)
 * - currentValue = 수량 × 평균매수가  (시세 없을 때 원가 기준)
 */
@Component
class StockSyncAdapter(
    private val stockTradeRepository: StockTradeRepository,
    private val yahooFinanceClient: YahooFinanceClient,
) : SyncAdapter {

    override val supportedProvider = AccountProvider.STOCK

    override fun sync(account: Account): List<Asset> {
        val trades = stockTradeRepository.findByAccountId(account.id)
            .sortedBy { it.tradedAt }

        // 종목별 집계: stockName → Position
        data class Position(
            val stockName: String,
            val symbol: String?,
            var quantity: BigDecimal = BigDecimal.ZERO,
            var totalBuyCost: BigDecimal = BigDecimal.ZERO,
            var totalBuyQty: BigDecimal = BigDecimal.ZERO,
        ) {
            fun avgCost(): BigDecimal =
                if (totalBuyQty > BigDecimal.ZERO)
                    totalBuyCost.divide(totalBuyQty, 4, RoundingMode.HALF_UP)
                else BigDecimal.ZERO
        }

        val positions = mutableMapOf<String, Position>()

        for (trade in trades) {
            val key = trade.symbol ?: trade.stockName
            val pos = positions.getOrPut(key) {
                Position(stockName = trade.stockName, symbol = trade.symbol)
            }

            when (trade.tradeType) {
                StockTradeType.BUY, StockTradeType.CREDIT_BUY -> {
                    // totalAmount가 있으면 실제 거래금액 사용, 없으면 price × quantity fallback
                    val cost = if (trade.totalAmount > BigDecimal.ZERO) trade.totalAmount
                               else trade.price.multiply(trade.quantity)
                    pos.quantity     += trade.quantity
                    pos.totalBuyCost += cost
                    pos.totalBuyQty  += trade.quantity
                }
                StockTradeType.SELL, StockTradeType.CREDIT_SELL -> {
                    pos.quantity -= trade.quantity
                }
                else -> { /* DIVIDEND, MARGIN 등 수량 변동 없음 */ }
            }
        }

        return positions.values
            .filter { it.quantity > BigDecimal.ZERO }
            .map { pos ->
                val avgCost      = pos.avgCost()
                val livePrice    = pos.symbol?.let { yahooFinanceClient.getPrice(it) }
                val currentPrice = livePrice ?: avgCost
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
                    purchasePrice   = avgCost,
                    currentValue    = currentValue,
                    currency        = account.currency,
                    valuationMethod = if (livePrice != null) ValuationMethod.MARKET_PRICE else ValuationMethod.USER_INPUT,
                )
            }
    }
}
