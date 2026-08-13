package com.allfolio.fx

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
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
     * **성공 본문이 이 클래스의 배송물이다** — 로그가 아니라 브라우저·devtools·디스크 캐시로 나가고,
     * 사용 방식이 "받은 걸 붙여넣어 물어본다"라 Slack·노션까지 간다. 그러니 인증키만은 가린다.
     *
     * 2xx에 키가 실려 오냐고 물으면, **ECOS는 RESULT 오류를 HTTP 200으로 준다.**
     * 형제 클라이언트의 마스킹이 존재하는 이유가 "등록되지 않은 인증키입니다: XXX" 모양이고,
     * 그 응답이 바로 200이다. 가려도 탐색 가치는 하나도 줄지 않는다 —
     * 이걸 부르는 사람이 확인하려는 건 통계표 코드지 자기 인증키가 아니다.
     */
    @Test
    fun `성공 본문에서도 인증키를 가린다`() {
        val port = serve { ex ->
            // ECOS 인증 오류의 실제 모양. RESULT는 200으로 온다
            respond(ex, 200, """{"RESULT":{"CODE":"INFO-100","MESSAGE":"등록되지 않은 인증키입니다: $apiKey"}}""")
        }

        val body = client(port).tables(null)

        assertThat(body).doesNotContain(apiKey)
        assertThat(body).contains("***")
        // 가리는 건 키뿐이다. 나머지 JSON을 뭉개면 보러 온 것을 못 보게 된다
        assertThat(body).contains("INFO-100").contains("등록되지 않은 인증키입니다")
    }

    /** 키가 비어 있을 때 replace를 걸면 글자 사이마다 마스크가 낀다 — 그 경로는 애초에 NO_KEY로 끝난다 */
    @Test
    fun `정상 목록 응답은 한 글자도 바뀌지 않는다`() {
        val body = """{"StatisticTableList":{"list_total_count":834,"row":[{"STAT_CODE":"721Y001"}]}}"""
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

    /**
     * **예외 단언으로는 로그를 못 본다.** 누군가 "진단이 부족하다"며 로그 줄에 `e.message`나 경로를
     * 얹으면 인증키가 Render 대시보드로 흘러가는데, 스택 단언은 초록으로 남는다 —
     * 존재 이유가 그 규율인 파일에서. [EcosStatisticSearchClientTest]의 같은 단언을 옮겨 온다.
     *
     * 남기는 게 없어서 통과하는 걸 막으려고, WARN이 실제로 찍혔다는 것도 함께 본다.
     */
    @Test
    fun `HTTP 실패 로그에 인증키가 남지 않는다`() {
        val logger = LoggerFactory.getLogger(EcosStatListClient::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            val port = serve { ex ->
                // 요청 URI를 되울리되 키를 퍼센트 인코딩한다 — 문자열 마스킹만으로는 절대 못 잡는 형태라,
                // "본문을 아예 안 만진다"는 이 클래스의 방침이 실제로 지켜지는지가 여기서 갈린다
                val encoded = ex.requestURI.path.replace(apiKey, "SUPERSECRET%4B%45%59")
                respond(ex, 500, "<html><body>등록되지 않은 인증키입니다: $apiKey / $encoded</body></html>")
            }

            catchThrowable { client(port).items("901Y009") }

            val logged = appender.list.joinToString("\n") { it.formattedMessage }
            assertThat(logged).describedAs("실패 로그 자체가 없으면 이 단언은 아무것도 증명하지 않는다").contains("HTTP 500")
            assertThat(logged).doesNotContain(apiKey)
            assertThat(logged).doesNotContain("SUPERSECRET") // 퍼센트 인코딩된 조각도 없다
            assertThat(logged).doesNotContain(PATH_MARKER)
        } finally {
            logger.detachAppender(appender)
        }
    }

    /** 연결 실패 경로도 같다 — Reactor가 checkpoint 프레임에 요청 URI를 통째로 싣는다 */
    @Test
    fun `연결 실패 로그에 인증키가 남지 않는다`() {
        val logger = LoggerFactory.getLogger(EcosStatListClient::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            val deadPort = ServerSocket(0).use { it.localPort }

            catchThrowable { client(deadPort).items("901Y009") }

            val logged = appender.list.joinToString("\n") { it.formattedMessage }
            assertThat(logged).describedAs("실패 로그 자체가 없으면 이 단언은 아무것도 증명하지 않는다").contains("목록 조회 실패")
            assertThat(logged).doesNotContain(apiKey)
            assertThat(logged).doesNotContain(PATH_MARKER)
            // 어느 API에 어떤 코드로 물었는지는 비밀이 아니다. 이게 없으면 시도들을 로그로 구분할 수 없다
            assertThat(logged).contains("StatisticItemList").contains("901Y009")
        } finally {
            logger.detachAppender(appender)
        }
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
