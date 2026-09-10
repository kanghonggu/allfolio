package com.allfolio.market.realestate

import com.allfolio.common.metrics.PortalConsumer
import com.allfolio.metrics.MicrometerPortalCallMetrics
import com.allfolio.test.dedicatedConnector
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.time.Duration
import java.time.YearMonth
import java.util.concurrent.atomic.AtomicReference

/**
 * 실거래가 클라이언트.
 *
 * 인증키가 **쿼리 파라미터**(`serviceKey=`)에 실리므로 `FscCommodityClientTest`와 같은
 * 그물을 친다 — 예외 어디에도 키가 없어야 하고 요청 경로 조각도 없어야 한다.
 *
 * 요청 형식 단언은 **값으로 파싱해 비교**한다. 쿼리 문자열을 통째로 문자열 비교하면
 * 파라미터 순서가 바뀌는 것만으로 깨진다.
 */
class RtmsClientTest {

    private companion object {
        const val API_KEY = "SUPERSECRETRTMSKEY9876"
        const val SGG = "11110"
        val MONTH: YearMonth = YearMonth.of(2026, 7)

        /** 2026-08-21 실제 응답의 첫 행 */
        val REAL_BODY = """
            {"response":{"header":{"resultCode":"000","resultMsg":"OK"},"body":{"items":{"item":[
            {"aptDong":" ","aptNm":"동문(482-0)","aptSeq":"11110-132","buildYear":2002,
             "cdealDay":" ","cdealType":" ","dealAmount":"55,000","dealDay":23,"dealMonth":7,
             "dealYear":2026,"excluUseAr":60,"floor":5,"sggCd":11110,"umdNm":"숭인동"}
            ]},"numOfRows":200,"pageNo":1,"totalCount":37}}}
        """.trimIndent()
    }

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

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun serving(body: String): Int = serve { respond(it, 200, body) }

    /** 계측은 진짜 레지스트리로 본다(AF-210) — 근거는 `FscCommodityClientTest`의 같은 자리 */
    private val registry = SimpleMeterRegistry()

    private fun portalCalls(result: String): Double =
        registry.find(MicrometerPortalCallMetrics.CALL_COUNT)
            .tag("consumer", PortalConsumer.RTMS.tag).tag("result", result)
            .counter()?.count() ?: 0.0

    private fun client(port: Int, key: String = API_KEY) = RtmsClient(
        apiKey = key,
        baseUrl = "http://localhost:$port",
        objectMapper = ObjectMapper(),
        portalMetrics = MicrometerPortalCallMetrics(registry),
    ).apply { connector = dedicatedConnector() }

    private fun deadPort(): Int = ServerSocket(0).use { it.localPort }

    private fun queryOf(raw: String): Map<String, String> =
        raw.split("&").filter { it.isNotBlank() }.associate {
            val (k, v) = it.split("=", limit = 2)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

    private fun assertNoSecretAnywhere(t: Throwable) {
        val dump = t.stackTraceToString()
        assertThat(dump).doesNotContain(API_KEY)
        assertThat(dump).doesNotContain("RTMSDataSvcAptTradeDev")
    }

    // ── 요청 형식 ──────────────────────────────────────────────────────────

    /**
     * **`DEAL_YMD`는 `yyyyMM` 6자리다.** `YearMonth.toString()`은 `2026-07`이라
     * 그대로 넘기면 조용히 0건이 온다 — 오류가 아니라 빈 결과라서 안 보인다.
     */
    @Test
    fun `년월을 yyyyMM 여섯 자리로 보낸다`() {
        val seen = AtomicReference<Map<String, String>>()
        val port = serve { ex ->
            seen.set(queryOf(ex.requestURI.rawQuery ?: ""))
            respond(ex, 200, REAL_BODY)
        }

        client(port).fetchDeals(SGG, MONTH)

        assertThat(seen.get()["DEAL_YMD"]).isEqualTo("202607")
        assertThat(seen.get()["LAWD_CD"]).isEqualTo("11110")
    }

    /** 한 자리 월도 0을 채운다 — `20261`이 되면 안 된다 */
    @Test
    fun `한 자리 월에 0을 채운다`() {
        val seen = AtomicReference<Map<String, String>>()
        val port = serve { ex ->
            seen.set(queryOf(ex.requestURI.rawQuery ?: ""))
            respond(ex, 200, REAL_BODY)
        }

        client(port).fetchDeals(SGG, YearMonth.of(2026, 1))

        assertThat(seen.get()["DEAL_YMD"]).isEqualTo("202601")
    }

    /**
     * **`_type`이지 `resultType`이 아니다.** 금시세(FSC)와 파라미터 이름이 다르다 —
     * 틀리면 XML이 와서 파서가 통째로 깨진다.
     */
    @Test
    fun `json 응답을 _type으로 요청한다`() {
        val seen = AtomicReference<Map<String, String>>()
        val port = serve { ex ->
            seen.set(queryOf(ex.requestURI.rawQuery ?: ""))
            respond(ex, 200, REAL_BODY)
        }

        client(port).fetchDeals(SGG, MONTH)

        assertThat(seen.get()["_type"]).isEqualTo("json")
        assertThat(seen.get()).doesNotContainKey("resultType")
    }

    @Test
    fun `페이지와 페이지 크기를 보낸다`() {
        val seen = AtomicReference<Map<String, String>>()
        val port = serve { ex ->
            seen.set(queryOf(ex.requestURI.rawQuery ?: ""))
            respond(ex, 200, REAL_BODY)
        }

        client(port).fetchDeals(SGG, MONTH, page = 3)

        assertThat(seen.get()["pageNo"]).isEqualTo("3")
        assertThat(seen.get()["numOfRows"]).isEqualTo(RtmsClient.PAGE_SIZE.toString())
    }

    // ── 응답 ──────────────────────────────────────────────────────────────

    @Test
    fun `실측 응답을 읽는다`() {
        val fetch = client(serving(REAL_BODY)).fetchDeals(SGG, MONTH)

        val d = fetch.deals.single()
        assertThat(d.aptSeq).isEqualTo("11110-132")
        assertThat(d.dealAmountKrw).isEqualTo(550_000_000L)
        assertThat(fetch.totalCount).isEqualTo(37)
    }

    /** 실측: 분당 2026-07이 450건이라 200씩 3페이지였다 */
    @Test
    fun `총건수로 다음 페이지 필요를 판단한다`() {
        val c = client(serving(REAL_BODY))
        val big = RtmsFetch(emptyList(), 0, totalCount = 450)

        assertThat(c.hasMore(big, page = 1)).isTrue()
        assertThat(c.hasMore(big, page = 2)).isTrue()
        assertThat(c.hasMore(big, page = 3)).isFalse()

        val small = RtmsFetch(emptyList(), 0, totalCount = 37)
        assertThat(c.hasMore(small, page = 1)).isFalse()
    }

    // ── 🔴 인증키가 새지 않는다 ────────────────────────────────────────────

    /**
     * 설정 누락은 상류 장애가 아니라 우리 문제다 — 사유가 남아야 운영자가 환경변수를 본다.
     * 조용히 빈 목록을 주면 "그 달은 거래가 없었다"로 굳는다.
     */
    @Test
    fun `키가 없으면 예외로 알린다`() {
        val t = catchThrowable { client(serving(REAL_BODY), key = "").fetchDeals(SGG, MONTH) }

        assertThat(t).isInstanceOf(RtmsApiException::class.java)
        assertThat(t).hasMessageContaining("FSC_API_KEY")
    }

    /** 연결 실패의 cause 체인에 요청 URI(=키)가 들어 있다 — 붙이지 않는다 */
    @Test
    fun `연결 실패해도 키가 새지 않는다`() {
        val c = client(deadPort()).apply { timeout = Duration.ofSeconds(2) }

        val t = catchThrowable { c.fetchDeals(SGG, MONTH) }

        assertThat(t).isInstanceOf(RtmsApiException::class.java)
        assertNoSecretAnywhere(t!!)
    }

    /** 오류 페이지가 요청 URI를 되울린다 — 본문 미리보기를 남기지 않는다 */
    @Test
    fun `서버 오류 본문을 예외에 싣지 않는다`() {
        val port = serve { respond(it, 500, "<html>요청 URI: /path?serviceKey=$API_KEY</html>") }

        val t = catchThrowable { client(port).fetchDeals(SGG, MONTH) }

        assertThat(t).isInstanceOf(RtmsApiException::class.java)
        assertNoSecretAnywhere(t!!)
    }

    /** 미승인 키의 실제 응답 형태 */
    @Test
    fun `미승인 키는 헤더 오류로 온다`() {
        val port = serving(
            """{"response":{"header":{"resultCode":"30",
               "resultMsg":"SERVICE_KEY_IS_NOT_REGISTERED_ERROR"}}}""",
        )

        val t = catchThrowable { client(port).fetchDeals(SGG, MONTH) }

        assertThat(t).isInstanceOf(RtmsApiException::class.java)
        assertThat(t).hasMessageContaining("SERVICE_KEY_IS_NOT_REGISTERED_ERROR")
        assertNoSecretAnywhere(t!!)
    }

    @Test
    fun `JSON이 아니면 사유를 밝히고 키를 안 싣는다`() {
        val port = serving("<OpenAPI_ServiceResponse>XML이 왔다</OpenAPI_ServiceResponse>")

        val t = catchThrowable { client(port).fetchDeals(SGG, MONTH) }

        assertThat(t).isInstanceOf(RtmsApiException::class.java)
        assertThat(t).hasMessageContaining("JSON이 아니다")
        assertNoSecretAnywhere(t!!)
    }

    // ── 포털 호출 계측 (AF-210) ───────────────────────────────────────────

    @Test
    fun `성공한 호출 하나가 성공 카운터를 1 올린다`() {
        client(serving(REAL_BODY)).fetchDeals(SGG, MONTH)

        assertThat(portalCalls("success")).isEqualTo(1.0)
        assertThat(portalCalls("failure")).isEqualTo(0.0)
    }

    /**
     * **페이징은 호출부가 돈다** — 그래서 한 조합이 몇 콜을 썼는지가 그대로 카운터에 쌓여야
     * 한다. 실측에서 분당 2026-07이 3콜이었다(450건 / 200행).
     */
    @Test
    fun `페이지마다 따로 센다`() {
        val port = serving(REAL_BODY)

        client(port).fetchDeals(SGG, MONTH, page = 1)
        client(port).fetchDeals(SGG, MONTH, page = 2)

        assertThat(portalCalls("success")).isEqualTo(2.0)
    }

    @Test
    fun `연결 실패도 실패 태그로 1 센다`() {
        catchThrowable { client(deadPort()).fetchDeals(SGG, MONTH) }

        assertThat(portalCalls("failure")).isEqualTo(1.0)
        assertThat(portalCalls("success")).isEqualTo(0.0)
    }

    /** 키가 없으면 호출이 아예 안 나간다 — 세면 한도 배분이 그만큼 부풀려진다 */
    @Test
    fun `키 미설정으로 못 나간 호출은 세지 않는다`() {
        catchThrowable { client(deadPort(), key = "").fetchDeals(SGG, MONTH) }

        assertThat(portalCalls("success")).isEqualTo(0.0)
        assertThat(portalCalls("failure")).isEqualTo(0.0)
    }
}
