package com.allfolio.unifiedasset.infrastructure.adapter

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.time.Duration

@Component
class YahooFinanceClient {

    private val log = LoggerFactory.getLogger(javaClass)

    private val client = WebClient.builder()
        .defaultHeader("User-Agent", "Mozilla/5.0")
        .build()

    /**
     * 종목 현재가 조회
     * symbol 예: "005930" → Yahoo Finance ticker "005930.KS" (KOSPI)
     *            "035720" → "035720.KQ" (KOSDAQ)
     */
    fun getPrice(symbol: String): BigDecimal? {
        val s = symbol.trim()
        // 6자리 숫자: KOSPI(.KS) 먼저 시도, null이면 KOSDAQ(.KQ) 재시도
        return if (s.matches(Regex("\\d{6}"))) {
            fetchPrice("$s.KS") ?: fetchPrice("$s.KQ")
        } else {
            fetchPrice(s)
        }
    }

    private fun fetchPrice(ticker: String): BigDecimal? =
        runCatching {
            val url = "https://query1.finance.yahoo.com/v8/finance/chart/$ticker?interval=1d&range=1d"
            val resp = client.get().uri(url)
                .retrieve()
                .bodyToMono(YahooChartResponse::class.java)
                .block(Duration.ofSeconds(5))

            val price = resp?.chart?.result?.firstOrNull()?.meta?.regularMarketPrice
            if (price != null) {
                log.debug("[Yahoo] {} price={}", ticker, price)
                BigDecimal(price.toString())
            } else null
        }.onFailure { e ->
            log.warn("[Yahoo] price lookup failed for {}: {}", ticker, e.message)
        }.getOrNull()

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class YahooChartResponse(val chart: Chart? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Chart(val result: List<ChartResult>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ChartResult(val meta: Meta? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Meta(val regularMarketPrice: Double? = null)
}
