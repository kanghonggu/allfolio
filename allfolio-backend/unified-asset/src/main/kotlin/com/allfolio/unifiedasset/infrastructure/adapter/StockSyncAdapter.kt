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
import java.time.LocalDate

/**
 * STOCK 계좌 동기화 어댑터
 *
 * ua_stock_trades를 읽어 종목별 포지션을 계산하고 Asset을 생성한다.
 * - BUY/SELL 거래를 종목별로 그룹화 (시간순 처리)
 * - 순 수량(BUY - SELL) > 0 인 종목만 자산으로 등록
 * - 평균 매수가: 이동평균법 — BUY 시 (보유금액 + 매수금액) / (보유수량 + 매수수량)
 *   SELL 시 평균가 유지, 전량 매도 후 재매수 시 신규 평균가로 초기화
 * - currentValue: 종목코드가 있으면 Yahoo Finance 당일 시세, 국내 6자리 코드는 FSC(금융위원회)
 *   전일 종가(주식시세정보 → ETF는 증권상품시세정보)로 폴백, 코드가 없으면 평균 매수가
 *   (순서의 근거는 [fetchLivePrice] 참조)
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
                val live = pos.symbol?.let { sym ->
                    fetchLivePrice(sym, pos.stockName, account.id.toString())
                } ?: run {
                    log.warn(
                        "[StockSync] symbol 없음 — 원가를 현재가로 사용 (수익률 0%): stockName={}, accountId={}. " +
                        "거래 입력 시 종목코드(예: 005930)를 입력하면 실시간 수익률을 조회할 수 있습니다.",
                        pos.stockName, account.id,
                    )
                    null
                }

                val currentPrice = live?.price ?: pos.avgCost
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
                    valuationMethod = if (live != null) ValuationMethod.MARKET_PRICE else ValuationMethod.USER_INPUT,
                    // Yahoo 값이면 null이다 — 근거는 [LivePrice.asOf]
                    priceAsOf       = live?.asOf,
                )
            }
    }

    /**
     * Yahoo 먼저, 국내 6자리 코드만 FSC로 폴백.
     *
     * **이 순서를 뒤집지 말 것.** FSC(공공데이터포털)는 당일 종가를 주지 않는다 — 제공자가
     * "데이터 갱신은 기준일자로부터 영업일 하루 뒤"라고 명시한다. FSC를 먼저 물으면 값이 늘
     * 있으므로 폴백까지 내려갈 일이 없고, 그래서 화면의 "현재가"가 영구히 전일 종가가 된다
     * (2026-08-20 실측: 장 마감 3시간 뒤에도 삼성전자가 8/19 종가 247,500이었다).
     * 같은 시각 Yahoo는 당일 15:30 종가를 줬다.
     *
     * 실제로 2026-05-05 `fb5ae63`이 종목 마스터 갱신을 넣으면서 이 순서를 FSC 우선으로
     * 바꿨고, 그게 3개월 반 동안 안 보인 회귀였다.
     *
     * FSC를 남겨 두는 이유는 Yahoo가 비공식이라 언제든 막힐 수 있어서다 — 그때 원가로
     * 떨어지는 것보다 하루 늦은 공식 종가가 낫다.
     *
     * **국내 코드의 폴백은 두 단이다.** FSC 주식시세정보는 ETF를 담고 있지 않다 —
     * 2026-08-21 실측: `likeSrtnCd=395270`(HANARO Fn K-반도체)에 `totalCount=0`이 온다.
     * 그래서 ETF만 Yahoo가 막히면 폴백 없이 원가로 떨어져 수익률이 영구히 0%였다.
     * ETF는 증권상품시세정보라는 **다른 서비스**에 있고, 그게 세 번째 단이다.
     *
     * ETF인지 미리 가려내지 않고 그냥 순서대로 묻는다. 종목이 ETF인지 아는 방법이 상장종목
     * 마스터를 뒤지는 것뿐인데, 그 대가로 얻는 건 폴백의 폴백에서 헛호출 한 번을 아끼는
     * 것뿐이다 — 애초에 Yahoo가 막혔을 때만 도는 경로다.
     *
     * **세 단이 이 순서인 근거는 이제 잰 값이다.** 2026-08-21 11:42 KST 같은 시각 관측:
     * FSC는 주식(005930)도 ETF(395270)도 최신 `basDt`가 `20260820`으로 **둘 다 D+1**이고,
     * Yahoo는 같은 종목에 당일 값을 준다. 신선한 쪽이 먼저, 공식이되 하루 늦은 쪽이 뒤다.
     */
    private fun fetchLivePrice(symbol: String, stockName: String, accountId: String): LivePrice? {
        val isKrCode = symbol.matches(Regex("\\d{6}"))
        // 6자리 코드면 getPrice가 .KS → .KQ 순으로 알아서 붙인다
        val live = yahooFinanceClient.getPrice(symbol)?.let { LivePrice(it, asOf = null) }
            ?: if (isKrCode) {
                (fscStockClient.getPrice(symbol) ?: fscStockClient.getEtfPrice(symbol))
                    ?.let { LivePrice(it.price, it.asOf) }
            } else null
        if (live == null) log.warn(
            "[StockSync] 실시간 시세 조회 실패: symbol={}, stockName={}, accountId={}",
            symbol, stockName, accountId,
        )
        return live
    }

    /**
     * 현재가 한 건과 그 값이 **언제 것인지**.
     *
     * @param asOf **Yahoo 값이면 null이다.** 둘째 이유가 더 중요하다:
     *  1. Yahoo는 당일 값이라 "옛날 값"이라는 경고가 필요 없다.
     *  2. 🔴 **장중이면 그 값은 종가가 아니다.** 화면 라벨이 `"8/20 종가 기준"`으로 박혀 있어
     *     (`frontend/lib/price-as-of.ts`) 장중 값에 날짜를 달면 없느니만 못한 거짓말이 된다.
     *
     * FSC로 떨어졌을 때만 채운다 — 그쪽은 D+1 확정 종가라 날짜가 곧 사실이고, 사용자가
     * 그 숫자를 지금 시세로 오해하는 걸 막는 게 이 필드의 존재 이유다(A1·N2의 금과 같다).
     */
    private data class LivePrice(val price: BigDecimal, val asOf: LocalDate?)
}
