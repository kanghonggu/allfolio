package com.allfolio.marketdata.adapter

import com.allfolio.marketdata.metrics.MarketMetrics
import com.allfolio.marketdata.properties.BinanceWsProperties
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Binance 실시간 체결가 WebSocket Adapter
 *
 * 연결: wss://stream.binance.com:9443/stream?streams=btcusdt@trade/ethusdt@trade/...
 * 수신: trade 이벤트 → InternalPriceEvent publish
 *   → MarketPriceKafkaProducer (Kafka market.prices)
 *   → MarketPriceBatchWriter (DB tick 저장)
 *
 * 활성화: binance.ws-enabled=true
 */
@Component
@ConditionalOnProperty(name = ["binance.ws-enabled"], havingValue = "true", matchIfMissing = false)
class BinanceWsAdapter(
    private val properties: BinanceWsProperties,
    private val eventPublisher: ApplicationEventPublisher,
    private val metrics: MarketMetrics,
    private val objectMapper: ObjectMapper,
) : MarketDataAdapter {

    override val exchange = "BINANCE"

    private val log       = LoggerFactory.getLogger(javaClass)
    private val connected = AtomicBoolean(false)
    private val wsRef     = AtomicReference<WebSocket?>(null)

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @PostConstruct
    override fun connect() {
        val symbols = properties.symbolList()
        if (symbols.isEmpty()) { log.info("[BinanceWs] no symbols configured"); return }
        subscribe(symbols)
    }

    @PreDestroy
    override fun disconnect() {
        wsRef.get()?.close(1000, "shutdown")
        connected.set(false)
        log.info("[BinanceWs] disconnected")
    }

    override fun subscribe(symbols: List<String>) {
        if (symbols.isEmpty()) return
        val streams = symbols.joinToString("/") { "${it.lowercase()}@trade" }
        val wsBase  = if (properties.baseUrl.contains("testnet"))
            "wss://testnet.binance.vision/stream" else "wss://stream.binance.com:9443/stream"
        val request = Request.Builder().url("$wsBase?streams=$streams").build()

        log.info("[BinanceWs] connecting symbols={}", symbols)

        client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected.set(true); wsRef.set(webSocket)
                log.info("[BinanceWs] connected streams={}", streams)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { handleMessage(text) }
                    .onFailure { e -> log.warn("[BinanceWs] parse error: {}", e.message) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected.set(false); wsRef.set(null)
                log.error("[BinanceWs] failure: {} — will reconnect", t.message)
                metrics.wsError(exchange)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected.set(false); wsRef.set(null)
                log.info("[BinanceWs] closed code={}", code)
            }
        }).also { wsRef.set(it) }
    }

    @Scheduled(fixedDelay = 30_000)
    fun healthCheck() {
        if (!connected.get()) { log.warn("[BinanceWs] reconnecting..."); connect() }
    }

    override fun isConnected(): Boolean = connected.get()

    private fun handleMessage(text: String) {
        val wrapper = objectMapper.readValue(text, WsStreamWrapper::class.java)
        val data    = wrapper.data ?: return
        if (data.eventType != "trade") return

        val assetId = AssetIdResolver.resolveBinance(data.symbol)
        eventPublisher.publishEvent(
            InternalPriceEvent(
                exchange  = exchange,
                symbol    = data.symbol,
                assetId   = assetId,
                price     = BigDecimal(data.price),
                volume    = BigDecimal(data.quantity),
                timestamp = data.tradeTime,
            )
        )
        metrics.priceReceived(exchange, data.symbol)
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class WsStreamWrapper(
        val stream: String? = null,
        val data: WsTradeData? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class WsTradeData(
        @JsonProperty("e") val eventType: String = "",
        @JsonProperty("s") val symbol: String = "",
        @JsonProperty("p") val price: String = "0",
        @JsonProperty("q") val quantity: String = "0",
        @JsonProperty("T") val tradeTime: Long = 0L,
    )
}
