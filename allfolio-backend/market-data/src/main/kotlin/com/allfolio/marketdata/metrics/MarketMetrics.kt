package com.allfolio.marketdata.metrics

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

@Component
class MarketMetrics(private val registry: MeterRegistry) {

    fun priceReceived(exchange: String, symbol: String) {
        registry.counter("market.price.received", "exchange", exchange, "symbol", symbol).increment()
    }

    fun kafkaSendSuccess(exchange: String) {
        registry.counter("market.kafka.send.success", "exchange", exchange).increment()
    }

    fun kafkaSendFailed(exchange: String) {
        registry.counter("market.kafka.send.failed", "exchange", exchange).increment()
    }

    fun wsError(exchange: String) {
        registry.counter("market.ws.error", "exchange", exchange).increment()
    }
}
