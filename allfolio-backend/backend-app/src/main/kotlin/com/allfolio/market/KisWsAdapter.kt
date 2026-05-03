package com.allfolio.market

import com.allfolio.broker.kis.KisApiClient
import com.allfolio.broker.kis.KisProperties
import com.allfolio.broker.kis.KisTradeMapper
import com.allfolio.metrics.BrokerMetrics
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
 * 한국투자증권(KIS) 실시간 체결가 WebSocket Adapter
 *
 * 프로토콜:
 *   - 연결 URL: kis.ws-url (wss://ops.koreainvestment.com:21000 또는 31000(모의))
 *   - 구독 메시지: { header: { approval_key, ... }, body: { input: { tr_id: "H0STCNT0", tr_key: 종목코드 }}}
 *   - 수신 형식: "0|H0STCNT0|001|{종목코드}^{시간}^{현재가}^..."  (^구분자)
 *     또는 JSON (PINGPONG 포함)
 *
 * 활성화 조건: kis.ws-enabled=true AND kis.app-key 설정
 *
 * 설계:
 *   - BinanceWsAdapter와 동일한 구조 (PriceUpdateEvent publish)
 *   - connect() → approval_key 발급 → 종목 구독
 *   - 30초 healthCheck → 연결 끊김 시 자동 재연결
 */
@Component
@ConditionalOnProperty(name = ["kis.ws-enabled"], havingValue = "true", matchIfMissing = false)
class KisWsAdapter(
    private val kisProperties: KisProperties,
    private val kisApiClient: KisApiClient,
    private val eventPublisher: ApplicationEventPublisher,
    private val metrics: BrokerMetrics,
    private val objectMapper: ObjectMapper,
) : MarketDataAdapter {

    override val exchange = "KIS"

    private val log       = LoggerFactory.getLogger(javaClass)
    private val connected = AtomicBoolean(false)
    private val wsRef     = AtomicReference<WebSocket?>(null)
    private var approvalKey: String = ""

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @PostConstruct
    override fun connect() {
        if (!kisProperties.isConfigured()) {
            log.info("[KisWs] app-key not configured — WS disabled")
            return
        }
        val symbols = kisProperties.symbolList()
        if (symbols.isEmpty()) {
            log.info("[KisWs] no symbols configured — WS disabled")
            return
        }
        runCatching {
            approvalKey = kisApiClient.issueApprovalKey()
            subscribe(symbols)
        }.onFailure { e ->
            log.error("[KisWs] connect failed: {}", e.message)
        }
    }

    @PreDestroy
    override fun disconnect() {
        wsRef.get()?.close(1000, "shutdown")
        connected.set(false)
        log.info("[KisWs] disconnected")
    }

    override fun subscribe(symbols: List<String>) {
        if (symbols.isEmpty()) return

        val request = Request.Builder().url(kisProperties.wsUrl).build()
        log.info("[KisWs] connecting url={} symbols={}", kisProperties.wsUrl, symbols)

        val ws = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected.set(true)
                wsRef.set(webSocket)
                log.info("[KisWs] connected — subscribing {} symbols", symbols.size)
                symbols.forEach { symbol -> sendSubscribe(webSocket, symbol) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { handleMessage(text) }
                    .onFailure { e -> log.warn("[KisWs] message error: {}", e.message) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected.set(false)
                wsRef.set(null)
                log.error("[KisWs] failure: {} — will reconnect", t.message)
                metrics.apiError("KIS", "websocket")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected.set(false)
                wsRef.set(null)
                log.info("[KisWs] closed code={} reason={}", code, reason)
            }
        })
        wsRef.set(ws)
    }

    @Scheduled(fixedDelay = 30_000)
    fun healthCheck() {
        if (!connected.get()) {
            log.warn("[KisWs] disconnected — reconnecting...")
            connect()
        }
    }

    override fun isConnected(): Boolean = connected.get()

    // ── Internal ─────────────────────────────────────────────────────────────

    /**
     * KIS WebSocket 구독 메시지 전송
     * tr_id = "H0STCNT0" (실시간 체결가)
     */
    private fun sendSubscribe(ws: WebSocket, symbol: String) {
        val msg = mapOf(
            "header" to mapOf(
                "approval_key" to approvalKey,
                "custtype"     to "P",
                "tr_type"      to "1",
                "content-type" to "utf-8",
            ),
            "body" to mapOf(
                "input" to mapOf(
                    "tr_id"  to "H0STCNT0",
                    "tr_key" to symbol,
                )
            )
        )
        ws.send(objectMapper.writeValueAsString(msg))
        log.debug("[KisWs] subscribed symbol={}", symbol)
    }

    /**
     * 수신 메시지 파싱
     *
     * 파이프(|) 구분 형식: "0|H0STCNT0|001|{body}"
     *   - [0] = 암호화여부 (0=평문)
     *   - [1] = tr_id
     *   - [2] = 데이터건수
     *   - [3] = ^구분 body (종목코드^시간^현재가^...)
     * JSON 형식: PINGPONG 등 제어 메시지
     */
    private fun handleMessage(text: String) {
        // JSON 제어 메시지 처리 (PINGPONG)
        if (text.startsWith("{")) {
            val node = objectMapper.readTree(text)
            val trId = node.path("header").path("tr_id").asText("")
            if (trId == "PINGPONG") {
                wsRef.get()?.send(text)  // pong
            }
            return
        }

        val parts = text.split("|")
        if (parts.size < 4) return

        val trId  = parts[1]
        if (trId != "H0STCNT0") return   // 체결가 데이터만 처리

        val body  = parts[3].split("^")
        if (body.size < 3) return

        val symbol       = body[0]
        val tradingTime  = body[1]
        val currentPrice = body[2].toBigDecimalOrNull() ?: return

        val assetId = KisTradeMapper.assetId(symbol)
        val event = PriceUpdateEvent(
            exchange  = "KIS",
            symbol    = symbol,
            assetId   = assetId,
            price     = currentPrice,
            timestamp = System.currentTimeMillis(),
        )

        eventPublisher.publishEvent(event)
        metrics.priceUpdateReceived("KIS", symbol)

        log.debug("[KisWs] price {}={}", symbol, currentPrice)
    }
}
