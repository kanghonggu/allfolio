package com.allfolio.marketdata.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "binance")
data class BinanceWsProperties(
    val wsEnabled: Boolean = false,
    val baseUrl: String = "wss://stream.binance.com:9443",
    val symbols: String = "",
) {
    fun symbolList(): List<String> =
        symbols.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
}
