package com.allfolio.market

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.TimeUnit

data class StockSearchResult(
    val symbol: String,
    val name: String,
    val exchange: String,
    val type: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class YahooQuote(
    val symbol: String = "",
    @JsonProperty("shortname")  val shortName: String? = null,
    @JsonProperty("longname")   val longName: String? = null,
    val exchange: String = "",
    val quoteType: String = "",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class YahooSearchResponse(val quotes: List<YahooQuote> = emptyList())

@RestController
@RequestMapping("/api/unified/stocks")
class StockSearchController(private val jdbc: JdbcTemplate) {

    private val log    = LoggerFactory.getLogger(javaClass)
    private val mapper = ObjectMapper()
    private val http   = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @GetMapping("/search")
    fun search(@RequestParam q: String): List<StockSearchResult> {
        if (q.isBlank()) return emptyList()

        // 1. KRX DB 검색 (한글/영문 종목명 + 종목코드 모두 지원)
        val krResults = searchKrStocks(q)
        if (krResults.isNotEmpty()) return krResults

        // 2. KRX DB miss → Yahoo Finance (해외 종목, 영문 쿼리 대응)
        return searchYahoo(q)
    }

    private fun searchKrStocks(q: String): List<StockSearchResult> = runCatching {
        jdbc.query(
            """SELECT symbol, name, market FROM kr_stocks
               WHERE name ILIKE ? OR symbol ILIKE ?
               ORDER BY
                 CASE WHEN name ILIKE ? THEN 0
                      WHEN name ILIKE ? THEN 1
                      ELSE 2 END,
                 name
               LIMIT 10""",
            { rs, _ ->
                StockSearchResult(
                    symbol   = rs.getString("symbol"),
                    name     = rs.getString("name"),
                    exchange = rs.getString("market"),
                    type     = "EQUITY",
                )
            },
            "%$q%", "%$q%", "$q%", "%$q%",
        )
    }.getOrElse { e ->
        log.warn("KR stock DB search failed: {}", e.message)
        emptyList()
    }

    private fun searchYahoo(q: String): List<StockSearchResult> = runCatching {
        val url = "https://query1.finance.yahoo.com/v1/finance/search" +
            "?q=${java.net.URLEncoder.encode(q, "UTF-8")}" +
            "&quotesCount=10&newsCount=0&enableFuzzyQuery=false&quotesQueryId=tss_match_phrase_query"

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .header("Accept", "application/json")
            .build()

        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return emptyList()
            val body = res.body?.string() ?: return emptyList()
            mapper.readValue(body, YahooSearchResponse::class.java).quotes
                .filter { it.symbol.isNotBlank() }
                .map { quote ->
                    val cleanSymbol = quote.symbol.removeSuffix(".KS").removeSuffix(".KQ")
                    val exchange = when {
                        quote.symbol.endsWith(".KS") -> "KOSPI"
                        quote.symbol.endsWith(".KQ") -> "KOSDAQ"
                        quote.exchange in listOf("NMS", "NGM", "NCM") -> "NASDAQ"
                        quote.exchange == "NYQ" -> "NYSE"
                        else -> quote.exchange
                    }
                    StockSearchResult(
                        symbol   = cleanSymbol,
                        name     = quote.longName ?: quote.shortName ?: quote.symbol,
                        exchange = exchange,
                        type     = quote.quoteType,
                    )
                }
        }
    }.getOrElse { e ->
        log.warn("Yahoo Finance search failed for query='{}': {}", q, e.message)
        emptyList()
    }
}
