package com.allfolio.market

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
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Bybit 실시간 체결가 WebSocket Adapter
 *
 * 프로토콜:
 *   URL: wss://stream.bybit.com/v5/public/spot
 *   구독: {"op":"subscribe","args":["publicTrade.BTCUSDT","publicTrade.ETHUSDT"]}
 *   수신: {"topic":"publicTrade.BTCUSDT","data":[{"p":"67000.5","v":"0.001","T":1234567890}]}
 *   Ping: {"op":"ping"} → {"op":"pong"}
 *   심볼: BTCUSDT 그대로 사용
 */
@Component
@ConditionalOnProperty(name = ["bybit.ws-enabled"], havingValue = "true")
class BybitWsAdapter(
    private val properties: BybitWsProperties,
    private val eventPublisher: ApplicationEventPublisher,
    private val objectMapper: ObjectMapper,
) : MarketDataAdapter {

    override val exchange = "BYBIT"

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
        if (symbols.isEmpty()) { log.info("[BybitWs] no symbols configured"); return }
        subscribe(symbols)
    }

    @PreDestroy
    override fun disconnect() {
        wsRef.get()?.close(1000, "shutdown")
        connected.set(false)
    }

    override fun subscribe(symbols: List<String>) {
        val args    = symbols.map { "publicTrade.$it" }
        val request = Request.Builder().url("wss://stream.bybit.com/v5/public/spot").build()
        log.info("[BybitWs] connecting symbols={}", symbols)

        client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected.set(true); wsRef.set(webSocket)
                val msg = objectMapper.writeValueAsString(mapOf("op" to "subscribe", "args" to args))
                webSocket.send(msg)
                log.info("[BybitWs] connected, subscribed args={}", args)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { handleMessage(webSocket, text) }
                    .onFailure { e -> log.warn("[BybitWs] parse error: {}", e.message) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected.set(false); wsRef.set(null)
                log.error("[BybitWs] failure: {} — will reconnect", t.message)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected.set(false); wsRef.set(null)
            }
        })
    }

    @Scheduled(fixedDelay = 20_000)
    fun ping() {
        wsRef.get()?.send(objectMapper.writeValueAsString(mapOf("op" to "ping")))
    }

    @Scheduled(fixedDelay = 30_000)
    fun healthCheck() {
        if (!connected.get()) { log.warn("[BybitWs] reconnecting..."); connect() }
    }

    override fun isConnected(): Boolean = connected.get()

    private fun handleMessage(ws: WebSocket, text: String) {
        val msg = objectMapper.readValue(text, BybitMessage::class.java)

        if (msg.op == "pong") return

        val topic = msg.topic ?: return
        if (!topic.startsWith("publicTrade.")) return

        val symbol = topic.removePrefix("publicTrade.")
        val trade  = msg.data?.firstOrNull() ?: return
        val price  = trade.price.toBigDecimalOrNull() ?: return

        val base = extractBase(symbol)
        eventPublisher.publishEvent(
            PriceUpdateEvent(
                exchange  = exchange,
                symbol    = symbol,
                assetId   = UUID.nameUUIDFromBytes("asset:$base".toByteArray()),
                price     = price,
                timestamp = trade.timestamp,
            )
        )
    }

    private fun extractBase(symbol: String): String {
        val quotes = listOf("USDT", "USDC", "BTC", "ETH")
        return quotes.firstOrNull { symbol.endsWith(it) }
            ?.let { symbol.removeSuffix(it) } ?: symbol
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class BybitMessage(
        val op: String? = null,
        val topic: String? = null,
        val data: List<BybitTrade>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class BybitTrade(
        @JsonProperty("p") val price: String = "0",
        @JsonProperty("v") val volume: String = "0",
        @JsonProperty("T") val timestamp: Long = 0L,
    )
}

@ConfigurationProperties("bybit")
data class BybitWsProperties(
    val wsEnabled: Boolean = false,
    val symbols: String = "BTCUSDT,ETHUSDT",
) {
    fun symbolList() = symbols.split(",").map(String::trim).filter(String::isNotEmpty)
}
