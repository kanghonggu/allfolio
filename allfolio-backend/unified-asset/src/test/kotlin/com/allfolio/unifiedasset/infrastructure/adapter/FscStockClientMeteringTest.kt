package com.allfolio.unifiedasset.infrastructure.adapter

import com.allfolio.common.metrics.PortalCallMetrics
import com.allfolio.common.metrics.PortalConsumer
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 포털 호출 계측 — 주식 경로(AF-210).
 *
 * ## 왜 이 파일이 따로 있나
 *
 * `FscStockClient`는 **한 클래스에 오퍼레이션이 셋**이고(주식시세 · 증권상품시세 · 상장종목
 * 목록), AF-203이 묻는 것은 "그중 무엇이 한도를 먹었나"다. 클래스 단위로 뭉쳐 세면 그 질문에
 * 답할 수 없다 — 특히 **폴백의 폴백**이 몇 번 도는지가 핵심이다:
 * `StockSyncAdapter`는 Yahoo가 실패한 날에만 `getPrice`를 부르고, 그게 값을 못 주면
 * `getEtfPrice`를 한 번 더 부른다. 즉 **KR 종목 하나가 포털을 최대 두 번 친다.**
 *
 * ## 왜 SimpleMeterRegistry가 아니라 기록 대역인가
 *
 * `unified-asset`의 컴파일 클래스패스에 Micrometer가 없다 — 계측 하나 붙이자고 모듈 의존성을
 * 새로 들이지 않기로 했고(Tier 2-1), 그래서 `common`의 포트만 여기서 문다.
 * **포트 → 실제 카운터**의 연결은 `backend-app`의 `MicrometerPortalCallMetricsTest`가 잇는다.
 * 둘을 합쳐야 "카운터가 오른다"가 증명된다 — 한쪽만으로는 아니다.
 */
class FscStockClientMeteringTest {

    private companion object {
        const val API_KEY = "SUPERSECRETFSCKEY1234"
        const val STOCK = "005930"
        const val ETF = "395270"

        val KST: ZoneId = ZoneId.of("Asia/Seoul")

        /** 주식시세정보 정상 응답(형식은 `FscEtfPriceTest`의 실측 봉투와 같다) */
        val STOCK_BODY = """
            {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},
            "body":{"numOfRows":1,"pageNo":1,"totalCount":1627,"items":{"item":[
            {"basDt":"20260820","srtnCd":"005930","itmsNm":"삼성전자","clpr":"71500",
            "vs":"500","fltRt":".70","mkp":"71000","hipr":"71800","lopr":"70900","trqu":"12345678"}
            ]}}}}
        """.trimIndent()

        /** ETF(증권상품시세정보) 정상 응답 — 신선도 가드를 통과하도록 시계를 고정해 쓴다 */
        val ETF_BODY = """
            {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},
            "body":{"numOfRows":1,"pageNo":1,"totalCount":1236,"items":{"item":[
            {"basDt":"20260820","srtnCd":"395270","isinCd":"KR7395270002","itmsNm":"HANARO Fn K-반도체",
            "clpr":"56905"}
            ]}}}}
        """.trimIndent()

        /** 상장종목 목록(`getItemInfo`) — 한 페이지가 3000행에 못 미치므로 호출은 한 번이다 */
        val LIST_BODY = """
            {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},
            "body":{"numOfRows":3000,"pageNo":1,"totalCount":1,"items":{"item":[
            {"srtnCd":"005930","isinCd":"KR7005930003","itmsNm":"삼성전자","corpNm":"삼성전자","mrktCtg":"KOSPI"}
            ]}}}}
        """.trimIndent()

        /**
         * 미승인·쿼터초과가 오는 봉투. **HTTP 200이다** — 전송 성공을 성공으로 세면
         * 한도 초과를 진단하려고 만든 계측이 정작 한도 초과를 성공으로 집계한다.
         */
        val NOT_REGISTERED = """
            {"OpenAPI_ServiceResponse":{"cmmMsgHeader":{
            "errMsg":"SERVICE_KEY_IS_NOT_REGISTERED_ERROR",
            "returnAuthMsg":"등록되지 않은 서비스키","returnReasonCode":"30"}}}
        """.trimIndent()

        /** 0건 — 오류가 아니라 정상 응답이다(ETF 코드를 주식시세에 물으면 이게 온다) */
        val EMPTY_BODY = """
            {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},
            "body":{"numOfRows":1,"pageNo":1,"totalCount":0,"items":""}}}
        """.trimIndent()
    }

    /** 소비자별 성공/실패를 세는 기록 대역. `MicrometerPortalCallMetrics`가 하는 일의 최소형 */
    private class RecordingMetrics : PortalCallMetrics {
        private val counts = ConcurrentHashMap<Pair<PortalConsumer, String>, AtomicInteger>()

        override fun callSucceeded(consumer: PortalConsumer) = bump(consumer, "success")
        override fun callFailed(consumer: PortalConsumer) = bump(consumer, "failure")

        private fun bump(consumer: PortalConsumer, result: String) {
            counts.computeIfAbsent(consumer to result) { AtomicInteger() }.incrementAndGet()
        }

        operator fun get(consumer: PortalConsumer, result: String): Int =
            counts[consumer to result]?.get() ?: 0

        /** 어느 소비자에도 아무것도 안 세졌는지 */
        fun isEmpty(): Boolean = counts.values.all { it.get() == 0 }
    }

    private val metrics = RecordingMetrics()
    private var server: HttpServer? = null

    @AfterEach
    fun tearDown() {
        server?.stop(0)
    }

    private fun serve(handler: (HttpExchange) -> Unit): Int {
        val s = HttpServer.create(InetSocketAddress(0), 0)
        s.createContext("/", handler)
        s.start()
        server = s
        return s.address.port
    }

    private fun serving(body: String): Int = serve { ex ->
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json;charset=UTF-8")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    /** 아무도 듣지 않는 포트. 여는 즉시 닫아 두므로 연결이 거부된다 */
    private fun deadPort(): Int = ServerSocket(0).use { it.localPort }

    private fun client(port: Int, key: String = API_KEY) =
        FscStockClient(key, ObjectMapper(), metrics).apply {
            baseUrl = "http://127.0.0.1:$port"
            // ETF 신선도 가드(14일)를 통과시키려면 픽스처 기준일자 근처로 시계를 고정해야 한다
            clock = Clock.fixed(Instant.parse("2026-08-21T02:42:00Z"), KST)
        }

    // ── getPrice (주식시세정보) ───────────────────────────────────────────

    @Test
    fun `주식 현재가 호출 하나가 성공 카운터를 1 올린다`() {
        client(serving(STOCK_BODY)).getPrice(STOCK)

        assertThat(metrics[PortalConsumer.STOCK_PRICE, "success"]).isEqualTo(1)
        assertThat(metrics[PortalConsumer.STOCK_PRICE, "failure"]).isEqualTo(0)
    }

    /**
     * **0건도 한도는 똑같이 먹는다.** ETF 코드를 주식시세에 물으면 오류가 아니라 이 응답이
     * 오는데(2026-08-21 실측), 그 호출을 안 세면 폴백이 두 번 도는 날의 사용량이 절반으로
     * 보인다 — 정확히 이 계측이 답해야 할 질문이 사라진다.
     */
    @Test
    fun `0건으로 돌아온 호출도 실패 태그로 센다`() {
        client(serving(EMPTY_BODY)).getPrice(STOCK)

        assertThat(metrics[PortalConsumer.STOCK_PRICE, "failure"]).isEqualTo(1)
        assertThat(metrics[PortalConsumer.STOCK_PRICE, "success"]).isEqualTo(0)
    }

    @Test
    fun `연결 실패도 실패 태그로 1 센다`() {
        client(deadPort()).getPrice(STOCK)

        assertThat(metrics[PortalConsumer.STOCK_PRICE, "failure"]).isEqualTo(1)
    }

    // ── getEtfPrice (증권상품시세정보) ────────────────────────────────────

    /**
     * **`getPrice`와 태그가 갈려야 한다.** 폴백의 폴백이 몇 번 도는지가 이 태스크의 핵심
     * 질문이고, 둘을 한 태그로 뭉치면 그 질문에 답할 수 없다.
     */
    @Test
    fun `ETF 현재가는 주식 현재가와 다른 태그로 센다`() {
        client(serving(ETF_BODY)).getEtfPrice(ETF)

        assertThat(metrics[PortalConsumer.STOCK_ETF_PRICE, "success"]).isEqualTo(1)
        assertThat(metrics[PortalConsumer.STOCK_PRICE, "success"]).isEqualTo(0)
    }

    /** 미승인 봉투로 빠지는 경로는 `return null`이다 — 계측이 가장 새기 쉬운 자리 */
    @Test
    fun `미승인 봉투로 돌아온 호출도 실패 태그로 센다`() {
        client(serving(NOT_REGISTERED)).getEtfPrice(ETF)

        assertThat(metrics[PortalConsumer.STOCK_ETF_PRICE, "failure"]).isEqualTo(1)
        assertThat(metrics[PortalConsumer.STOCK_ETF_PRICE, "success"]).isEqualTo(0)
    }

    /**
     * 실제 폴백 순서를 그대로 흉내 낸다 — 주식시세가 0건이면 증권상품시세를 한 번 더 친다.
     * **KR 종목 하나가 포털을 두 번 먹는 그 경로다.**
     */
    @Test
    fun `주식이 0건이라 ETF까지 가면 소비자별로 각각 1씩 쌓인다`() {
        val port = serve { ex ->
            val body = if (ex.requestURI.path.contains("getETFPriceInfo")) ETF_BODY else EMPTY_BODY
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        val c = client(port)

        val quote = c.getPrice(ETF) ?: c.getEtfPrice(ETF)

        assertThat(quote).isNotNull
        assertThat(metrics[PortalConsumer.STOCK_PRICE, "failure"]).isEqualTo(1)
        assertThat(metrics[PortalConsumer.STOCK_ETF_PRICE, "success"]).isEqualTo(1)
    }

    // ── listAllStocks (상장종목 목록) ─────────────────────────────────────

    @Test
    fun `상장종목 목록도 자기 태그로 센다`() {
        client(serving(LIST_BODY)).listAllStocks()

        assertThat(metrics[PortalConsumer.STOCK_LIST, "success"]).isEqualTo(1)
        assertThat(metrics[PortalConsumer.STOCK_LIST, "failure"]).isEqualTo(0)
    }

    // ── 안 나간 호출 ──────────────────────────────────────────────────────

    /**
     * **키가 없으면 호출이 아예 안 나간다** — 세면 안 된다. 안 나간 호출을 세면 한도 배분이
     * 그만큼 부풀려지고, "누가 먹었나"의 답이 조용히 틀어진다.
     */
    @Test
    fun `키 미설정으로 못 나간 호출은 어느 태그로도 세지 않는다`() {
        val c = client(deadPort(), key = "")

        c.getPrice(STOCK)
        c.getEtfPrice(ETF)
        c.listAllStocks()

        assertThat(metrics.isEmpty()).isTrue()
    }
}
