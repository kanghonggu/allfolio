package com.allfolio.market

import com.allfolio.external.crypto.BinanceProperties
import com.allfolio.external.crypto.BinanceTradeMapper
import com.allfolio.metrics.BrokerMetrics
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
 * Binance 실시간 시세 WebSocket Adapter
 *
 * 설계 원칙:
 * - OkHttp WebSocket: 내부 I/O 스레드에서 비동기 처리 (절대 blocking 없음)
 * - Combined stream: 단일 WebSocket으로 다수 symbol 구독
 * - @Scheduled(30s): 연결 상태 확인 + 자동 재연결
 * - ApplicationEventPublisher: PriceUpdateEvent → @Async EventListener 로 dispatch
 *
 * 성능 영향:
 * - Trade write path 완전 분리 (별도 OkHttp 스레드)
 * - onMessage 핸들러: JSON 파싱 + 이벤트 발행 (O(1), 수 μs)
 *
 * ConditionalOnProperty: binance.ws-enabled=true + binance.api-key 설정 시 활성화
 */
@Component
@ConditionalOnProperty(name = ["binance.ws-enabled"], havingValue = "true", matchIfMissing = false)
class BinanceWsAdapter(
    private val binanceProperties: BinanceProperties,
    private val eventPublisher: ApplicationEventPublisher,
    private val metrics: BrokerMetrics,
    private val objectMapper: ObjectMapper,
) : MarketDataAdapter {

    override val exchange = "BINANCE"

    private val log       = LoggerFactory.getLogger(javaClass)
    private val connected = AtomicBoolean(false)
    private val wsRef     = AtomicReference<WebSocket?>(null)

    // OkHttp Client: WebSocket용 (ping/pong 지원, 재연결 시 재사용)
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // WebSocket은 무한 대기
        .build()

    @PostConstruct
    override fun connect() {
        if (!binanceProperties.isConfigured()) {
            log.info("[BinanceWs] API key not configured — WS disabled")
            return
        }
        subscribe(binanceProperties.symbolList())
    }

    @PreDestroy
    override fun disconnect() {
        wsRef.get()?.close(1000, "shutdown")
        connected.set(false)
        log.info("[BinanceWs] disconnected")
    }

    override fun subscribe(symbols: List<String>) {
        if (symbols.isEmpty()) return

        val streams  = symbols.joinToString("/") { "${it.lowercase()}@trade" }
        val wsUrl    = buildWsUrl(streams)
        val request  = Request.Builder().url(wsUrl).build()

        log.info("[BinanceWs] connecting to {} symbols={}", wsUrl, symbols)

        val ws = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected.set(true)
                wsRef.set(webSocket)
                log.info("[BinanceWs] connected streams={}", streams)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { handleMessage(text) }
                    .onFailure { e -> log.warn("[BinanceWs] message parse error: {}", e.message) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected.set(false)
                wsRef.set(null)
                log.error("[BinanceWs] connection failure: {} — will reconnect on next health check", t.message)
                metrics.apiError("BINANCE", "websocket")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected.set(false)
                wsRef.set(null)
                log.info("[BinanceWs] closed code={} reason={}", code, reason)
            }
        })

        wsRef.set(ws)
    }

    /** 30초마다 연결 상태 확인 — 끊어지면 재연결 */
    @Scheduled(fixedDelay = 30_000)
    fun healthCheck() {
        if (!connected.get()) {
            log.warn("[BinanceWs] disconnected — reconnecting...")
            connect()
        }
    }

    override fun isConnected(): Boolean = connected.get()

    // ──────────────────────────────────────────────
    // Internal
    // ──────────────────────────────────────────────

    private fun handleMessage(text: String) {
        val wrapper = objectMapper.readValue(text, WsStreamWrapper::class.java)
        val data    = wrapper.data ?: return

        if (data.eventType != "trade") return

        val symbol  = data.symbol
        val price   = BigDecimal(data.price)
        val assetId = BinanceTradeMapper.assetId(symbol)

        val event = PriceUpdateEvent(
            exchange  = "BINANCE",
            symbol    = symbol,
            assetId   = assetId,
            price     = price,
            timestamp = data.tradeTime,
        )

        // ApplicationEventPublisher.publishEvent(): Spring ThreadPoolTaskExecutor에 dispatch
        // → @Async @EventListener(PnlCalculationService)가 별도 스레드에서 수신
        eventPublisher.publishEvent(event)
        metrics.priceUpdateReceived("BINANCE", symbol)

        log.debug("[BinanceWs] price {}={}", symbol, price)
    }

    /**
     * Binance WS URL 결정
     * testnet: wss://testnet.binance.vision/stream?streams=...
     * prod:    wss://stream.binance.com:9443/stream?streams=...
     */
    private fun buildWsUrl(streams: String): String {
        val wsBase = when {
            binanceProperties.baseUrl.contains("testnet") ->
                "wss://testnet.binance.vision/stream"
            else ->
                "wss://stream.binance.com:9443/stream"
        }
        return "$wsBase?streams=$streams"
    }

    // ── DTO ───────────────────────────────────────────────────────

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
