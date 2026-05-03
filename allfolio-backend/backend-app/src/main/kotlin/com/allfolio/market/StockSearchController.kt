package com.allfolio.market

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
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
class StockSearchController {

    private val log    = LoggerFactory.getLogger(javaClass)
    private val mapper = ObjectMapper()
    private val http   = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @GetMapping("/search")
    fun search(@RequestParam q: String): List<StockSearchResult> {
        if (q.isBlank() || q.length < 1) return emptyList()
        return try {
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
                val parsed = mapper.readValue(body, YahooSearchResponse::class.java)
                parsed.quotes
                    .filter { it.symbol.isNotBlank() }
                    .map { quote ->
                        val cleanSymbol = quote.symbol
                            .removeSuffix(".KS")
                            .removeSuffix(".KQ")
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
        } catch (e: Exception) {
            log.warn("Stock search failed for query='$q': ${e.message}")
            emptyList()
        }
    }
}
