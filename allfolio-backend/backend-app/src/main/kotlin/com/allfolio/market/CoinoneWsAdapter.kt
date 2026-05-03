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
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Coinone 실시간 시세 WebSocket Adapter
 *
 * 프로토콜:
 *   URL: wss://stream.coinone.co.kr
 *   구독 (종목별): {"request_type":"SUBSCRIBE","channel":"TICKER","topic":{"quote_currency":"KRW","target_currency":"BTC"}}
 *   수신: {"response_type":"DATA","channel":"TICKER","data":{"quote_currency":"KRW","target_currency":"BTC","last":"67000000"}}
 *   심볼 정규화: BTC/KRW → BTCKRW
 */
@Component
@ConditionalOnProperty(name = ["coinone.ws-enabled"], havingValue = "true")
class CoinoneWsAdapter(
    private val properties: CoinoneWsProperties,
    private val eventPublisher: ApplicationEventPublisher,
    private val objectMapper: ObjectMapper,
) : MarketDataAdapter {

    override val exchange = "COINONE"

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
        if (symbols.isEmpty()) { log.info("[CoinoneWs] no symbols configured"); return }
        subscribe(symbols)
    }

    @PreDestroy
    override fun disconnect() {
        wsRef.get()?.close(1000, "shutdown")
        connected.set(false)
    }

    override fun subscribe(symbols: List<String>) {
        val request = Request.Builder().url("wss://stream.coinone.co.kr").build()
        log.info("[CoinoneWs] connecting symbols={}", symbols)

        client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected.set(true); wsRef.set(webSocket)
                symbols.forEach { symbol ->
                    val msg = objectMapper.writeValueAsString(
                        mapOf(
                            "request_type" to "SUBSCRIBE",
                            "channel"      to "TICKER",
                            "topic"        to mapOf("quote_currency" to "KRW", "target_currency" to symbol),
                        )
                    )
                    webSocket.send(msg)
                }
                log.info("[CoinoneWs] connected, subscribed symbols={}", symbols)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { handleMessage(text) }
                    .onFailure { e -> log.warn("[CoinoneWs] parse error: {}", e.message) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected.set(false); wsRef.set(null)
                log.error("[CoinoneWs] failure: {} — will reconnect", t.message)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected.set(false); wsRef.set(null)
            }
        })
    }

    @Scheduled(fixedDelay = 30_000)
    fun healthCheck() {
        if (!connected.get()) { log.warn("[CoinoneWs] reconnecting..."); connect() }
    }

    override fun isConnected(): Boolean = connected.get()

    private fun handleMessage(text: String) {
        val msg = objectMapper.readValue(text, CoinoneMessage::class.java)
        if (msg.responseType != "DATA" || msg.channel != "TICKER") return
        val data = msg.data ?: return

        val base   = data.targetCurrency.uppercase()
        val symbol = "${base}KRW"
        val price  = data.last.toBigDecimalOrNull() ?: return

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
    private data class CoinoneMessage(
        @JsonProperty("response_type") val responseType: String = "",
        val channel: String = "",
        val data: CoinoneTickerData? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class CoinoneTickerData(
        @JsonProperty("quote_currency")  val quoteCurrency: String = "",
        @JsonProperty("target_currency") val targetCurrency: String = "",
        val last: String = "0",
    )
}

@ConfigurationProperties("coinone")
data class CoinoneWsProperties(
    val wsEnabled: Boolean = false,
    val symbols: String = "BTC,ETH",
) {
    fun symbolList() = symbols.split(",").map(String::trim).filter(String::isNotEmpty)
}
