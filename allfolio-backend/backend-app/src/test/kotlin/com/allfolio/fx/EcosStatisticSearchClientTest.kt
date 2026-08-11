package com.allfolio.fx

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
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
    fun `코덱 한도를 넘는 응답은 DECODE로 보고된다`() {
        // 실측: DataBufferLimitException은 그대로 새어 나오지 않고 WebClientResponseException(status=200)으로
        // 감싸여 온다. 그래서 코드를 분기하지 않으면 "HTTP 200 실패"라는 엉뚱한 보고가 된다 —
        // 운영자가 ECOS를 의심하게 만드는 자리라 DECODE로 갈라 둔다.
        val oversized = "x".repeat(9 * 1024 * 1024) // maxInMemorySize(8MB) 초과
        val port = serve { ex -> respond(ex, 200, oversized) }

        val raw = catchThrowable { call(port) }

        assertThat(raw).isInstanceOf(EcosApiException::class.java)
        assertThat((raw as EcosApiException).code).isEqualTo("DECODE")
        assertNoSecretAnywhere(raw)
    }

    @Test
    fun `RESULT 오류 메시지에 되울려 온 인증키가 예외로 새지 않는다`() {
        // 잘 형성된 JSON이라 파서가 정상 동작하고 RESULT.MESSAGE를 detail에 그대로 넣는다.
        // 서버가 준 문자열이므로 본문 미리보기와 똑같이 서버제어 → 예외 경로다.
        val port = serve { ex ->
            respond(
                ex, 200,
                """{"RESULT":{"CODE":"INFO-300","MESSAGE":"조회 실패: ${ex.requestURI.path}"}}""",
            )
        }

        val raw = catchThrowable { call(port) }

        assertThat(raw).isInstanceOf(EcosApiException::class.java)
        val thrown = raw as EcosApiException
        assertThat(thrown.code).isEqualTo("INFO-300") // 분기용 코드는 보존한다
        assertNoSecretAnywhere(thrown)
    }

    @Test
    fun `RESULT 메시지가 길어도 잘라서 싣는다`() {
        val port = serve { ex ->
            respond(ex, 200, """{"RESULT":{"CODE":"INFO-300","MESSAGE":"${"가".repeat(5_000)}"}}""")
        }

        val raw = catchThrowable { call(port) }

        assertThat((raw as EcosApiException).detail).hasSizeLessThanOrEqualTo(200)
    }

    @Test
    fun `타임아웃은 EcosApiException으로 통일되고 인증키가 새지 않는다`() {
        // catch (Throwable) 종단 절의 실제 유일한 사용자. 기본 30초라 타임아웃을 주입해 싸게 만든다.
        val port = serve { Thread.sleep(30_000) } // 응답을 주지 않고 물고 있는다
        val client = client(port).apply { timeout = Duration.ofMillis(300) }

        val raw = catchThrowable {
            client.fetchDailyRates("TEST-STAT-CODE", "TEST-ITEM-CODE", LocalDate.now(), LocalDate.now())
        }

        assertThat(raw).isInstanceOf(EcosApiException::class.java)
        assertThat((raw as EcosApiException).code).isEqualTo("IO")
        assertNoSecretAnywhere(raw)
    }

    @Test
    fun `인터럽트되면 플래그를 남긴 채 EcosApiException으로 나간다`() {
        // 플래그가 지워지면 종료 중 끊긴 백필이 ECOS 장애로 읽히고 Task 10 루프가 다음 통화로 넘어간다.
        // (실측상 지금은 reactor-core가 복원해 주지만, 그 의존을 테스트로 못 박아 둔다.)
        val port = serve { Thread.sleep(30_000) }
        val client = client(port).apply { timeout = Duration.ofSeconds(20) }

        val started = CountDownLatch(1)
        val thrown = AtomicReference<Throwable>()
        val flagRestored = AtomicBoolean(false)
        val worker = Thread {
            started.countDown()
            try {
                client.fetchDailyRates("TEST-STAT-CODE", "TEST-ITEM-CODE", LocalDate.now(), LocalDate.now())
            } catch (e: Throwable) {
                thrown.set(e)
            }
            flagRestored.set(Thread.currentThread().isInterrupted)
        }
        worker.start()
        started.await()
        Thread.sleep(500) // 요청이 실제로 대기 상태에 들어간 뒤 끊는다
        worker.interrupt()
        worker.join(10_000)

        assertThat(thrown.get()).isInstanceOf(EcosApiException::class.java)
        assertThat(flagRestored).isTrue()
        assertNoSecretAnywhere(thrown.get())
    }

    @Test
    fun `WARN 로그의 본문 미리보기에서 인증키가 마스킹된다`() {
        // 예외에서는 본문을 뺐지만 로그에는 미리보기가 남는다. 이 로그는 Render 대시보드로 나가므로
        // 마스킹이 빠지면 조용히 키가 흘러간다 — 예외만 보는 단언으로는 못 잡는 자리다.
        val logger = LoggerFactory.getLogger(EcosStatisticSearchClient::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            // 키를 경로 밖에 단독으로 싣는다 — ECOS 인증 오류 페이지가 하는 모양이다.
            // 경로 안에 두면 URI 통째 제거가 먼저 먹어서 "마스킹이 살아 있는지"를 못 가린다.
            val port = serve { ex -> respond(ex, 500, "<html><body>등록되지 않은 인증키입니다: $apiKey</body></html>") }

            catchThrowable { call(port) }

            val logged = appender.list.joinToString("\n") { it.formattedMessage }
            assertThat(logged).contains("***")          // 마스킹이 실제로 일어났다
            assertThat(logged).doesNotContain(apiKey)   // 평문 키는 없다
        } finally {
            logger.detachAppender(appender)
        }
    }

    @Test
    fun `WARN 로그에서 되울려 온 요청 URI가 통째로 지워진다`() {
        // 마스킹은 정확 일치일 때만 듣는다. 퍼센트 인코딩된 키는 통과하므로 경로를 통으로 지운다.
        val logger = LoggerFactory.getLogger(EcosStatisticSearchClient::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            val port = serve { ex ->
                // 키를 퍼센트 인코딩해서 되울린다 — 마스킹만으로는 절대 못 잡는 형태.
                val encoded = ex.requestURI.path.replace("SUPERSECRETKEY1234", "SUPERSECRETKEY%31%32%33%34")
                respond(ex, 500, "<html><body><b>Message</b> $encoded</body></html>")
            }

            catchThrowable { call(port) }

            val logged = appender.list.joinToString("\n") { it.formattedMessage }
            assertThat(logged).contains("[요청 URI 생략]")
            assertThat(logged).doesNotContain("SUPERSECRETKEY")
        } finally {
            logger.detachAppender(appender)
        }
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
