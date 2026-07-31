package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.application.GeneratedReport
import com.allfolio.report.application.ReportBodyGenerator
import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.report.domain.archive.ReportType
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.DividendLedgerSource
import com.allfolio.unifiedasset.application.port.DividendRecord
import com.allfolio.unifiedasset.application.port.FxConverter
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.util.UUID

/**
 * R-03 배당·이자 보고서 생성 엔진 (R1 #38 BE).
 * ua_stock_trades DIVIDEND 행의 세전(total_amount)·원천징수(tax)·세후(차액)를 본문에 고정.
 * 금액은 KRW 취급(통화 컬럼 부재). 배당 0건은 예외가 아닌 유효한 0 보고서.
 * 배당 캘린더(지급 이력 패턴, 사실형) 포함. 후속: 외부 확정 지급일·기대세율 비교.
 * v1 제외: 세율 마스터·기대세율 비교, 이자.
 */
@Component
class DividendInterestReportGenerator(
    private val ledger: DividendLedgerSource,
    private val assetRepository: AssetRepository,
    private val fx: FxConverter,
) : ReportBodyGenerator {

    override val type = ReportType.DIVIDEND_INTEREST

    private val mapper = jacksonObjectMapper()
    private val mc = MathContext(10, RoundingMode.HALF_UP)
    private val numericSymbol = Regex("^[0-9]+$")

    override fun generate(userId: UUID, period: ReportPeriod): GeneratedReport {
        val records = ledger.findDividends(userId, period.start, period.end)
        val ttm = ledger.findDividends(userId, period.end.minusYears(1), period.end)

        val gross = records.sum { it.gross }
        val tax = records.sum { it.tax }
        val net = gross - tax

        val portfolioKrw = assetRepository.findByUserId(userId)
            .fold(BigDecimal.ZERO) { acc, a -> acc + a.currentValueInKrw(fx) }
        val ttmNet = ttm.sum { it.net }
        val ttmYield: BigDecimal? =
            if (portfolioKrw <= BigDecimal.ZERO) null else pct(ttmNet, portfolioKrw)

        val receipts = records.map {
            mapOf(
                "payDate" to it.payDate.toString(), "stockName" to it.stockName, "symbol" to it.symbol,
                "account" to it.accountName, "gross" to it.gross, "tax" to it.tax, "net" to it.net,
            )
        }

        val monthly = records.groupBy { it.payDate.toString().substring(0, 7) }
            .map { (m, rs) -> mapOf("month" to m, "net" to rs.sum { it.net }) }
            .sortedBy { it["month"] as String }

        val bySymbol = records.groupBy { it.stockName to it.symbol }
            .map { (key, rs) ->
                val g = rs.sum { it.gross }; val t = rs.sum { it.tax }
                mapOf(
                    "stockName" to key.first, "symbol" to key.second,
                    "gross" to g, "tax" to t, "net" to (g - t), "weight" to pct(g - t, net),
                )
            }.sortedByDescending { it["net"] as BigDecimal }

        val byCountry = records.groupBy { if (it.symbol?.matches(numericSymbol) == true) "국내" else "해외" }
            .map { (country, rs) ->
                val g = rs.sum { it.gross }; val t = rs.sum { it.tax }
                mapOf(
                    "country" to country, "gross" to g, "tax" to t, "net" to (g - t),
                    "effectiveTaxRate" to pct(t, g),
                )
            }.sortedByDescending { it["gross"] as BigDecimal }

        val calendar = DividendCalendarCalculator.build(ttm)

        val body = mapOf(
            "summary" to mapOf(
                "grossTotal" to gross, "withholdingTax" to tax, "netTotal" to net,
                "effectiveTaxRate" to pct(tax, gross), "receiptCount" to records.size,
                "ttmYield" to ttmYield,
            ),
            "receipts" to receipts,
            "monthly" to monthly,
            "bySymbol" to bySymbol,
            "byCountry" to byCountry,
            "dividendCalendar" to calendar.map {
                mapOf("symbol" to it.symbol, "stockName" to it.stockName, "cadence" to it.cadence,
                      "paidMonths" to it.paidMonths, "payCount" to it.payCount,
                      "lastPayDate" to it.lastPayDate.toString(), "ttmNet" to it.ttmNet)
            },
        )
        val asOf = records.maxOfOrNull { it.payDate } ?: period.end
        return GeneratedReport(asOfDate = asOf, bodyJson = mapper.writeValueAsString(body))
    }

    private fun List<DividendRecord>.sum(sel: (DividendRecord) -> BigDecimal): BigDecimal =
        fold(BigDecimal.ZERO) { acc, r -> acc + sel(r) }

    /** a/b × 100, 0~100 스케일 (b<=0이면 0) */
    private fun pct(a: BigDecimal, b: BigDecimal): BigDecimal =
        if (b <= BigDecimal.ZERO) BigDecimal.ZERO
        else a.divide(b, mc).multiply(BigDecimal(100), mc).setScale(2, RoundingMode.HALF_UP)
}
