package com.allfolio.marketdata.adapter

import com.allfolio.marketdata.metrics.MarketMetrics
import com.allfolio.marketdata.properties.KisWsProperties
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
 * 연결: kis.ws-url (wss://ops.koreainvestment.com:21000)
 * 구독: H0STCNT0 (실시간 체결가)
 * 수신: "0|H0STCNT0|001|종목코드^시간^현재가^..." → InternalPriceEvent publish
 *
 * 활성화: kis.ws-enabled=true AND kis.app-key 설정
 */
@Component
@ConditionalOnProperty(name = ["kis.ws-enabled"], havingValue = "true", matchIfMissing = false)
class KisWsAdapter(
    private val properties: KisWsProperties,
    private val eventPublisher: ApplicationEventPublisher,
    private val metrics: MarketMetrics,
    private val objectMapper: ObjectMapper,
) : MarketDataAdapter {

    override val exchange = "KIS"

    private val log        = LoggerFactory.getLogger(javaClass)
    private val connected  = AtomicBoolean(false)
    private val wsRef      = AtomicReference<WebSocket?>(null)
    private var approvalKey: String = ""

    private val httpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @PostConstruct
    override fun connect() {
        if (!properties.isConfigured()) {
            log.info("[KisWs] app-key not configured — skipping")
            return
        }
        val symbols = properties.symbolList()
        if (symbols.isEmpty()) {
            log.info("[KisWs] no symbols configured — skipping")
            return
        }
        runCatching {
            approvalKey = issueApprovalKey()
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
        val request = Request.Builder().url(properties.wsUrl).build()
        log.info("[KisWs] connecting url={} symbols={}", properties.wsUrl, symbols)

        httpClient.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected.set(true); wsRef.set(webSocket)
                log.info("[KisWs] connected — subscribing {} symbols", symbols.size)
                symbols.forEach { sendSubscribe(webSocket, it) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { handleMessage(text) }
                    .onFailure { e -> log.warn("[KisWs] parse error: {}", e.message) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected.set(false); wsRef.set(null)
                log.error("[KisWs] failure: {} — will reconnect", t.message)
                metrics.wsError(exchange)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected.set(false); wsRef.set(null)
                log.info("[KisWs] closed code={} reason={}", code, reason)
            }
        }).also { wsRef.set(it) }
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

    private fun issueApprovalKey(): String {
        val body = mapOf(
            "grant_type" to "client_credentials",
            "appkey"     to properties.appKey,
            "secretkey"  to properties.appSecret,
        )
        val req = Request.Builder()
            .url(properties.approvalKeyUrl())
            .post(objectMapper.writeValueAsString(body).toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val json  = resp.body?.string() ?: error("empty body")
            val node  = objectMapper.readTree(json)
            return node.path("approval_key").asText()
                .also { log.info("[KisWs] approval_key issued") }
        }
    }

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
     * 파이프(|) 구분 형식: "0|H0STCNT0|001|종목코드^시간^현재가^..."
     * JSON 형식: PINGPONG 등 제어 메시지
     */
    private fun handleMessage(text: String) {
        if (text.startsWith("{")) {
            val node = objectMapper.readTree(text)
            if (node.path("header").path("tr_id").asText("") == "PINGPONG") {
                wsRef.get()?.send(text)
            }
            return
        }

        val parts = text.split("|")
        if (parts.size < 4) return
        if (parts[1] != "H0STCNT0") return

        val body  = parts[3].split("^")
        if (body.size < 3) return

        val symbol = body[0]
        val price  = body[2].toBigDecimalOrNull() ?: return

        val assetId = AssetIdResolver.resolve("KIS", symbol)
        eventPublisher.publishEvent(
            InternalPriceEvent(
                exchange  = exchange,
                symbol    = symbol,
                assetId   = assetId,
                price     = price,
                volume    = BigDecimal.ZERO,
                timestamp = System.currentTimeMillis(),
            )
        )
        metrics.priceReceived(exchange, symbol)
        log.debug("[KisWs] price {}={}", symbol, price)
    }
}
