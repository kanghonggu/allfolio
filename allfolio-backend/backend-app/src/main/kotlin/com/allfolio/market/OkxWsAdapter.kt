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
 * OKX 실시간 체결가 WebSocket Adapter
 *
 * 프로토콜:
 *   URL: wss://ws.okx.com:8443/ws/v5/public
 *   구독: {"op":"subscribe","args":[{"channel":"trades","instId":"BTC-USDT"}]}
 *   수신: {"arg":{"channel":"trades","instId":"BTC-USDT"},"data":[{"px":"67000.5","sz":"0.001","ts":"1234567890"}]}
 *   Ping: 문자열 "ping" → "pong"
 *   심볼 정규화: BTC-USDT → BTCUSDT
 */
@Component
@ConditionalOnProperty(name = ["okx.ws-enabled"], havingValue = "true")
class OkxWsAdapter(
    private val properties: OkxWsProperties,
    private val eventPublisher: ApplicationEventPublisher,
    private val objectMapper: ObjectMapper,
) : MarketDataAdapter {

    override val exchange = "OKX"

    private val log       = LoggerFactory.getLogger(javaClass)
    private val connected = AtomicBoolean(false)
    private val wsRef     = AtomicReference<WebSocket?>(null)

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @PostConstruct
    override fun connect() {
        val symbols = properties.symbolList()
        if (symbols.isEmpty()) { log.info("[OkxWs] no symbols configured"); return }
        subscribe(symbols)
    }

    @PreDestroy
    override fun disconnect() {
        wsRef.get()?.close(1000, "shutdown")
        connected.set(false)
    }

    override fun subscribe(symbols: List<String>) {
        val args    = symbols.map { mapOf("channel" to "trades", "instId" to it) }
        val request = Request.Builder().url("wss://ws.okx.com:8443/ws/v5/public").build()
        log.info("[OkxWs] connecting symbols={}", symbols)

        client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected.set(true); wsRef.set(webSocket)
                val msg = objectMapper.writeValueAsString(mapOf("op" to "subscribe", "args" to args))
                webSocket.send(msg)
                log.info("[OkxWs] connected, subscribed symbols={}", symbols)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text == "pong") return
                runCatching { handleMessage(text) }
                    .onFailure { e -> log.warn("[OkxWs] parse error: {}", e.message) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected.set(false); wsRef.set(null)
                log.error("[OkxWs] failure: {} — will reconnect", t.message)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected.set(false); wsRef.set(null)
            }
        })
    }

    @Scheduled(fixedDelay = 25_000)
    fun ping() {
        wsRef.get()?.send("ping")
    }

    @Scheduled(fixedDelay = 30_000)
    fun healthCheck() {
        if (!connected.get()) { log.warn("[OkxWs] reconnecting..."); connect() }
    }

    override fun isConnected(): Boolean = connected.get()

    private fun handleMessage(text: String) {
        val msg = objectMapper.readValue(text, OkxMessage::class.java)
        if (msg.arg?.channel != "trades") return

        val instId = msg.arg.instId ?: return
        val trade  = msg.data?.firstOrNull() ?: return
        val price  = trade.px.toBigDecimalOrNull() ?: return

        // BTC-USDT → BTCUSDT, base = BTC
        val symbol = instId.replace("-", "")
        val base   = instId.substringBefore("-")

        eventPublisher.publishEvent(
            PriceUpdateEvent(
                exchange  = exchange,
                symbol    = symbol,
                assetId   = UUID.nameUUIDFromBytes("asset:$base".toByteArray()),
                price     = price,
                timestamp = trade.ts.toLongOrNull() ?: System.currentTimeMillis(),
            )
        )
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class OkxMessage(
        val arg: OkxArg? = null,
        val data: List<OkxTrade>? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class OkxArg(
        val channel: String? = null,
        val instId: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class OkxTrade(
        val px: String = "0",
        val sz: String = "0",
        val ts: String = "0",
    )
}

@ConfigurationProperties("okx")
data class OkxWsProperties(
    val wsEnabled: Boolean = false,
    val symbols: String = "BTC-USDT,ETH-USDT",
) {
    fun symbolList() = symbols.split(",").map(String::trim).filter(String::isNotEmpty)
}
