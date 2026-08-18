package com.allfolio.dart.list

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
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

/**
 * OpenDART 공시검색 `list.json` 클라이언트.
 *
 * 응답 본문은 전부 2026-08-11~08-18(6영업일 8,667건) 실측 원문에서 잘라 왔다 — 값을 지어내지 않았다.
 * 인증키는 **쿼리 파라미터**(`crtfc_key=`)에 실린다 — `FredApiClient`·`FscCommodityClient`와
 * 같은 그물을 친다: 예외 어디에도 키도 오퍼레이션 경로 조각도 없어야 한다.
 */
class DartListClientTest {

    private companion object { const val API_KEY = "SUPERSECRETDARTKEY1234" }

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
    private fun client(port: Int, key: String = API_KEY) = DartListClient(
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
        assertThat(dump).doesNotContain(DartListClient.PATH)
    }

    @Test
    fun `정상 응답을 파싱한다`() {
        val port = serving("""
            {"status":"000","message":"정상","page_no":1,"page_count":10,"total_count":95,"total_page":10,
             "list":[{"corp_code":"00152880","corp_name":"코오롱글로벌","stock_code":"003070","corp_cls":"Y",
                      "report_nm":"단일판매ㆍ공급계약체결              ","rcept_no":"20260818800172",
                      "flr_nm":"코오롱글로벌","rcept_dt":"20260818","rm":"유"}]}
        """.trimIndent())

        val page = client(port).fetchPage(LocalDate.of(2026, 8, 18), LocalDate.of(2026, 8, 18), 1)

        assertThat(page.totalPage).isEqualTo(10)
        assertThat(page.emptyResult).isFalse()
        assertThat(page.rows).hasSize(1)
        with(page.rows.first()) {
            assertThat(rceptNo).isEqualTo("20260818800172")
            assertThat(corpCode).isEqualTo("00152880")
            assertThat(corpName).isEqualTo("코오롱글로벌")
            assertThat(stockCode).isEqualTo("003070")
            assertThat(corpCls).isEqualTo("Y")
            assertThat(flrNm).isEqualTo("코오롱글로벌")
            assertThat(rm).isEqualTo("유")
            assertThat(rceptDt).isEqualTo(LocalDate.of(2026, 8, 18))
            // trim만 한다 — 정규화(DartReportName)는 Task 8 수집 서비스의 몫이다. 원문의 U+318D(ㆍ)도 그대로 남는다
            assertThat(reportNm).isEqualTo("단일판매ㆍ공급계약체결")
        }
    }

    @Test
    fun `stock_code 빈 문자열은 null이 된다`() {
        // 실측 3,273건이 이 형태다(전부 corp_cls=E). NULL로 안 바꾸면 부분 인덱스가 죽는다.
        val port = serving("""
            {"status":"000","total_page":1,
             "list":[{"corp_code":"01888779","corp_name":"제이엠밸브","stock_code":"","corp_cls":"E",
                      "report_nm":"감사보고서 (2025.12)","rcept_no":"20260818000094",
                      "flr_nm":"모두공인회계사감사반(제547호)","rcept_dt":"20260818","rm":""}]}
        """.trimIndent())

        val page = client(port).fetchPage(LocalDate.of(2026, 8, 18), LocalDate.of(2026, 8, 18), 1)

        assertThat(page.rows.first().stockCode).isNull()
        assertThat(page.rows.first().rm).isNull()
    }

    @Test
    fun `status 013은 실패가 아니라 빈 결과다`() {
        // 공휴일 응답. 2026-08-17(광복절 대체공휴일)이 이것이었다.
        // 실패로 다루면 대체공휴일마다 배치가 빨갛게 된다.
        val port = serving("""{"status":"013","message":"조회된 데이타가 없습니다."}""")

        val page = client(port).fetchPage(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 17), 1)

        assertThat(page.rows).isEmpty()
        assertThat(page.totalPage).isZero()
        assertThat(page.emptyResult).isTrue()
    }

    @Test
    fun `그 밖의 status는 예외다`() {
        val port = serving("""{"status":"020","message":"요청 제한을 초과하였습니다."}""")

        val thrown = catchThrowable {
            client(port).fetchPage(LocalDate.of(2026, 8, 18), LocalDate.of(2026, 8, 18), 1)
        }

        assertThat(thrown).isInstanceOf(DartApiException::class.java)
        assertThat(thrown).hasMessageContaining("020")
    }

    @Test
    fun `인증키가 비면 호출하지 않고 예외를 던진다`() {
        // 아무도 듣지 않는 포트 — 호출이 나가면 연결 거부로 다른 예외가 된다
        val deadPort = deadPort()

        val thrown = catchThrowable {
            client(deadPort, key = "").fetchPage(LocalDate.of(2026, 8, 18), LocalDate.of(2026, 8, 18), 1)
        }

        assertThat(thrown).isInstanceOf(DartApiException::class.java)
        assertThat(thrown).hasMessageContaining("DART_API_KEY")
    }

    @Test
    fun `예외 메시지에 인증키가 들어가지 않는다`() {
        // 이 메시지는 어드민 응답과 GitHub Actions 주석까지 나간다.
        val port = serving("""{"status":"020","message":"요청 제한을 초과하였습니다."}""")

        val thrown = catchThrowable {
            client(port).fetchPage(LocalDate.of(2026, 8, 18), LocalDate.of(2026, 8, 18), 1)
        }!!

        assertThat(thrown.stackTraceToString()).doesNotContain(API_KEY)
        assertThat(thrown.cause).isNull()
    }

    @Test
    fun `요청에 날짜와 페이지가 값으로 실린다`() {
        val port = serving("""{"status":"013","message":"조회된 데이타가 없습니다."}""")

        client(port).fetchPage(LocalDate.of(2026, 8, 17), LocalDate.of(2026, 8, 18), 3)

        // 문자열 통째 비교는 파라미터 순서가 바뀌면 깨진다 — 값으로 파싱해 본다
        val q = queryOf(received.get())
        assertThat(q["bgn_de"]).isEqualTo("20260817")
        assertThat(q["end_de"]).isEqualTo("20260818")
        assertThat(q["page_no"]).isEqualTo("3")
        assertThat(q["page_count"]).isEqualTo("100")
        assertThat(q["crtfc_key"]).isEqualTo(API_KEY)
    }

    @Test
    fun `rcept_no나 rcept_dt가 깨진 행은 버리고 나머지는 살린다`() {
        // 실측 8,667건 중 이런 행은 0건이다 — 방어적 케이스. 한 행이 깨졌다고 페이지 전체를
        // 버리면 그날 공시가 통째로 안 잡힌다.
        val port = serving("""
            {"status":"000","total_page":1,
             "list":[
               {"corp_code":"00152880","corp_name":"코오롱글로벌","stock_code":"003070","corp_cls":"Y",
                "report_nm":"단일판매ㆍ공급계약체결","rcept_no":"","flr_nm":"코오롱글로벌",
                "rcept_dt":"20260818","rm":"유"},
               {"corp_code":"00166227","corp_name":"화승인더스트리","stock_code":"006060","corp_cls":"Y",
                "report_nm":"투자판단관련주요경영사항","rcept_no":"20260811801012","flr_nm":"화승인더스트리",
                "rcept_dt":"2026-08-11","rm":"유"},
               {"corp_code":"00828497","corp_name":"한미약품","stock_code":"128940","corp_cls":"Y",
                "report_nm":"임원ㆍ주요주주특정증권등소유상황보고서","rcept_no":"20260811000698","flr_nm":"황상연",
                "rcept_dt":"20260811","rm":""}
             ]}
        """.trimIndent())

        val page = client(port).fetchPage(LocalDate.of(2026, 8, 11), LocalDate.of(2026, 8, 18), 1)

        assertThat(page.rows).hasSize(1)
        assertThat(page.rows.first().rceptNo).isEqualTo("20260811000698")
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

        val thrown = catchThrowable {
            client(port).fetchPage(LocalDate.of(2026, 8, 18), LocalDate.of(2026, 8, 18), 1)
        }!!

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

        val thrown = catchThrowable {
            client(port).fetchPage(LocalDate.of(2026, 8, 18), LocalDate.of(2026, 8, 18), 1)
        }!!

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
        val thrown = catchThrowable {
            client(deadPort()).fetchPage(LocalDate.of(2026, 8, 18), LocalDate.of(2026, 8, 18), 1)
        }!!

        assertThat(thrown).isInstanceOf(DartApiException::class.java)
        assertNoSecretAnywhere(thrown)
    }

    @Test
    fun `타임아웃도 인증키가 새지 않는다`() {
        // 핸들러 sleep을 길게 잡지 말 것 — HttpServer.stop()이 디스패처 스레드를 join하므로
        // tearDown이 그 시간을 통째로 기다린다. 타임아웃(300ms)보다 넉넉히 길기만 하면 된다.
        val port = serve { Thread.sleep(2_000) }
        val client = client(port).apply { timeout = Duration.ofMillis(300) }

        val thrown = catchThrowable {
            client.fetchPage(LocalDate.of(2026, 8, 18), LocalDate.of(2026, 8, 18), 1)
        }!!

        assertThat(thrown).isInstanceOf(DartApiException::class.java)
        assertNoSecretAnywhere(thrown)
    }
}
