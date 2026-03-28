package com.allfolio.fx

import com.allfolio.external.crypto.BinanceProperties
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.time.Duration

/**
 * Binance REST API 기반 USDT/KRW 환율 클라이언트
 *
 * 조회 전략 (순서대로 fallback):
 *   1. USDTKRW 직접 조회 (거래소에 페어가 있는 경우)
 *   2. USDTUSD × USDKRW 조합 (USDTUSD 없으면 1.0 사용 — USDT ≈ 1 USD 페그 가정)
 *
 * 활성화 조건: fx.scheduler.enabled=true
 * baseUrl: binance.base-url (BinanceProperties 재사용)
 */
@Component
@ConditionalOnProperty(name = ["fx.scheduler.enabled"], havingValue = "true")
class BinanceFxApiClient(
    private val binanceProperties: BinanceProperties,
) : FxApiClient {

    private val log = LoggerFactory.getLogger(javaClass)

    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(binanceProperties.baseUrl)
            .codecs { it.defaultCodecs().maxInMemorySize(256 * 1024) }
            .build()
    }

    companion object {
        private val TIMEOUT = Duration.ofSeconds(5)
    }

    override fun getUsdtKrw(): BigDecimal {
        // 1차 시도: USDTKRW 직접
        runCatching { fetchPrice("USDTKRW") }
            .onSuccess { rate ->
                log.debug("[BinanceFx] USDTKRW direct={}", rate)
                return rate
            }

        // 2차 시도: USDTUSD × USDKRW 조합
        val usdtUsd = runCatching { fetchPrice("USDTUSD") }
            .getOrElse {
                log.debug("[BinanceFx] USDTUSD not available — using 1.0 (peg assumption)")
                BigDecimal.ONE
            }

        val usdKrw = fetchPrice("USDKRW")   // 실패하면 예외 전파 → 스케줄러 fallback
        val rate   = usdtUsd.multiply(usdKrw)

        log.debug("[BinanceFx] USDTUSD={} USDKRW={} → USDTKRW={}", usdtUsd, usdKrw, rate)
        return rate
    }

    private fun fetchPrice(symbol: String): BigDecimal {
        val response = webClient.get()
            .uri("/api/v3/ticker/price?symbol=$symbol")
            .retrieve()
            .bodyToMono(TickerResponse::class.java)
            .block(TIMEOUT)
            ?: throw RuntimeException("null response for symbol=$symbol")

        return response.price
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class TickerResponse(
    val symbol: String = "",
    val price: BigDecimal = BigDecimal.ZERO,
)
