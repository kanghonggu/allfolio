package com.allfolio.external.crypto

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Binance REST API Client
 *
 * 인증: HMAC SHA256 서명 (API Key + Secret)
 * - X-MBX-APIKEY 헤더
 * - signature query param = HMAC_SHA256(secretKey, queryString)
 *
 * Anti-Corruption Layer:
 * - Binance 응답을 BinanceTradeDto로만 노출
 * - 도메인 타입 절대 반환 금지
 */
@Component
class BinanceApiClient(
    private val properties: BinanceProperties,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * GET /api/v3/myTrades
     *
     * @param symbol  거래 심볼 (예: BTCUSDT)
     * @param fromId  이 ID 이후의 거래만 조회 (0 = 최근 limit건)
     * @param limit   최대 조회 건수 (Binance 최대 1000)
     */
    fun fetchMyTrades(
        symbol: String,
        fromId: Long = 0L,
        limit: Int = 100,
    ): List<BinanceTradeDto> {
        val timestamp = System.currentTimeMillis()

        val query = buildString {
            append("symbol=$symbol")
            if (fromId > 0) append("&fromId=$fromId")
            append("&limit=$limit")
            append("&recvWindow=5000")
            append("&timestamp=$timestamp")
        }

        val signature = sign(query)
        val url = "${properties.baseUrl}/api/v3/myTrades?$query&signature=$signature"

        log.debug("[Binance] GET myTrades symbol={} fromId={}", symbol, fromId)

        val request = Request.Builder()
            .url(url)
            .header("X-MBX-APIKEY", properties.apiKey)
            .build()

        val response = http.newCall(request).execute()
        val body = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            throw BinanceApiException("Binance API error ${response.code}: $body")
        }

        log.debug("[Binance] response: {}", body.take(200))

        return objectMapper.readValue(
            body,
            objectMapper.typeFactory.constructCollectionType(List::class.java, BinanceTradeDto::class.java),
        )
    }

    // ──────────────────────────────────────────────
    // HMAC SHA256 서명
    // ──────────────────────────────────────────────

    private fun sign(queryString: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(properties.secretKey.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(queryString.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

class BinanceApiException(message: String) : RuntimeException(message)
