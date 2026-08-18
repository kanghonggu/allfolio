package com.allfolio.dart.insider

import com.allfolio.dart.DartApiException
import com.allfolio.dart.DartProperties
import com.allfolio.test.dedicatedConnector
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

/**
 * OpenDART 임원·주요주주 소유상황보고 `elestock.json` 클라이언트.
 *
 * 응답 본문은 계획서 Task 10 실측 픽스처(30개사 3,922행에서 추출) 원문에서 잘라 왔다 — 값을
 * 지어내지 않았다. 인증키는 **쿼리 파라미터**(`crtfc_key=`)에 실린다 — `DartListClient`·
 * `DartCorpCodeClient`와 같은 그물을 친다: 예외 어디에도 키도 오퍼레이션 경로 조각도 없어야 한다.
 */
class DartElestockClientTest {

    private companion object { const val API_KEY = "SUPERSECRETELESTOCKKEY5678" }

    private var server: HttpServer? = null
    private val received = AtomicReference<String>()

    @AfterEach fun tearDown() { server?.stop(0) }

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

    /** 본문 하나를 200으로 돌려주는 루프백 스텁. 요청 쿼리를 [received]에 남긴다 */
    private fun serving(body: String): Int = serve { ex ->
        received.set(ex.requestURI.rawQuery)
        respond(ex, 200, body)
    }

    // dedicatedConnector를 쓰는 이유는 StubServerConnector.kt 주석에 있다 — 빼면 간헐적으로 깨진다
    private fun client(port: Int, key: String = API_KEY) = DartElestockClient(
        DartProperties(apiKey = key, baseUrl = "http://localhost:$port"),
        ObjectMapper(),
    ).apply { connector = dedicatedConnector() }

    /** 아무도 듣지 않는 포트. 여는 즉시 닫아 두므로 연결이 거부된다 */
    private fun deadPort(): Int = ServerSocket(0).use { it.localPort }

    private fun queryOf(raw: String): Map<String, String> =
        raw.split("&").associate { p ->
            val (n, v) = p.split("=", limit = 2)
            URLDecoder.decode(n, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

    /** 예외 전체(메시지 + cause 체인 + suppressed + 모든 스택프레임)에 비밀이 없는지 본다 */
    private fun assertNoSecretAnywhere(t: Throwable) {
        val dump = t.stackTraceToString()
        assertThat(dump).doesNotContain(API_KEY)
        assertThat(dump).doesNotContain(DartElestockClient.PATH)
    }

    @Test
    fun `결측이 하이픈인 지배주주 행을 파싱한다`() {
        // 실측 픽스처 A(대교, 2026-05-28)
        val port = serving("""
            {"status":"000","message":"정상","list":[
              {"rcept_no":"20260528000732","rcept_dt":"2026-05-28","corp_code":"00108913","corp_name":"대교",
               "repror":"대교홀딩스","isu_exctv_rgist_at":"-","isu_exctv_ofcps":"-","isu_main_shrholdr":"10%이상주주",
               "sp_stock_lmp_cnt":"47,971,200","sp_stock_lmp_irds_cnt":"1,800,000",
               "sp_stock_lmp_rate":"46.07","sp_stock_lmp_irds_rate":"1.73"}]}
        """.trimIndent())

        val rows = client(port).fetch("00108913")

        assertThat(rows).hasSize(1)
        with(rows.single()) {
            assertThat(rceptNo).isEqualTo("20260528000732")
            assertThat(corpCode).isEqualTo("00108913")
            // elestock의 rcept_dt는 하이픈 포맷이다 — list.json(yyyyMMdd)과 다르다
            assertThat(reportDate).isEqualTo(LocalDate.of(2026, 5, 28))
            assertThat(repror).isEqualTo("대교홀딩스")
            assertThat(ownedQty).isEqualTo(47_971_200L)   // 콤마 제거
            assertThat(changeQty).isEqualTo(1_800_000L)
            assertThat(ownedRate).isEqualByComparingTo(BigDecimal("46.07"))
            assertThat(changeRate).isEqualByComparingTo(BigDecimal("1.73"))
            // "-"는 결측이다. 빈 문자열로 저장하면 화면에 하이픈이 그대로 나간다
            assertThat(officerPosition).isNull()
            assertThat(isRegistered).isNull()
            assertThat(majorHolderType).isEqualTo("10%이상주주")
        }
    }

    @Test
    fun `등기임원과 직위를 읽는다`() {
        // 실측 픽스처 B(대교, 2025-03-31)
        val port = serving("""
            {"status":"000","list":[
              {"rcept_no":"20250331001742","rcept_dt":"2025-03-31","corp_code":"00108913","corp_name":"대교",
               "repror":"박수완","isu_exctv_rgist_at":"등기임원","isu_exctv_ofcps":"사외이사","isu_main_shrholdr":"-",
               "sp_stock_lmp_cnt":"53,380","sp_stock_lmp_irds_cnt":"53,380",
               "sp_stock_lmp_rate":"0.05","sp_stock_lmp_irds_rate":"0.05"}]}
        """.trimIndent())

        with(client(port).fetch("00108913").single()) {
            assertThat(isRegistered).isTrue()
            assertThat(officerPosition).isEqualTo("사외이사")
            assertThat(majorHolderType).isNull()
        }
    }

    @Test
    fun `비등기임원의 음수 증감을 읽는다`() {
        // 실측 픽스처 C(삼성전자, 2024-10-04)
        val port = serving("""
            {"status":"000","list":[
              {"rcept_no":"20241004000101","rcept_dt":"2024-10-04","corp_code":"00126380","corp_name":"삼성전자",
               "repror":"박형신","isu_exctv_rgist_at":"비등기임원","isu_exctv_ofcps":"상무","isu_main_shrholdr":"-",
               "sp_stock_lmp_cnt":"761","sp_stock_lmp_irds_cnt":"-500",
               "sp_stock_lmp_rate":"0.00","sp_stock_lmp_irds_rate":"0.00"}]}
        """.trimIndent())

        with(client(port).fetch("00126380").single()) {
            assertThat(isRegistered).isFalse()
            assertThat(officerPosition).isEqualTo("상무")
            assertThat(ownedQty).isEqualTo(761L)
            assertThat(changeQty).isEqualTo(-500L)   // 콤마 제거 후 음수 파싱
            // 0.00은 무변동이지 결측이 아니다 — null로 접으면 안 된다
            assertThat(changeRate).isEqualByComparingTo(BigDecimal("0.00"))
        }
    }

    @Test
    fun `status 013은 빈 목록이다`() {
        // 공휴일·무자료 실측 응답
        val port = serving("""{"status":"013","message":"조회된 데이타가 없습니다."}""")

        assertThat(client(port).fetch("00000000")).isEmpty()
    }

    @Test
    fun `그 밖의 status는 예외다`() {
        val port = serving("""{"status":"020","message":"요청 제한을 초과하였습니다."}""")

        val thrown = catchThrowable { client(port).fetch("00126380") }

        assertThat(thrown).isInstanceOf(DartApiException::class.java)
        assertThat(thrown).hasMessageContaining("020")
    }

    @Test
    fun `인증키가 비면 호출하지 않고 예외를 던진다`() {
        // 아무도 듣지 않는 포트 — 호출이 나가면 연결 거부로 다른 예외가 된다
        val deadPort = deadPort()

        val thrown = catchThrowable { client(deadPort, key = "").fetch("00126380") }

        assertThat(thrown).isInstanceOf(DartApiException::class.java)
        assertThat(thrown).hasMessageContaining("DART_API_KEY")
    }

    @Test
    fun `쿼리에 인증키와 corp_code가 값으로 실린다`() {
        val port = serving("""{"status":"013","message":"조회된 데이타가 없습니다."}""")

        client(port).fetch("00828497")

        // 문자열 통째 비교는 파라미터 순서가 바뀌면 깨진다 — 값으로 파싱해 본다
        val q = queryOf(received.get())
        assertThat(q["crtfc_key"]).isEqualTo(API_KEY)
        assertThat(q["corp_code"]).isEqualTo("00828497")
    }

    @Test
    fun `예외 메시지에 인증키가 들어가지 않는다`() {
        val port = serving("""{"status":"020","message":"요청 제한을 초과하였습니다."}""")

        val thrown = catchThrowable { client(port).fetch("00126380") }!!

        assertThat(thrown.stackTraceToString()).doesNotContain(API_KEY)
        assertThat(thrown.cause).isNull()
    }

    /**
     * 공공기관 기본 오류 페이지가 실제로 하는 그대로 — 요청 URI를 쿼리까지 본문에 렌더링한다.
     * `WebClientResponseException.message`에는 요청 URI가 통째로 들어 있으므로, 그 메시지를
     * 그대로 실으면(`"...: ${e.message}"`) crtfc_key가 샌다 — 상태 코드만 남겨야 한다.
     */
    @Test
    fun `요청 URI를 되울리는 500 본문에서도 인증키가 새지 않는다`() {
        val port = serve { ex ->
            respond(ex, 500, "<html><body><b>Message</b> ${ex.requestURI}</body></html>")
        }

        val thrown = catchThrowable { client(port).fetch("00126380") }!!

        assertThat(thrown).isInstanceOf(DartApiException::class.java)
        assertThat(thrown).hasMessageContaining("500")
        assertNoSecretAnywhere(thrown)
    }

    /**
     * 본문이 JSON이 아니면(점검 안내 HTML 등) Jackson 예외가 원본 본문을 `[Source: (String)"..."]`로
     * 물고 나온다 — 그 본문에 되울려 온 요청 URI가 있으면 그대로 새므로 여기서 갈아끼운다.
     */
    @Test
    fun `요청 URI를 되울리는 비-JSON 200 본문에서도 인증키가 새지 않는다`() {
        val port = serve { ex ->
            respond(ex, 200, "<html><body>점검 중입니다 ${ex.requestURI}</body></html>")
        }

        val thrown = catchThrowable { client(port).fetch("00126380") }!!

        assertThat(thrown).isInstanceOf(DartApiException::class.java)
        assertNoSecretAnywhere(thrown)
    }

    /**
     * **유효한 키로** 죽은 포트를 때린다 — 빈 키를 넘기면 네트워크 계층에 닿기도 전에 막혀서
     * Reactor checkpoint 프레임 경로(요청 URI를 통째로 물고 있다)를 지나가지 않는다.
     * cause를 붙이지 않는다는 방어는 바로 이 경로를 위한 것이다.
     */
    @Test
    fun `연결 실패에서도 인증키가 새지 않는다`() {
        val thrown = catchThrowable { client(deadPort()).fetch("00126380") }!!

        assertThat(thrown).isInstanceOf(DartApiException::class.java)
        assertNoSecretAnywhere(thrown)
    }

    @Test
    fun `타임아웃도 인증키가 새지 않는다`() {
        // 핸들러 sleep을 길게 잡지 말 것 — HttpServer.stop()이 디스패처 스레드를 join하므로
        // tearDown이 그 시간을 통째로 기다린다. 타임아웃(300ms)보다 넉넉히 길기만 하면 된다.
        val port = serve { Thread.sleep(2_000) }
        val client = client(port).apply { timeout = Duration.ofMillis(300) }

        val thrown = catchThrowable { client.fetch("00126380") }!!

        assertThat(thrown).isInstanceOf(DartApiException::class.java)
        assertNoSecretAnywhere(thrown)
    }
}
