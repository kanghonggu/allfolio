package com.allfolio.fx

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

/**
 * ECOS 인증키는 URL 경로에 들어간다. 그래서 "던져진 예외 어디에도 인증키가 없다"가 이 클래스의 핵심 속성인데,
 * 깨져도 조용하고 운영 로그에서야 드러난다 — 여기서 고정한다.
 *
 * 유출 경로가 두 갈래라 둘 다 태운다:
 *   1. 예외 자신 — Reactor가 붙이는 *__checkpoint suppressed 프레임에 요청 URI가 통째로 들어간다
 *   2. 응답 본문 — 정부 Java 스택의 기본 오류 페이지는 요청 URI를 본문에 렌더링하고,
 *      우리는 그 본문을 미리보기로 로그·예외 메시지에 싣는다
 *
 * 그래서 단언은 "키가 없다"에 더해 "요청 경로가 없다"까지 본다 — 경로 조각이 보이면 키도 곧 보인다.
 * 경로 표식으로 "StatisticSearch"만 쓰면 클래스 이름(EcosStatisticSearchClient)이 스택프레임에 찍혀 늘 걸리므로
 * "/api/StatisticSearch"로 본다.
 */
class EcosStatisticSearchClientTest {

    private val apiKey = "SUPERSECRETKEY1234"

    private companion object {
        /** 요청 경로 표식. 클래스 이름에도 들어 있는 "StatisticSearch" 단독으로는 오탐이 난다. */
        const val PATH_MARKER = "/api/StatisticSearch"
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

    private fun client(port: Int) = EcosStatisticSearchClient(
        EcosProperties(apiKey = apiKey, baseUrl = "http://localhost:$port"),
        EcosResponseParser(ObjectMapper()),
    )

    private fun call(port: Int) = client(port).fetchDailyRates(
        "TEST-STAT-CODE", "TEST-ITEM-CODE", LocalDate.of(2026, 1, 2), LocalDate.of(2026, 3, 4),
    )

    /** 예외 전체(메시지 + cause 체인 + suppressed + 모든 스택프레임)에 비밀이 없는지 본다. */
    private fun assertNoSecretAnywhere(t: Throwable) {
        val dump = t.stackTraceToString()
        assertThat(dump).doesNotContain(apiKey)
        assertThat(dump).doesNotContain(PATH_MARKER)
    }

    @Test
    fun `요청 경로가 문서화된 형태와 정확히 일치한다`() {
        // 세그먼트 순서가 틀리면 ECOS는 오류 대신 조용히 0건을 준다 — 그래서 정확히 고정한다.
        val seen = AtomicReference<String>()
        val port = serve { ex ->
            seen.set(ex.requestURI.path)
            respond(ex, 200, """{"StatisticSearch":{"row":[{"TIME":"20260102","DATA_VALUE":"1390.2"}]}}""")
        }

        val result = call(port)

        assertThat(seen.get()).isEqualTo(
            "/api/StatisticSearch/$apiKey/json/kr/1/100000/TEST-STAT-CODE/D/20260102/20260304/TEST-ITEM-CODE",
        )
        assertThat(result.rates).hasSize(1)
        assertThat(result.rates[0].rateKrw).isEqualByComparingTo("1390.2")
    }

    @Test
    fun `요청 URI를 되울리는 500 본문에서도 인증키가 새지 않는다`() {
        // Tomcat 기본 오류 페이지가 하는 그대로 — 요청 URI를 본문에 렌더링한다.
        val port = serve { ex ->
            respond(ex, 500, "<html><body><b>Message</b> ${ex.requestURI.path}</body></html>")
        }

        val raw = catchThrowable { call(port) }

        assertThat(raw).isInstanceOf(EcosApiException::class.java)
        val thrown = raw as EcosApiException

        assertThat(thrown.code).isEqualTo("HTTP-500")
        assertNoSecretAnywhere(thrown)
    }

    @Test
    fun `요청 URI를 되울리는 비-JSON 200 본문에서도 인증키가 새지 않는다`() {
        val port = serve { ex ->
            respond(ex, 200, "<html><body>점검중 ${ex.requestURI.path}</body></html>")
        }

        val raw = catchThrowable { call(port) }

        assertThat(raw).isInstanceOf(EcosApiException::class.java)
        val thrown = raw as EcosApiException

        assertThat(thrown.code).isEqualTo("MALFORMED")
        assertNoSecretAnywhere(thrown)
    }

    @Test
    fun `연결 실패에서도 인증키가 새지 않는다`() {
        // 아무도 듣지 않는 포트. Reactor가 checkpoint suppressed 프레임에 요청 URI를 실어 보낸다.
        val deadPort = ServerSocket(0).use { it.localPort }

        val raw = catchThrowable { call(deadPort) }

        assertThat(raw).isInstanceOf(EcosApiException::class.java)
        val thrown = raw as EcosApiException

        assertThat(thrown.code).isEqualTo("CONN")
        assertNoSecretAnywhere(thrown)
    }

    @Test
    fun `코덱 한도를 넘는 응답에서도 인증키가 새지 않는다`() {
        // DataBufferLimitException은 IllegalStateException을 상속하면서 WebClient 체인 안에서 터진다 —
        // 즉 checkpoint 프레임을 달고 나온다. 예외 종류를 열거하는 방식으로는 잡히지 않는 자리다.
        val oversized = "x".repeat(9 * 1024 * 1024) // maxInMemorySize(8MB) 초과
        val port = serve { ex -> respond(ex, 200, oversized) }

        val raw = catchThrowable { call(port) }

        assertThat(raw).isInstanceOf(EcosApiException::class.java)
        assertNoSecretAnywhere(raw)
    }

    @Test
    fun `설정 누락은 EcosApiException으로 나간다`() {
        // IllegalArgumentException이면 GlobalExceptionHandler가 400을 내보낸다 — 서버 설정 누락은 클라이언트 잘못이 아니다.
        val noKey = EcosStatisticSearchClient(EcosProperties(), EcosResponseParser(ObjectMapper()))

        assertThatThrownBy { noKey.fetchDailyRates("S", "I", LocalDate.now(), LocalDate.now()) }
            .isInstanceOf(EcosApiException::class.java)
            .hasMessageContaining("NO_KEY")

        val noSeries = EcosStatisticSearchClient(
            EcosProperties(apiKey = apiKey), EcosResponseParser(ObjectMapper()),
        )
        assertThatThrownBy { noSeries.fetchDailyRates("", "", LocalDate.now(), LocalDate.now()) }
            .isInstanceOf(EcosApiException::class.java)
            .hasMessageContaining("NO_SERIES")
    }
}
