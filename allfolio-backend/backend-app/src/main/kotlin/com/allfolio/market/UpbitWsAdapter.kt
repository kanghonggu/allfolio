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
import okio.ByteString
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
 * Upbit 실시간 체결가 WebSocket Adapter
 *
 * 프로토콜:
 *   URL: wss://api.upbit.com/websocket/v1
 *   구독: [{"ticket":"ALLFOLIO"},{"type":"trade","codes":["KRW-BTC","KRW-ETH"]}]
 *   수신: {"type":"trade","code":"KRW-BTC","trade_price":67000000.0,"trade_volume":0.001}
 *   심볼 정규화: KRW-BTC → symbol=BTCKRW
 */
@Component
@ConditionalOnProperty(name = ["upbit.ws-enabled"], havingValue = "true")
class UpbitWsAdapter(
    private val properties: UpbitWsProperties,
    private val eventPublisher: ApplicationEventPublisher,
    private val objectMapper: ObjectMapper,
) : MarketDataAdapter {

    override val exchange = "UPBIT"

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
        if (symbols.isEmpty()) { log.info("[UpbitWs] no symbols configured"); return }
        subscribe(symbols)
    }

    @PreDestroy
    override fun disconnect() {
        wsRef.get()?.close(1000, "shutdown")
        connected.set(false)
    }

    override fun subscribe(symbols: List<String>) {
        val request = Request.Builder().url("wss://api.upbit.com/websocket/v1").build()
        log.info("[UpbitWs] connecting symbols={}", symbols)

        client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected.set(true); wsRef.set(webSocket)
                val msg = objectMapper.writeValueAsString(
                    listOf(mapOf("ticket" to "ALLFOLIO"), mapOf("type" to "trade", "codes" to symbols))
                )
                webSocket.send(msg)
                log.info("[UpbitWs] connected, subscribed symbols={}", symbols)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { handleMessage(text) }
                    .onFailure { e -> log.warn("[UpbitWs] parse error: {}", e.message) }
            }

            // Upbit은 binary 프레임으로 전송
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                runCatching { handleMessage(bytes.utf8()) }
                    .onFailure { e -> log.warn("[UpbitWs] binary parse error: {}", e.message) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected.set(false); wsRef.set(null)
                log.error("[UpbitWs] failure: {} — will reconnect", t.message)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected.set(false); wsRef.set(null)
            }
        })
    }

    @Scheduled(fixedDelay = 30_000)
    fun healthCheck() {
        if (!connected.get()) { log.warn("[UpbitWs] reconnecting..."); connect() }
    }

    override fun isConnected(): Boolean = connected.get()

    private fun handleMessage(text: String) {
        val data = objectMapper.readValue(text, UpbitTradeData::class.java)
        if (data.type != "trade") return

        // KRW-BTC → BTCKRW
        val parts  = data.code.split("-")
        if (parts.size != 2) return
        val symbol = "${parts[1]}${parts[0]}"

        eventPublisher.publishEvent(
            PriceUpdateEvent(
                exchange  = exchange,
                symbol    = symbol,
                assetId   = UUID.nameUUIDFromBytes("asset:${parts[1]}".toByteArray()),
                price     = BigDecimal(data.tradePrice.toString()),
                timestamp = data.timestamp,
            )
        )
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class UpbitTradeData(
        val type: String = "",
        val code: String = "",
        @JsonProperty("trade_price")  val tradePrice: Double = 0.0,
        @JsonProperty("trade_volume") val tradeVolume: Double = 0.0,
        val timestamp: Long = 0L,
    )
}

@ConfigurationProperties("upbit")
data class UpbitWsProperties(
    val wsEnabled: Boolean = false,
    val symbols: String = "KRW-BTC,KRW-ETH",
) {
    fun symbolList() = symbols.split(",").map(String::trim).filter(String::isNotEmpty)
}
