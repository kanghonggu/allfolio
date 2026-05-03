package com.allfolio.marketdata.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "kis")
data class KisWsProperties(
    val appKey: String = "",
    val appSecret: String = "",
    val baseUrl: String = "https://openapi.koreainvestment.com:9443",
    val wsUrl: String = "wss://ops.koreainvestment.com:21000",
    val mock: Boolean = false,
    val wsEnabled: Boolean = false,
    val symbols: String = "",
) {
    fun isConfigured(): Boolean = appKey.isNotBlank() && appSecret.isNotBlank()

    fun symbolList(): List<String> =
        symbols.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    // 모의투자(mock=true)일 때 모의 approval_key 발급 URL
    fun approvalKeyUrl(): String =
        if (mock) "https://openapivts.koreainvestment.com:29443/oauth2/Approval"
        else "$baseUrl/oauth2/Approval"

    fun tokenUrl(): String =
        if (mock) "https://openapivts.koreainvestment.com:29443/oauth2/tokenP"
        else "$baseUrl/oauth2/tokenP"
}
