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
 * 테스트넷: https://testnet.binance.vision
 * 실운영:   https://api.binance.com
 */
@ConfigurationProperties(prefix = "binance")
data class BinanceProperties(
    val apiKey: String = "",
    val secretKey: String = "",
    @DefaultValue("https://testnet.binance.vision")
    val baseUrl: String,
    val tenantId: UUID,
    val portfolioId: UUID,
    @DefaultValue("BTCUSDT,ETHUSDT")
    val symbols: String,
) {
    fun symbolList(): List<String> = symbols.split(",").map { it.trim() }.filter { it.isNotBlank() }
    fun isConfigured(): Boolean = apiKey.isNotBlank() && secretKey.isNotBlank()
}
