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
import java.time.Duration
import java.time.LocalDate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * OpenDART 전 종목 매핑 `corpCode.xml` 클라이언트.
 *
 * **`corpCode.xml`의 실제 응답 구조는 아직 호출해 본 적이 없다.** 여기 쓰인 `<result><list><corp_code>…`
 * 구조는 계획서(`docs/superpowers/plans/2026-08-18-dart-disclosure-backend.md` Task 9)의 가정이지
 * 실측이 아니다. 실측 확인은 배포 후 corp-map 워크플로 수동 실행에서 한다.
 *
 * 인증키는 `list.json`(`DartListClient`)과 같은 쿼리 파라미터(`crtfc_key=`) 방식이므로 같은 유출
 * 방어 셋을 그대로 검증한다 — 예외 어디에도 키도 오퍼레이션 경로 조각도 없어야 한다.
 */
class DartCorpCodeClientTest {

    private companion object { const val API_KEY = "SUPERSECRETCORPCODEKEY9876" }

    private var server: HttpServer? = null

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

    /** ZIP 하나를 200으로 돌려주는 루프백 스텁 */
    private fun serving(zip: ByteArray): Int = serve { ex -> respond(ex, 200, zip) }

    // dedicatedConnector를 쓰는 이유는 StubServerConnector.kt 주석에 있다 — 빼면 간헐적으로 깨진다
    private fun client(port: Int, key: String = API_KEY) = DartCorpCodeClient(
        DartProperties(apiKey = key, baseUrl = "http://localhost:$port"),
    ).apply { connector = dedicatedConnector() }

    /** 아무도 듣지 않는 포트. 여는 즉시 닫아 두므로 연결이 거부된다 */
    private fun deadPort(): Int = ServerSocket(0).use { it.localPort }

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
    fun `ZIP을 풀어 매핑을 읽는다`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <result>
              <list><corp_code>00126380</corp_code><corp_name>삼성전자</corp_name>
                    <stock_code>005930</stock_code><modify_date>20260814</modify_date></list>
              <list><corp_code>01888779</corp_code><corp_name>제이엠밸브</corp_name>
                    <stock_code> </stock_code><modify_date>20260701</modify_date></list>
            </result>
        """.trimIndent()

        val rows = DartCorpCodeClient.parseZip(zipOf(xml))

        assertThat(rows).hasSize(2)
        with(rows[0]) {
            assertThat(corpCode).isEqualTo("00126380")
            assertThat(stockCode).isEqualTo("005930")
            assertThat(modifyDate).isEqualTo(LocalDate.of(2026, 8, 14))
        }
        // 비상장은 공백으로 온다 — null로 정규화해야 부분 인덱스(WHERE stock_code IS NOT NULL)가 산다
        assertThat(rows[1].stockCode).isNull()
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

        val rows = DartCorpCodeClient.parseZip(zipOf(xml))

        assertThat(rows).hasSize(2)
        assertThat(rows[0].modifyDate).isNull()
        // 빈 값이 아니라 형식이 깨진 경우 — runCatching으로 감싸지 않으면 예외가 전체 파싱을 죽인다
        assertThat(rows[1].modifyDate).isNull()
    }

    @Test
    fun `corp_code가 없는 행은 버린다`() {
        val xml = """
            <result><list><corp_name>이름만있음</corp_name><stock_code>000001</stock_code></list></result>
        """.trimIndent()

        assertThat(DartCorpCodeClient.parseZip(zipOf(xml))).isEmpty()
    }

    @Test
    fun `정상 응답을 받아 파싱까지 마친다`() {
        val xml = """
            <result><list><corp_code>00126380</corp_code><corp_name>삼성전자</corp_name>
                    <stock_code>005930</stock_code><modify_date>20260814</modify_date></list></result>
        """.trimIndent()
        val port = serving(zipOf(xml))

        val rows = client(port).fetch()

        assertThat(rows).hasSize(1)
        assertThat(rows.first().corpCode).isEqualTo("00126380")
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

    /** ZIP이 아닌(깨진) 바이트가 오면 ZipInputStream/DOM 파서가 실패한다 — 그 실패 경로에서도 유출이 없어야 한다 */
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

        val rows = client(port).fetch()

        assertThat(rows).hasSize(1)
        assertThat(rows.first().corpName).hasSize(300_000)
    }
}
