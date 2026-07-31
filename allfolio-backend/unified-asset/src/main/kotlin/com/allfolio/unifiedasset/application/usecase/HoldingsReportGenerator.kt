package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.application.GeneratedReport
import com.allfolio.report.application.ReportBodyGenerator
import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.report.domain.archive.ReportType
import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.application.port.StockTradeRepository
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.UUID

/**
 * R-05 월말 보유 명세서 생성 엔진 (R2 #40 BE).
 * ua_assets 보유 자산의 종목별 명세(수량·평단·평가액·평가손익)와 계좌/자산군 소계를 본문에 고정.
 * 월말 스냅샷 히스토리 부재 → 생성 시점 보유 기준(asOf=period.end). 자산 0건은 예외 없는 유효 0 보고서.
 * 당월 실현손익(FIFO)은 ua_stock_trades 기반으로 계산해 realized 섹션에 반영한다.
 * v1 제외 해소: 지역 노출(통화 파생) 포함. 후속: 국가/거래소 필드 기반 정밀 분류.
 */
@Component
class HoldingsReportGenerator(
    private val assetRepository: AssetRepository,
    private val accountRepository: AccountRepository,
    private val fx: FxConverter,
    private val stockTradeRepository: StockTradeRepository,
) : ReportBodyGenerator {

    override val type = ReportType.HOLDINGS

    private val mapper = jacksonObjectMapper()
    private val mc = MathContext(10, RoundingMode.HALF_UP)

    override fun generate(userId: UUID, period: ReportPeriod): GeneratedReport {
        val assets = assetRepository.findByUserId(userId)
        val accounts = accountRepository.findByUserId(userId)
        val labels = accounts.associate { it.id to Pair(it.accountName, it.provider.name) }

        val trades = accounts.flatMap { stockTradeRepository.findByAccountId(it.id) }
        val realizedBySymbol = FifoRealizedPnlCalculator.calculate(trades, period)
        val realizedTotal = realizedBySymbol.values.fold(BigDecimal.ZERO) { a, b -> a + b }
        val nameBySymbol = trades.filter { it.symbol != null }
            .groupBy { it.symbol!! }
            .mapValues { (_, ts) -> ts.maxByOrNull { it.tradedAt }!!.stockName }

        val valued = assets.map { it to it.currentValueInKrw(fx) }
        val totalKrw = valued.fold(BigDecimal.ZERO) { acc, (_, v) -> acc + v }

        // 실현손익은 심볼 단위 → 같은 심볼이 여러 행(계좌)일 때 중복 표시 방지 위해 첫(최대 평가액) 행에만 귀속.
        val realizedAttributed = HashSet<String>()
        val holdings = valued.sortedByDescending { it.second }.map { (a, valueKrw) ->
            val (accName, provider) = labels[a.accountId] ?: Pair("-", "-")
            val rowRealized = a.symbol?.takeIf { realizedAttributed.add(it) }?.let { realizedBySymbol[it] } ?: BigDecimal.ZERO
            mapOf(
                "name" to a.name, "symbol" to a.symbol, "type" to a.type.name,
                "account" to accName, "provider" to provider,
                "quantity" to a.quantity, "avgPrice" to a.purchasePrice,
                "currentValue" to a.currentValue, "valueKrw" to valueKrw,
                "weight" to pct(valueKrw, totalKrw),
                "unrealizedPnl" to a.unrealizedPnlInKrw(fx), "returnRate" to a.returnRate(),
                "realizedPnl" to rowRealized,
            )
        }

        val byAccount = valued.groupBy { it.first.accountId }.map { (accId, g) ->
            val sum = g.fold(BigDecimal.ZERO) { acc, (_, v) -> acc + v }
            val (accName, provider) = labels[accId] ?: Pair("-", "-")
            mapOf(
                "account" to accName, "provider" to provider, "valueKrw" to sum,
                "weight" to pct(sum, totalKrw), "holdingCount" to g.size,
            )
        }.sortedByDescending { it["valueKrw"] as BigDecimal }

        val byType = valued.groupBy { it.first.type }.map { (t, g) ->
            val sum = g.fold(BigDecimal.ZERO) { acc, (_, v) -> acc + v }
            mapOf("type" to t.name, "valueKrw" to sum, "weight" to pct(sum, totalKrw), "holdingCount" to g.size)
        }.sortedByDescending { it["valueKrw"] as BigDecimal }

        val byRegion = valued.groupBy { CurrencyRegionMapper.regionOf(it.first.currency) }
            .map { (region, g) ->
                val sum = g.fold(BigDecimal.ZERO) { acc, (_, v) -> acc + v }
                mapOf("region" to region, "valueKrw" to sum, "weight" to pct(sum, totalKrw), "holdingCount" to g.size)
            }.sortedByDescending { it["valueKrw"] as BigDecimal }

        val cashValued = valued.filter { it.first.type == AssetType.CASH }
        val cashKrw = cashValued.fold(BigDecimal.ZERO) { acc, (_, v) -> acc + v }
        val cash = cashValued.map { (a, valueKrw) ->
            val (accName, _) = labels[a.accountId] ?: Pair("-", "-")
            mapOf("account" to accName, "currency" to a.currency, "valueKrw" to valueKrw)
        }

        val unrealizedTotal = assets.fold(BigDecimal.ZERO) { acc, a -> acc + a.unrealizedPnlInKrw(fx) }

        val monthlyChange = MonthlyChangeCalculator.build(trades, period, realizedBySymbol, nameBySymbol)

        val body = mapOf(
            "summary" to mapOf(
                "totalValueKrw" to totalKrw, "holdingCount" to assets.size,
                "accountCount" to accounts.size, "cashWeight" to pct(cashKrw, totalKrw),
                "unrealizedPnlKrw" to unrealizedTotal,
                "realizedPnlKrw" to realizedTotal,
            ),
            "holdings" to holdings,
            "byAccount" to byAccount,
            "byType" to byType,
            "byRegion" to byRegion,
            "cash" to cash,
            "realized" to realizedBySymbol.filterValues { it.signum() != 0 }
                .map { (sym, pnl) -> mapOf("symbol" to sym, "name" to (nameBySymbol[sym] ?: sym), "realizedPnl" to pnl) }
                .sortedByDescending { it["realizedPnl"] as BigDecimal },
            "monthlyChange" to mapOf(
                "newEntries" to monthlyChange.newEntries.map {
                    mapOf("symbol" to it.symbol, "name" to it.name, "firstBuyDate" to it.firstBuyDate.toString(), "buyPrice" to it.buyPrice)
                },
                "soldOut" to monthlyChange.soldOut.map {
                    mapOf("symbol" to it.symbol, "name" to it.name, "soldOutDate" to it.soldOutDate.toString(), "realizedPnl" to it.realizedPnl)
                },
                "qtyChanges" to monthlyChange.qtyChanges.map {
                    mapOf("symbol" to it.symbol, "name" to it.name, "netQty" to it.netQty, "netBuyAmount" to it.netBuyAmount)
                },
            ),
            "note" to "보유·평가액은 보고서 생성 시점 기준 · 당월 실현손익은 수동 입력 거래(ua_stock_trades) 기준",
        )
        return GeneratedReport(asOfDate = period.end, bodyJson = mapper.writeValueAsString(body))
    }

    private fun pct(a: BigDecimal, b: BigDecimal): BigDecimal =
        if (b <= BigDecimal.ZERO) BigDecimal.ZERO
        else a.divide(b, mc).multiply(BigDecimal(100), mc).setScale(2, RoundingMode.HALF_UP)
}
