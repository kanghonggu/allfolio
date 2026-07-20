package com.allfolio.unifiedasset.infrastructure.adapter

import com.allfolio.unifiedasset.application.port.BenchmarkHistoryClient
import com.allfolio.unifiedasset.domain.benchmark.BenchmarkType
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate

@Component
class YahooBenchmarkHistoryClient(
    private val yahoo: YahooFinanceClient,
) : BenchmarkHistoryClient {
    override fun dailyHistory(type: BenchmarkType, range: String): List<Pair<LocalDate, BigDecimal>> =
        yahoo.getDailyHistory(type.yahooTicker, range)
}
