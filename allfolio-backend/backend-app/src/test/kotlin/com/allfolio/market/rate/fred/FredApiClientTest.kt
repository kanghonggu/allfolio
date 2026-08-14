package com.allfolio.market.rate.fred

import com.allfolio.test.dedicatedConnector
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

/**
 * FRED 인증키는 **쿼리 파라미터**(`api_key=`)에 실린다. ECOS는 경로 첫 세그먼트지만 위치만 다르고
 * 노출 위험은 같다 — "던져진 예외 어디에도 인증키가 없다"가 이 클래스의 핵심 속성이다.
 * 깨져도 조용하고 운영 로그에서야 드러나므로 여기서 고정한다.
 * (`EcosStatisticSearchClientTest`가 같은 이유로 같은 그물을 친다.)
 *
 * 유출 경로가 두 갈래라 둘 다 태운다:
 *   1. 예외 자신 — Reactor가 붙이는 `*__checkpoint` suppressed 프레임에 요청 URI가 통째로 들어간다
 *   2. 응답 본문 — 기본 오류 페이지는 요청 URI(쿼리까지)를 본문에 렌더링하고,
 *      그 본문이 예외 메시지에 실리면 그대로 샌다
 *
 * 단언은 "키가 없다"에 더해 "요청 경로가 없다"까지 본다 — 경로 조각이 보이면 그 뒤 쿼리도 곧 보인다.
 */
class FredApiClientTest {

    private companion object {
        const val API_KEY = "SUPERSECRETFREDKEY1234"

        /** 요청 경로 표식. 이 문자열은 클래스 이름에 없으므로 스택프레임 오탐이 나지 않는다. */
        const val PATH_MARKER = "/fred/series/observations"

        val FROM: LocalDate = LocalDate.of(2026, 1, 2)
        val TO: LocalDate = LocalDate.of(2026, 3, 4)
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

    // 커넥터를 dedicatedConnector로 두는 이유는 그쪽 주석에 있다 — 빼면 간헐적으로 깨진다.
    private fun client(port: Int, key: String = API_KEY) = FredApiClient(
        FredProperties().also {
            it.apiKey = key
            it.baseUrl = "http://localhost:$port"
        },
        FredObservationParser(ObjectMapper()),
    ).apply { connector = dedicatedConnector() }

    private fun call(port: Int) = client(port).fetch("DGS10", FROM, TO)

    /** 아무도 듣지 않는 포트. 여는 즉시 닫아 두므로 연결이 거부된다. */
    private fun deadPort(): Int = ServerSocket(0).use { it.localPort }

    /** 예외 전체(메시지 + cause 체인 + suppressed + 모든 스택프레임)에 비밀이 없는지 본다. */
    private fun assertNoSecretAnywhere(t: Throwable) {
        val dump = t.stackTraceToString()
        assertThat(dump).doesNotContain(API_KEY)
        assertThat(dump).doesNotContain(PATH_MARKER)
    }

    private fun queryOf(rawQuery: String): Map<String, String> =
        rawQuery.split("&").associate { pair ->
            val (name, value) = pair.split("=", limit = 2)
            URLDecoder.decode(name, "UTF-8") to URLDecoder.decode(value, "UTF-8")
        }

    @Test
    fun `요청 경로와 쿼리가 문서화된 형태와 일치한다`() {
        // 쿼리를 문자열 하나로 단언하면 파라미터 순서·인코딩 방식에 묶여 깨진다 — 값으로 파싱해 본다.
        val seenPath = AtomicReference<String>()
        val seenQuery = AtomicReference<String>()
        val port = serve { ex ->
            seenPath.set(ex.requestURI.path)
            seenQuery.set(ex.requestURI.rawQuery)
            respond(ex, 200, """{"observations":[{"date":"2026-01-02","value":"4.25"}]}""")
        }

        val result = call(port)

        assertThat(seenPath.get()).isEqualTo(PATH_MARKER)
        assertThat(queryOf(seenQuery.get())).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "series_id" to "DGS10",
                "api_key" to API_KEY,
                "file_type" to "json",
                // FRED는 ISO 날짜(yyyy-MM-dd)를 받는다. LocalDate.toString()이 그 형식이라 포맷터가 없다 —
                // 여기서 형식을 고정해 두지 않으면 누군가 ECOS 형식(yyyyMMdd)으로 맞추려 해도 조용히 지나간다.
                "observation_start" to "2026-01-02",
                "observation_end" to "2026-03-04",
            ),
        )
        assertThat(result.rows).hasSize(1)
        assertThat(result.rows[0].value).isEqualByComparingTo("4.25")
    }

    /** 설정 누락은 상류 장애가 아니라 우리 문제다 — 코드가 갈려야 운영자가 Render 환경변수를 보러 간다 */
    @Test
    fun `인증키가 비면 호출 전에 NO_KEY로 실패한다`() {
        // 죽은 포트를 줘도 NO_KEY가 나오면 네트워크로 나가기 전에 막혔다는 뜻이다.
        val raw = catchThrowable { client(deadPort(), key = "").fetch("DGS10", FROM, TO) }

        assertThat(raw).isInstanceOf(FredApiException::class.java)
        assertThat((raw as FredApiException).code).isEqualTo("NO_KEY")
    }

    @Test
    fun `시리즈 ID가 비면 호출 전에 NO_SERIES로 실패한다`() {
        val raw = catchThrowable { client(deadPort()).fetch("", FROM, TO) }

        assertThat(raw).isInstanceOf(FredApiException::class.java)
        assertThat((raw as FredApiException).code).isEqualTo("NO_SERIES")
    }

    @Test
    fun `요청 URI를 되울리는 500 본문에서도 인증키가 새지 않는다`() {
        // 기본 오류 페이지가 하는 그대로 — 요청 URI를 쿼리까지 본문에 렌더링한다.
        val port = serve { ex ->
            respond(ex, 500, "<html><body><b>Message</b> ${ex.requestURI}</body></html>")
        }

        val raw = catchThrowable { call(port) }

        assertThat(raw).isInstanceOf(FredApiException::class.java)
        assertThat((raw as FredApiException).code).isEqualTo("HTTP-500")
        assertNoSecretAnywhere(raw)
    }

    /**
     * 본문이 JSON이 아니면 Jackson 예외가 원본 본문을 `[Source: (String)"..."]`로 물고 나온다 —
     * 그 본문에 되울려 온 쿼리가 있으면 인증키가 예외 메시지에 실린다.
     * 예외 메시지는 `RateCollectSummary.failures`를 타고 어드민 응답까지 나가는 값이다.
     */
    @Test
    fun `요청 URI를 되울리는 비-JSON 200 본문에서도 인증키가 새지 않는다`() {
        val port = serve { ex ->
            respond(ex, 200, "<html><body>점검중 ${ex.requestURI}</body></html>")
        }

        val raw = catchThrowable { call(port) }

        assertThat(raw).isInstanceOf(FredApiException::class.java)
        assertThat((raw as FredApiException).code).isEqualTo("MALFORMED")
        assertNoSecretAnywhere(raw)
    }

    @Test
    fun `연결 실패에서도 인증키가 새지 않는다`() {
        // Reactor가 checkpoint suppressed 프레임에 요청 URI를 실어 보낸다 — cause를 붙이면 그대로 샌다.
        val raw = catchThrowable { call(deadPort()) }

        assertThat(raw).isInstanceOf(FredApiException::class.java)
        assertThat((raw as FredApiException).code).isEqualTo("IO")
        assertNoSecretAnywhere(raw)
    }

    @Test
    fun `타임아웃도 FredApiException으로 통일되고 인증키가 새지 않는다`() {
        // 응답을 주지 않고 물고 있는 서버. block(timeout)이 던지는 IllegalStateException이
        // 그대로 새면 수집 요약의 failures에 리액터 내부 메시지가 실린다 — 코드 한 종류로 통일한다.
        // 기본 30초라 타임아웃을 주입해 싸게 만든다(timeout 필드가 존재하는 이유).
        //
        // **핸들러 sleep을 길게 잡지 말 것.** `HttpServer.stop()`은 디스패처 스레드를 join하므로
        // 잠든 시간을 tearDown이 통째로 기다린다 — 여기서 30초를 주면 이 테스트 하나가 CI에서
        // 30초를 먹는다(`EcosStatisticSearchClientTest`의 같은 테스트들이 실제로 그렇다).
        // 타임아웃(300ms)보다 넉넉히 길기만 하면 된다.
        val port = serve { Thread.sleep(2_000) }
        val client = client(port).apply { timeout = Duration.ofMillis(300) }

        val raw = catchThrowable { client.fetch("DGS10", FROM, TO) }

        assertThat(raw).isInstanceOf(FredApiException::class.java)
        assertThat((raw as FredApiException).code).isEqualTo("IO")
        assertNoSecretAnywhere(raw)
    }
}
