package com.allfolio.fx

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference

/**
 * [EcosStatListClient]도 인증키를 URL 경로에 싣는다 — [EcosStatisticSearchClientTest]가 고정한
 * "던져진 예외 어디에도 인증키가 없다"는 속성이 여기서도 그대로 필요하다. 새 클라이언트를
 * 하나 더 만들면서 그 속성을 테스트 없이 두면, 깨진 걸 운영 로그에서야 알게 된다.
 *
 * 경로 단언이 함께 있는 이유: 이 클라이언트의 존재 이유가 "수집 대상 코드를 눈으로 확인한다"인데,
 * 세그먼트 순서가 틀리면 확인하러 온 사람이 확인 대신 오류를 본다. ECOS 공개 sample 키로
 * 실호출해 확인한 형태를 여기 못 박아 둔다.
 */
class EcosStatListClientTest {

    private val apiKey = "SUPERSECRETKEY1234"

    private companion object {
        /** 요청 경로 표식. 클래스 이름(EcosStatListClient)과 겹치지 않아 오탐이 없다. */
        const val PATH_MARKER = "/api/Statistic"
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

    private fun client(port: Int, key: String = apiKey) =
        EcosStatListClient(EcosProperties(apiKey = key, baseUrl = "http://localhost:$port"))

    private fun assertNoSecretAnywhere(t: Throwable) {
        val dump = t.stackTraceToString()
        assertThat(dump).doesNotContain(apiKey)
        assertThat(dump).doesNotContain(PATH_MARKER)
    }

    @Test
    fun `통계표 목록 경로가 문서화된 형태와 일치한다`() {
        val seen = AtomicReference<String>()
        val port = serve { ex ->
            seen.set(ex.requestURI.path)
            respond(ex, 200, """{"StatisticTableList":{"list_total_count":834}}""")
        }

        client(port).tables(null)

        assertThat(seen.get()).isEqualTo("/api/StatisticTableList/$apiKey/json/kr/1/10000")
    }

    /** 통계표 코드는 마지막 세그먼트로 붙는다 — 쿼리 파라미터가 아니다 */
    @Test
    fun `통계표 코드를 주면 경로 끝에 붙는다`() {
        val seen = AtomicReference<String>()
        val port = serve { ex ->
            seen.set(ex.requestURI.path)
            respond(ex, 200, "{}")
        }

        client(port).tables("0000000001")

        assertThat(seen.get()).isEqualTo("/api/StatisticTableList/$apiKey/json/kr/1/10000/0000000001")
    }

    @Test
    fun `항목 목록 경로가 문서화된 형태와 일치한다`() {
        val seen = AtomicReference<String>()
        val port = serve { ex ->
            seen.set(ex.requestURI.path)
            respond(ex, 200, "{}")
        }

        client(port).items("901Y009")

        assertThat(seen.get()).isEqualTo("/api/StatisticItemList/$apiKey/json/kr/1/10000/901Y009")
    }

    /**
     * **파싱하지 않는 것이 이 클라이언트의 일이다.** 오류 응답(RESULT)까지 그대로 나가야
     * 코드를 확인하러 온 사람이 "없는 코드"라는 사실을 첫 호출에서 본다.
     */
    @Test
    fun `상류 본문을 파싱하지 않고 그대로 돌려준다`() {
        val body = """{"RESULT":{"CODE":"INFO-200","MESSAGE":"해당하는 데이터가 없습니다."}}"""
        val port = serve { ex -> respond(ex, 200, body) }

        assertThat(client(port).tables(null)).isEqualTo(body)
    }

    /**
     * 서비스 이름이 틀리면 ECOS는 RESULT가 아니라 HTTP 404를 준다(실측). 상태 코드를 살리지 않으면
     * 경로 실수가 연결 실패와 같은 문구로 뭉개진다 — 경로를 확인하러 만든 도구가 경로 실수를 감춘다.
     */
    @Test
    fun `HTTP 오류는 상태 코드를 살려 보고하고 인증키를 흘리지 않는다`() {
        // 정부 Java 스택의 기본 오류 페이지가 하는 그대로 — 요청 URI를 본문에 렌더링한다
        val port = serve { ex -> respond(ex, 404, "<html><body>Not Found ${ex.requestURI.path}</body></html>") }

        val raw = catchThrowable { client(port).tables(null) }

        assertThat(raw).isInstanceOf(EcosApiException::class.java)
        assertThat((raw as EcosApiException).code).isEqualTo("HTTP-404")
        assertNoSecretAnywhere(raw)
    }

    @Test
    fun `연결 실패에서도 인증키가 새지 않는다`() {
        // 아무도 듣지 않는 포트. Reactor가 checkpoint suppressed 프레임에 요청 URI를 실어 보낸다.
        val deadPort = ServerSocket(0).use { it.localPort }

        val raw = catchThrowable { client(deadPort).tables(null) }

        assertThat(raw).isInstanceOf(EcosApiException::class.java)
        assertThat((raw as EcosApiException).code).isEqualTo("IO")
        assertNoSecretAnywhere(raw)
    }

    /** 키가 없으면 호출 자체를 하지 않는다 — 죽은 포트인데도 NO_KEY가 나오는 것이 그 증거다 */
    @Test
    fun `인증키가 없으면 호출하지 않고 NO_KEY로 끝낸다`() {
        val deadPort = ServerSocket(0).use { it.localPort }

        val raw = catchThrowable { client(deadPort, key = "").items("901Y009") }

        assertThat(raw).isInstanceOf(EcosApiException::class.java)
        assertThat((raw as EcosApiException).code).isEqualTo("NO_KEY")
    }
}
