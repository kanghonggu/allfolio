package com.allfolio.market

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
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
 * Bithumb 실시간 시세 WebSocket Adapter
 *
 * 프로토콜:
 *   URL: wss://pubwss.bithumb.com/pub/ws
 *   구독: {"type":"ticker","symbols":["BTC_KRW","ETH_KRW"],"tickTypes":["24H"]}
 *   수신: {"type":"ticker","content":{"symbol":"BTC_KRW","closePrice":"67000000"}}
 *   심볼 정규화: BTC_KRW → BTCKRW
 */
@Component
@ConditionalOnProperty(name = ["bithumb.ws-enabled"], havingValue = "true")
class BithumbWsAdapter(
    private val properties: BithumbWsProperties,
    private val eventPublisher: ApplicationEventPublisher,
    private val objectMapper: ObjectMapper,
) : MarketDataAdapter {

    override val exchange = "BITHUMB"

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
        if (symbols.isEmpty()) { log.info("[BithumbWs] no symbols configured"); return }
        subscribe(symbols)
    }

    @PreDestroy
    override fun disconnect() {
        wsRef.get()?.close(1000, "shutdown")
        connected.set(false)
    }

    override fun subscribe(symbols: List<String>) {
        val request = Request.Builder().url("wss://pubwss.bithumb.com/pub/ws").build()
        log.info("[BithumbWs] connecting symbols={}", symbols)

        client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected.set(true); wsRef.set(webSocket)
                val msg = objectMapper.writeValueAsString(
                    mapOf("type" to "ticker", "symbols" to symbols, "tickTypes" to listOf("24H"))
                )
                webSocket.send(msg)
                log.info("[BithumbWs] connected, subscribed symbols={}", symbols)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { handleMessage(text) }
                    .onFailure { e -> log.warn("[BithumbWs] parse error: {}", e.message) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected.set(false); wsRef.set(null)
                log.error("[BithumbWs] failure: {} — will reconnect", t.message)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected.set(false); wsRef.set(null)
            }
        })
    }

    @Scheduled(fixedDelay = 30_000)
    fun healthCheck() {
        if (!connected.get()) { log.warn("[BithumbWs] reconnecting..."); connect() }
    }

    override fun isConnected(): Boolean = connected.get()

    private fun handleMessage(text: String) {
        val wrapper = objectMapper.readValue(text, BithumbWrapper::class.java)
        if (wrapper.type != "ticker") return
        val content = wrapper.content ?: return

        // BTC_KRW → BTCKRW
        val symbol = content.symbol.replace("_", "")
        val price  = content.closePrice.toBigDecimalOrNull() ?: return
        val base   = content.symbol.substringBefore("_")

        eventPublisher.publishEvent(
            PriceUpdateEvent(
                exchange  = exchange,
                symbol    = symbol,
                assetId   = UUID.nameUUIDFromBytes("asset:$base".toByteArray()),
                price     = price,
                timestamp = System.currentTimeMillis(),
            )
        )
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class BithumbWrapper(
        val type: String = "",
        val content: BithumbContent? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class BithumbContent(
        val symbol: String = "",
        val closePrice: String = "0",
    )
}

@ConfigurationProperties("bithumb")
data class BithumbWsProperties(
    val wsEnabled: Boolean = false,
    val symbols: String = "BTC_KRW,ETH_KRW",
) {
    fun symbolList() = symbols.split(",").map(String::trim).filter(String::isNotEmpty)
}
