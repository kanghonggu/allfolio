package com.allfolio.dart.corp

import com.allfolio.dart.DartApiException
import com.allfolio.dart.DartProperties
import com.allfolio.test.dedicatedConnector
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * OpenDART 전 종목 매핑 `corpCode.xml` 클라이언트.
 *
 * 응답 구조(`<result><list><corp_code>…`)와 태그 5종(`corp_code`·`corp_eng_name`·`corp_name`·
 * `modify_date`·`stock_code`), ZIP 엔트리명(`CORPCODE.xml`, 대문자)은 전부 2026-08-18 실제
 * 호출로 확인된 값이다 — 계획서의 "미검증 가정"은 이제 실측으로 대체됐다. 실측: ZIP
 * 3,596,918 bytes(3.4MB) → 해제 30,059,956 bytes(28.7MB), 118,712행, 그중 `stock_code`
 * 있음(상장) 3,983행(3.4%)·공백(비상장) 114,729행(96.6%, 빈 문자열이 아니라 공백 한 칸),
 * `modify_date` 파싱 불가 0건. 자세한 근거는 [DartCorpCodeClient] KDoc.
 *
 * 인증키는 `list.json`(`DartListClient`)과 같은 쿼리 파라미터(`crtfc_key=`) 방식이므로 같은 유출
 * 방어 셋을 그대로 검증한다 — 예외 어디에도 키도 오퍼레이션 경로 조각도 없어야 한다.
 */
class DartCorpCodeClientTest {

    private companion object { const val API_KEY = "SUPERSECRETCORPCODEKEY9876" }

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

    private fun respond(exchange: HttpExchange, status: Int, body: ByteArray) {
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) =
        respond(exchange, status, body.toByteArray())

    /** ZIP 하나를 200으로 돌려주는 루프백 스텁. 요청 쿼리를 [received]에 남긴다 */
    private fun serving(zip: ByteArray): Int = serve { ex ->
        received.set(ex.requestURI.rawQuery)
        respond(ex, 200, zip)
    }

    // dedicatedConnector를 쓰는 이유는 StubServerConnector.kt 주석에 있다 — 빼면 간헐적으로 깨진다
    private fun client(port: Int, key: String = API_KEY) = DartCorpCodeClient(
        DartProperties(apiKey = key, baseUrl = "http://localhost:$port"),
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
        assertThat(dump).doesNotContain(DartCorpCodeClient.PATH)
    }

    /** DEFLATE 압축 ZIP(테스트 데이터가 작을 때 쓴다) */
    private fun zipOf(xml: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("CORPCODE.xml"))
            zip.write(xml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    /**
     * STORED(무압축) ZIP. 압축 없이 원본 바이트 수 그대로 담기므로 "응답이 256KB(WebClient 기본
     * 버퍼 상한)를 넘는다"를 압축률에 흔들리지 않고 결정적으로 재현할 수 있다.
     */
    private fun zipOfStored(xml: String): ByteArray {
        val data = xml.toByteArray(Charsets.UTF_8)
        val checksum = CRC32().apply { update(data) }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val entry = ZipEntry("CORPCODE.xml").apply {
                method = ZipEntry.STORED
                size = data.size.toLong()
                compressedSize = data.size.toLong()
                crc = checksum.value
            }
            zip.putNextEntry(entry)
            zip.write(data)
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `ZIP을 풀어 상장사만 남긴다`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <result>
              <list><corp_code>00126380</corp_code><corp_name>삼성전자</corp_name>
                    <stock_code>005930</stock_code><modify_date>20260814</modify_date></list>
              <list><corp_code>01888779</corp_code><corp_name>제이엠밸브</corp_name>
                    <stock_code> </stock_code><modify_date>20260701</modify_date></list>
            </result>
        """.trimIndent()

        val result = DartCorpCodeClient.parseZip(zipOf(xml))

        // 실측대로 총 스캔 행수(2)와 실제 적재 대상(1, 비상장 걸러진 뒤)이 다르다
        assertThat(result.totalRows).isEqualTo(2)
        assertThat(result.listedRows).hasSize(1)
        with(result.listedRows.single()) {
            assertThat(corpCode).isEqualTo("00126380")
            assertThat(stockCode).isEqualTo("005930")
            assertThat(modifyDate).isEqualTo(LocalDate.of(2026, 8, 14))
        }
    }

    @Test
    fun `modify_date가 없거나 이상하면 null로 둔다`() {
        val xml = """
            <result>
              <list><corp_code>00000001</corp_code><corp_name>테스트빈값</corp_name>
                    <stock_code>000001</stock_code><modify_date></modify_date></list>
              <list><corp_code>00000002</corp_code><corp_name>테스트이상값</corp_name>
                    <stock_code>000002</stock_code><modify_date>이상한날짜</modify_date></list>
            </result>
        """.trimIndent()

        val rows = DartCorpCodeClient.parseZip(zipOf(xml)).listedRows

        assertThat(rows).hasSize(2)
        assertThat(rows[0].modifyDate).isNull()
        // 빈 값이 아니라 형식이 깨진 경우 — runCatching으로 감싸지 않으면 예외가 전체 파싱을 죽인다
        assertThat(rows[1].modifyDate).isNull()
    }

    @Test
    fun `corp_code 태그 자체가 없는 행은 버린다`() {
        val xml = """
            <result><list><corp_name>이름만있음</corp_name><stock_code>000001</stock_code></list></result>
        """.trimIndent()

        val result = DartCorpCodeClient.parseZip(zipOf(xml))

        assertThat(result.totalRows).isZero()
        assertThat(result.listedRows).isEmpty()
    }

    @Test
    fun `corp_code 태그는 있지만 공백뿐인 행도 버린다`() {
        // "태그가 아예 없음"과는 다른 경로다 — 빈 문자열/공백 정규화(takeIf isNotBlank)가
        // 빠지면 이 케이스만 새는데, "태그 자체가 없는" 테스트는 그 결함을 못 잡는다.
        val xml = """
            <result><list><corp_code> </corp_code><corp_name>공백코드</corp_name>
                    <stock_code>000001</stock_code></list></result>
        """.trimIndent()

        val result = DartCorpCodeClient.parseZip(zipOf(xml))

        assertThat(result.totalRows).isZero()
        assertThat(result.listedRows).isEmpty()
    }

    @Test
    fun `stock_code가 공백뿐이면 비상장으로 걸러진다`() {
        // 실측 114,729건(96.6%)이 이 형태다 — 빈 문자열이 아니라 공백 한 칸.
        val xml = """
            <result><list><corp_code>01888779</corp_code><corp_name>제이엠밸브</corp_name>
                    <stock_code> </stock_code><modify_date>20260701</modify_date></list></result>
        """.trimIndent()

        val result = DartCorpCodeClient.parseZip(zipOf(xml))

        // corp_code는 유효하므로 totalRows에는 잡히지만, 비상장이라 적재 대상엔 안 남는다
        assertThat(result.totalRows).isEqualTo(1)
        assertThat(result.listedRows).isEmpty()
    }

    /** DOCTYPE 자체를 거부하는지 본다 — SUPPORT_DTD=false가 빠지면 내부 엔티티가 조용히 치환된다 */
    @Test
    fun `DOCTYPE이 있는 XML은 XXE 방지로 거부된다`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE result [<!ENTITY x "치환됨">]>
            <result><list><corp_code>00000001</corp_code><corp_name>&x;</corp_name>
                    <stock_code>000001</stock_code></list></result>
        """.trimIndent()

        val thrown = catchThrowable { DartCorpCodeClient.parseZip(zipOf(xml)) }

        assertThat(thrown).isNotNull()
    }

    @Test
    fun `정상 응답을 받아 파싱까지 마친다`() {
        val xml = """
            <result><list><corp_code>00126380</corp_code><corp_name>삼성전자</corp_name>
                    <stock_code>005930</stock_code><modify_date>20260814</modify_date></list></result>
        """.trimIndent()
        val port = serving(zipOf(xml))

        val result = client(port).fetch()

        assertThat(result.totalRows).isEqualTo(1)
        assertThat(result.listedRows).hasSize(1)
        assertThat(result.listedRows.first().corpCode).isEqualTo("00126380")
    }

    @Test
    fun `쿼리에 인증키가 값으로 실린다`() {
        val xml = """<result></result>"""
        val port = serving(zipOf(xml))

        client(port).fetch()

        val q = queryOf(received.get())
        assertThat(q["crtfc_key"]).isEqualTo(API_KEY)
    }

    @Test
    fun `인증키가 비면 호출하지 않고 예외를 던진다`() {
        // 아무도 듣지 않는 포트 — 호출이 나가면 연결 거부로 다른 예외가 된다
        val deadPort = deadPort()

        val thrown = catchThrowable { client(deadPort, key = "").fetch() }

        assertThat(thrown).isInstanceOf(DartApiException::class.java)
        assertThat(thrown).hasMessageContaining("DART_API_KEY")
    }

    /**
     * 공공기관 기본 오류 페이지가 실제로 하는 그대로 — 요청 URI를 쿼리까지 본문에 렌더링한다.
     * `WebClientResponseException.message`에는 요청 URI가 통째로 들어 있으므로, 그 메시지를
     * 그대로 실으면 crtfc_key가 샌다 — 상태 코드만 남겨야 한다.
     */
    @Test
    fun `요청 URI를 되울리는 500 본문에서도 인증키가 새지 않는다`() {
        val port = serve { ex ->
            respond(ex, 500, "<html><body><b>Message</b> ${ex.requestURI}</body></html>")
        }

        val thrown = catchThrowable { client(port).fetch() }!!

        assertThat(thrown).isInstanceOf(DartApiException::class.java)
        assertThat(thrown).hasMessageContaining("500")
        assertNoSecretAnywhere(thrown)
    }

    /** ZIP이 아닌(깨진) 바이트가 오면 ZipInputStream/StAX 파서가 실패한다 — 그 실패 경로에서도 유출이 없어야 한다 */
    @Test
    fun `ZIP이 아닌 응답 본문에서도 인증키가 새지 않는다`() {
        val port = serve { ex ->
            respond(ex, 200, "<html><body>점검 중입니다 ${ex.requestURI}</body></html>")
        }

        val thrown = catchThrowable { client(port).fetch() }!!

        assertThat(thrown).isInstanceOf(DartApiException::class.java)
        assertNoSecretAnywhere(thrown)
    }

    /**
     * **유효한 키로** 죽은 포트를 때린다 — 빈 키를 넘기면 네트워크 계층에 닿기도 전에 막혀서
     * Reactor checkpoint 프레임 경로(요청 URI를 통째로 물고 있다)를 지나가지 않는다.
     */
    @Test
    fun `연결 실패에서도 인증키가 새지 않는다`() {
        val thrown = catchThrowable { client(deadPort()).fetch() }!!

        assertThat(thrown).isInstanceOf(DartApiException::class.java)
        assertNoSecretAnywhere(thrown)
    }

    @Test
    fun `타임아웃에서도 인증키가 새지 않는다`() {
        // 핸들러 sleep을 길게 잡지 말 것 — HttpServer.stop()이 디스패처 스레드를 join하므로
        // tearDown이 그 시간을 통째로 기다린다. 타임아웃(300ms)보다 넉넉히 길기만 하면 된다.
        val port = serve { Thread.sleep(2_000) }
        val client = client(port).apply { timeout = Duration.ofMillis(300) }

        val thrown = catchThrowable { client.fetch() }!!

        assertThat(thrown).isInstanceOf(DartApiException::class.java)
        assertNoSecretAnywhere(thrown)
    }

    /**
     * 전 종목 매핑이라 응답이 수 MB다. WebClient 기본 in-memory 버퍼 상한(256KB)을 올리지
     * 않으면 이 크기의 응답에서 `DataBufferLimitException`이 난다.
     */
    @Test
    fun `버퍼 상한을 올려 256KB를 넘는 응답도 받는다`() {
        // STORED(무압축)라서 xml 바이트 수가 곧 응답 바이트 수다 — 압축률에 기대지 않는다
        val bigName = "A".repeat(300_000)
        val xml = """
            <result><list><corp_code>00000001</corp_code><corp_name>$bigName</corp_name>
                    <stock_code>000001</stock_code><modify_date>20260814</modify_date></list></result>
        """.trimIndent()
        val zip = zipOfStored(xml)
        assertThat(zip.size).isGreaterThan(256 * 1024)
        val port = serving(zip)

        val result = client(port).fetch()

        assertThat(result.listedRows).hasSize(1)
        assertThat(result.listedRows.first().corpName).hasSize(300_000)
    }
}
