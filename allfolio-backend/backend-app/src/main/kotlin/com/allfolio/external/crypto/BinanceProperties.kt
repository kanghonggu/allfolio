package com.allfolio.external.crypto

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue
import java.util.UUID

/**
 * Binance API 연동 설정
 *
 * 환경변수:
 *   BINANCE_API_KEY, BINANCE_API_SECRET
 *   BINANCE_TENANT_ID, BINANCE_PORTFOLIO_ID
 *
 * base-url 기본값은 실운영이다. 테스트넷을 쓰려면 BINANCE_API_BASE_URL 환경변수로 덮는다.
 * (BINANCE_BASE_URL은 market-data가 WS 주소에 이미 쓰고 있어 재사용하지 않는다.)
 */
@ConfigurationProperties(prefix = "binance")
data class BinanceProperties(
    val apiKey: String = "",
    val secretKey: String = "",
    // 기본값이 테스트넷이면 운영이 테스트넷 가격으로 자산을 평가한다.
    @DefaultValue("https://api.binance.com")
    val baseUrl: String,
    val tenantId: UUID,
    val portfolioId: UUID,
    @DefaultValue("BTCUSDT,ETHUSDT")
    val symbols: String,
) {
    fun symbolList(): List<String> = symbols.split(",").map { it.trim() }.filter { it.isNotBlank() }
    fun isConfigured(): Boolean = apiKey.isNotBlank() && secretKey.isNotBlank()
}
