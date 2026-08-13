package com.allfolio.market.index

import com.allfolio.broker.kis.KisApiClient
import com.allfolio.broker.kis.KisProperties
import com.allfolio.broker.kis.KisTokenResponse
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate

/**
 * KIS 해외 지수 원본 덤프 (AF-110).
 *
 * [KisIndexClientTest]와 같은 방식이다 — JDK 내장 [HttpServer]를 루프백에 띄우고 그쪽을
 * baseUrl로 주므로 `fetchOverseasRaw`가 실제 HTTP 경로를 그대로 탄다. 가짜 응답을 밀어 넣는
 * 방식으로는 **요청이 어떤 모양으로 나갔는지**를 볼 수 없는데, 이 기능에서 정작 위험한 곳이
 * 거기다: 심볼에 `.`과 `#`이 들어가서 인코딩이 틀리면 조용히 잘린 심볼이 나간다.
 *
 * 외부로 나가는 통신은 없다(127.0.0.1, 포트는 OS가 준다).
 */
class KisIndexOverseasRawTest {

    private lateinit var kis: FakeKisOverseas

    private val from: LocalDate = LocalDate.of(2026, 8, 1)
    private val to: LocalDate = LocalDate.of(2026, 8, 13)

    @BeforeEach
    fun startServer() {
        kis = FakeKisOverseas()
    }

    @AfterEach
    fun stopServer() {
        kis.stop()
    }

    @Test
    fun `해외 경로로 tr_id와 시장구분 N을 달고 나간다`() {
        val client = KisIndexClient(properties(), issuer("T1"))

        client.fetchOverseasRaw("SPX", from, to)

        assertThat(kis.paths).containsExactly("/uapi/overseas-price/v1/quotations/inquire-daily-chartprice")
        assertThat(kis.trIds).containsExactly("FHKST03030100")
        assertThat(kis.lastParams()).containsEntry("FID_COND_MRKT_DIV_CODE", "N")
        assertThat(kis.lastParams()).containsEntry("FID_PERIOD_DIV_CODE", "D")
        // 날짜는 YYYYMMDD다. ISO 하이픈이 그대로 나가면 KIS가 조회 구간을 못 읽는다
        assertThat(kis.lastParams()).containsEntry("FID_INPUT_DATE_1", "20260801")
        assertThat(kis.lastParams()).containsEntry("FID_INPUT_DATE_2", "20260813")
    }

    /**
     * **이 파일에서 가장 중요한 테스트다.**
     *
     * `.DJI`의 `.`과 `HK#HS`의 `#`은 URI에서 특수한 뜻을 가진다. 특히 `#`은 프래그먼트
     * 구분자라서, 값이 쿼리 파라미터 값으로 인코딩되지 않으면 `#`부터가 잘려 나가 KIS에는
     * `HK`만 도착한다. 그런데 그 요청은 400이 아니라 **다른 지수의 멀쩡한 응답**을 받아올 수
     * 있어서, 결과만 보고는 심볼이 잘렸다는 걸 알아챌 수가 없다.
     *
     * 그래서 서버가 실제로 받은 **raw 쿼리 문자열**에 대고 단언한다. 클라이언트 쪽에서
     * "인코딩했다"고 확인하는 것으로는 부족하다 — 잘림은 전송 중에 일어나기 때문이다.
     */
    @Test
    fun `점과 샵이 든 심볼이 잘리지 않고 서버까지 도착한다`() {
        val client = KisIndexClient(properties(), issuer("T1"))

        client.fetchOverseasRaw(".DJI", from, to)
        client.fetchOverseasRaw("HK#HS", from, to)

        // 전선 위에서는 #이 %23으로 인코딩되어 있어야 한다. 날것으로 나가면 프래그먼트로 잘린다
        assertThat(kis.rawQueries[1])
            .describedAs("HK#HS의 #이 쿼리 값으로 인코딩되어야 한다 (raw=%s)", kis.rawQueries[1])
            .contains("FID_INPUT_ISCD=HK%23HS")

        // 그리고 서버가 디코딩하면 원래 심볼이 온전히 나와야 한다
        assertThat(kis.params[0]).containsEntry("FID_INPUT_ISCD", ".DJI")
        assertThat(kis.params[1]).containsEntry("FID_INPUT_ISCD", "HK#HS")
    }

    /**
     * `output1`·`output2` 중 어느 쪽에 최신 봉이 실리는지가 바로 이 엔드포인트로 확인하려는 것이다.
     * 클라이언트가 한쪽을 고르거나 펼치는 순간 확인할 대상이 사라진다.
     */
    @Test
    fun `output1과 output2가 그대로 살아서 올라온다`() {
        val client = KisIndexClient(properties(), issuer("T1"))

        val body = client.fetchOverseasRaw("SPX", from, to)

        assertThat(body).containsEntry("rt_cd", "0")
        assertThat(body).containsEntry("msg1", "정상처리 되었습니다.")
        assertThat(body["output1"]).isEqualTo(mapOf("ovrs_nmix_prpr" to "6389.45", "prdy_vrss" to "-12.5"))
        assertThat(body["output2"]).isEqualTo(
            listOf(
                mapOf("stck_bsop_date" to "20260813", "ovrs_nmix_prpr" to "6389.45"),
                mapOf("stck_bsop_date" to "20260812", "ovrs_nmix_prpr" to "6401.95"),
            )
        )
    }

    /**
     * 토큰 캐시는 이 메서드에서도 load-bearing이다 — KIS는 발급 호출을 분당 1회 정도로 막고,
     * 2026-08-13 운영에서 지수마다 발급하다 3건 중 2건이 403으로 죽었다. 진단용 호출이라도
     * 새로 발급하면 같은 제한을 수집 배치와 나눠 쓰게 된다.
     */
    @Test
    fun `두 번 불러도 토큰은 한 번만 발급된다`() {
        val issuer = issuer("T1")
        val client = KisIndexClient(properties(), issuer)

        client.fetchOverseasRaw("SPX", from, to)
        client.fetchOverseasRaw("NDX", from, to)

        verify(issuer, times(1)).issueToken()
        assertThat(kis.authHeaders).containsExactly("Bearer T1", "Bearer T1")
    }

    /**
     * KIS가 실패라고 말하면 그 문구를 그대로 올린다. 200 + rt_cd != 0을 성공으로 통과시키면
     * 빈 응답을 "해외 지수는 이렇게 생겼구나"로 읽게 된다.
     */
    @Test
    fun `KIS 에러 응답은 KisIndexException이 된다`() {
        kis.body = """{"rt_cd":"1","msg_cd":"OPSQ0001","msg1":"기간이 올바르지 않습니다."}"""
        val client = KisIndexClient(properties(), issuer("T1"))

        assertThatThrownBy { client.fetchOverseasRaw("SPX", from, to) }
            .isInstanceOf(KisIndexException::class.java)
            .hasMessageContaining("기간이 올바르지 않습니다.")
    }

    @Test
    fun `인증 정보가 없으면 토큰을 발급하지도 않는다`() {
        val issuer = issuer("T1")
        val client = KisIndexClient(KisProperties().apply { baseUrl = kis.baseUrl }, issuer)

        assertThatThrownBy { client.fetchOverseasRaw("SPX", from, to) }
            .isInstanceOf(KisIndexException::class.java)
            .hasMessageContaining("KIS_APP_KEY")

        verify(issuer, times(0)).issueToken()
    }

    // ── helpers ──────────────────────────────────────────────────

    private fun properties() = KisProperties().apply {
        appKey = "key"
        appSecret = "secret"
        baseUrl = kis.baseUrl
    }

    private fun issuer(accessToken: String): KisApiClient {
        val client = mock(KisApiClient::class.java)
        org.mockito.Mockito.`when`(client.issueToken())
            .thenReturn(KisTokenResponse(accessToken = accessToken, expiresIn = 86_400))
        return client
    }

    /**
     * 해외 지수 엔드포인트를 흉내내는 루프백 서버. 받은 경로·헤더·**raw 쿼리 문자열**을
     * 순서대로 남긴다. 본문 모양은 실제 KIS 응답이 아니라 "두 갈래가 온다"는 사실만 담은
     * 자리표시자다 — 진짜 모양은 이 엔드포인트로 확인할 것이라, 여기서 실측인 척하면 안 된다.
     */
    private class FakeKisOverseas {
        val authHeaders = mutableListOf<String>()
        val trIds = mutableListOf<String>()
        val paths = mutableListOf<String>()
        val rawQueries = mutableListOf<String>()
        val params = mutableListOf<Map<String, String>>()

        @Volatile
        var body: String = """
            {"rt_cd":"0","msg1":"정상처리 되었습니다.",
             "output1":{"ovrs_nmix_prpr":"6389.45","prdy_vrss":"-12.5"},
             "output2":[{"stck_bsop_date":"20260813","ovrs_nmix_prpr":"6389.45"},
                        {"stck_bsop_date":"20260812","ovrs_nmix_prpr":"6401.95"}]}
        """.trimIndent()

        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/uapi/overseas-price/v1/quotations/inquire-daily-chartprice") { exchange ->
                synchronized(paths) {
                    paths += exchange.requestURI.path
                    rawQueries += exchange.requestURI.rawQuery ?: ""
                    params += decode(exchange.requestURI.rawQuery)
                    authHeaders += exchange.requestHeaders.getFirst("authorization") ?: "(없음)"
                    trIds += exchange.requestHeaders.getFirst("tr_id") ?: "(없음)"
                }
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

        fun lastParams(): Map<String, String> = params.last()

        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

        fun stop() = server.stop(0)

        private fun decode(rawQuery: String?): Map<String, String> =
            (rawQuery ?: "").split("&").filter { it.isNotBlank() }.associate { pair ->
                val name = pair.substringBefore("=")
                val value = pair.substringAfter("=", "")
                URLDecoder.decode(name, StandardCharsets.UTF_8) to
                    URLDecoder.decode(value, StandardCharsets.UTF_8)
            }
    }
}
