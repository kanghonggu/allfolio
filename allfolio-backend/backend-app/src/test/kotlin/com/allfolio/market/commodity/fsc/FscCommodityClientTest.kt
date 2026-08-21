package com.allfolio.market.commodity.fsc

import com.allfolio.market.fsc.FscApiException
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
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference

/**
 * 금시세(`getGoldPriceInfo`) 클라이언트.
 *
 * 픽스처는 **2026-08-17에 실제로 호출해 받은 응답 그대로**다(공개 시세라 값을 그대로 적는다).
 * 여기서 단언이 깨지면 우리가 형식을 잘못 읽었거나 상류가 바꾼 것이지, 테스트가 낡은 게 아니다.
 *
 * 인증키는 **쿼리 파라미터**(`serviceKey=`)에 실린다 — `FredApiClientTest`와 같은 그물을 친다:
 * 예외 어디에도 키가 없어야 하고, 요청 경로 조각도 없어야 한다(보이면 그 뒤 쿼리도 곧 보인다).
 */
class FscCommodityClientTest {

    private companion object {
        const val API_KEY = "SUPERSECRETFSCKEY1234"

        val FROM: LocalDate = LocalDate.of(2026, 8, 5)
        val TO: LocalDate = LocalDate.of(2026, 8, 13)

        /** 금 99.99_1kg — 우리가 쓰는 종목 */
        const val GOLD_1KG = "04020000"

        /** 미니금 99.99_100g — 같은 응답에 섞여 오는 남의 종목 */
        const val MINI_GOLD = "04020100"

        /**
         * 실측 응답(2026-08-17). 두 종목이 같은 날짜로 함께 온다는 것이 이 픽스처의 요점이다.
         * `fltRt`가 `.05`·`-.19`처럼 **앞의 0 없이** 온다는 것도 여기 고정돼 있다.
         */
        val REAL_BODY = """
            {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},
            "body":{"numOfRows":100,"pageNo":1,"totalCount":3,"items":{"item":[
            {"basDt":"20260813","srtnCd":"04020000","isinCd":"KRD040200002","itmsNm":"금 99.99_1kg","clpr":"200570","vs":"100","fltRt":".05","mkp":"200800","hipr":"202250","lopr":"199750","trqu":"250785","trPrc":"50450411440"},
            {"basDt":"20260813","srtnCd":"04020100","isinCd":"KRD040201000","itmsNm":"미니금 99.99_100g","clpr":"200240","vs":"-380","fltRt":"-.19","mkp":"202630","hipr":"202640","lopr":"199940","trqu":"9674","trPrc":"1946526180"},
            {"basDt":"20260812","srtnCd":"04020000","isinCd":"KRD040200002","itmsNm":"금 99.99_1kg","clpr":"200470","vs":"740","fltRt":".37","mkp":"199730","hipr":"201400","lopr":"198950","trqu":"225792","trPrc":"45208926220"}
            ]}}}}
        """.trimIndent()
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

    /** 본문 하나를 200으로 돌려주는 스텁 */
    private fun serving(body: String): Int = serve { respond(it, 200, body) }

    // 커넥터를 dedicatedConnector로 두는 이유는 그쪽 주석에 있다 — 빼면 간헐적으로 깨진다.
    private fun client(port: Int, key: String = API_KEY) = FscCommodityClient(
        apiKey = key,
        baseUrl = "http://localhost:$port",
        objectMapper = ObjectMapper(),
    ).apply { connector = dedicatedConnector() }

    private fun fetch(port: Int) = client(port).fetchGoldPrices(FROM, TO)

    /** 아무도 듣지 않는 포트. 여는 즉시 닫아 두므로 연결이 거부된다. */
    private fun deadPort(): Int = ServerSocket(0).use { it.localPort }

    /** 예외 전체(메시지 + cause 체인 + suppressed + 모든 스택프레임)에 비밀이 없는지 본다. */
    private fun assertNoSecretAnywhere(t: Throwable) {
        val dump = t.stackTraceToString()
        assertThat(dump).doesNotContain(API_KEY)
        assertThat(dump).doesNotContain(FscCommodityClient.PATH)
    }

    private fun queryOf(rawQuery: String): Map<String, String> =
        rawQuery.split("&").associate { pair ->
            val (name, value) = pair.split("=", limit = 2)
            URLDecoder.decode(name, "UTF-8") to URLDecoder.decode(value, "UTF-8")
        }

    /**
     * 쿼리에 실린 날짜를 **값으로** 되읽는다. 문자열로 비교하면 경계가 하루 밀린 것과
     * 형식이 바뀐 것이 같은 모양으로 깨져 무엇이 틀렸는지 안 보인다 —
     * 형식(`yyyyMMdd`)은 위 테스트가 따로 못박는다.
     */
    private fun dateOf(query: Map<String, String>, name: String): LocalDate =
        LocalDate.parse(query.getValue(name), DateTimeFormatter.ofPattern("yyyyMMdd"))

    /** 요청 쿼리를 잡아 돌려주는 스텁. 응답은 실측 본문 그대로 */
    private fun capturedQuery(call: (FscCommodityClient) -> Unit): Map<String, String> {
        val seen = AtomicReference<String>()
        val port = serve { ex ->
            seen.set(ex.requestURI.rawQuery)
            respond(ex, 200, REAL_BODY)
        }
        call(client(port))
        return queryOf(seen.get())
    }

    /**
     * 파라미터 이름·날짜 형식은 2026-08-17 실측으로 확정한 것이다.
     * **`yyyyMMdd`가 여기 고정돼 있다** — FRED의 ISO(`yyyy-MM-dd`)로 맞추려는 시도가
     * 조용히 0건이 되는 대신 여기서 깨져야 한다.
     */
    @Test
    fun `요청 경로와 쿼리가 실측한 형태와 일치한다`() {
        // 쿼리를 문자열 하나로 단언하면 파라미터 순서·인코딩 방식에 묶여 깨진다 — 값으로 파싱해 본다.
        val seenPath = AtomicReference<String>()
        val seenQuery = AtomicReference<String>()
        val port = serve { ex ->
            seenPath.set(ex.requestURI.path)
            seenQuery.set(ex.requestURI.rawQuery)
            respond(ex, 200, REAL_BODY)
        }

        client(port).fetchGoldPrices(FROM, TO)

        assertThat(seenPath.get()).isEqualTo(FscCommodityClient.PATH)
        assertThat(queryOf(seenQuery.get()))
            .containsEntry("serviceKey", API_KEY)
            .containsEntry("resultType", "json")
            .containsEntry("pageNo", "1")
            .containsEntry("beginBasDt", "20260805")
            // 하루 뒤다. 이유는 아래 `endBasDt` 테스트에 있다 — 포털의 끝일은 배타적이다
            .containsEntry("endBasDt", "20260814")
            .containsKey("numOfRows")
    }

    /**
     * **포털의 `endBasDt`는 배타적이다 — `basDt < endBasDt`로 검색한다.**
     * 그래서 `to`를 그대로 실으면 **`to` 당일 행이 조용히 빠진다.**
     *
     * 2026-08-21 실측(`getGoldPriceInfo`, 실키):
     *
     * | 요청 | 응답 |
     * |---|---|
     * | `beginBasDt=20260813&endBasDt=20260813` | `totalCount=0` (8/13 시세는 존재한다) |
     * | `beginBasDt=20260813&endBasDt=20260814` | 8/13만 |
     * | `beginBasDt=20260813&endBasDt=20260819` | 8/13·8/14·8/18 (8/19 없음) |
     *
     * 포털 명세도 같은 말을 한다: `endBasDt`는 "기준일자가 검색값보다 **작은** 데이터를 검색".
     * `beginBasDt`는 반대로 포함이다(위 첫 행이 8/13을 준다).
     *
     * **증상이 안 보이던 이유**는 수집 창이 14일로 겹쳐 돌기 때문이다 — 빠진 하루를 다음 실행이
     * 메워서 "하루 늦게 들어온다"로만 보였다. 하루짜리 구간(`from == to`)으로 부르면 0건이 된다.
     *
     * 호출부([FscCommoditySource]·`CommodityCollectService`)는 `from..to`를 **포함**으로 잡고
     * 받은 행도 `in from..to`로 거른다. 그러니 배타성은 포털 사정으로 여기서 끝내고,
     * 클라이언트의 계약은 KDoc대로 포함으로 남긴다.
     */
    @Test
    fun `endBasDt가 배타적이라 to 다음 날을 싣는다 - 그래야 to 당일이 들어온다`() {
        val query = capturedQuery { it.fetchGoldPrices(FROM, TO) }

        assertThat(dateOf(query, "beginBasDt")).isEqualTo(FROM)
        assertThat(dateOf(query, "endBasDt")).isEqualTo(TO.plusDays(1))
    }

    /**
     * 하루짜리 구간이 이 버그가 가장 크게 드러나는 자리다 — 배타적 끝일을 그대로 보내면
     * `begin == end`가 되어 **항상 0건**이다(위 실측 첫 행). 겹치는 창이 없는 호출,
     * 예컨대 어드민이 특정 하루를 다시 채우는 백필이 여기 걸린다.
     */
    @Test
    fun `하루짜리 구간도 그 하루를 포함한다`() {
        val day = LocalDate.of(2026, 8, 13)

        val query = capturedQuery { it.fetchGoldPrices(day, day) }

        assertThat(dateOf(query, "beginBasDt")).isEqualTo(day)
        assertThat(dateOf(query, "endBasDt")).isEqualTo(day.plusDays(1))
    }

    /**
     * **`basDt`는 `yyyyMMdd`다.** 그리고 종가는 원/g이라 20만 원대가 정상이다 —
     * 5만이면 원/돈, 2억이면 원/kg으로 읽은 것이다.
     */
    @Test
    fun `basDt를 LocalDate로 읽고 종가를 그대로 담는다`() {
        val fetched = fetch(serving(REAL_BODY))

        assertThat(fetched.rows.map { it.quoteDate }).containsExactly(
            LocalDate.of(2026, 8, 13),
            LocalDate.of(2026, 8, 13),
            LocalDate.of(2026, 8, 12),
        )
        assertThat(fetched.rows[0].price).isEqualByComparingTo("200570")
        assertThat(fetched.skipped).isZero()
    }

    /**
     * **클라이언트는 종목을 가리지 않는다.** 고르는 일은 [FscCommoditySource]가 설정을 보고 한다 —
     * 그러려면 행마다 `srtnCd`가 붙어 있어야 한다. 여기서 미리 걸러 버리면 소스가 무엇을
     * 받았는지 알 수 없고, 종목 선택이 코드에 박혀 설정으로 못 바꾼다.
     */
    @Test
    fun `두 종목을 가리지 않고 srtnCd와 함께 돌려준다`() {
        val fetched = fetch(serving(REAL_BODY))

        assertThat(fetched.rows.map { it.srtnCd })
            .containsExactly(GOLD_1KG, MINI_GOLD, GOLD_1KG)
    }

    /**
     * **`fltRt`·`vs`는 앞의 0이 없는 소수로 온다**(`.05` · `-.19`).
     *
     * 이 두 필드는 지금 아무도 안 쓴다(포트가 못 싣는다 — `FscGoldRow` KDoc 참조).
     * 그래서 이 단언이 지키는 것은 **"나중에 전일대비를 소스 값으로 바꿀 때 형식을 다시
     * 조사하지 않아도 된다"** 하나다. 그 형식이 자명하지 않다는 게 요점이라 값을 그대로 적는다.
     */
    @Test
    fun `앞의 0이 없는 소수 등락률을 읽는다`() {
        val rows = fetch(serving(REAL_BODY)).rows

        assertThat(rows[0].changeRate).isEqualByComparingTo("0.05")
        assertThat(rows[1].changeRate).isEqualByComparingTo("-0.19")
        assertThat(rows[2].changeRate).isEqualByComparingTo("0.37")
        assertThat(rows[0].changeValue).isEqualByComparingTo("100")
        assertThat(rows[1].changeValue).isEqualByComparingTo("-380")
    }

    /**
     * **`totalCount=0`이면 `items`가 빈 문자열로 온다** — 공공데이터포털에 흔한 모양이다.
     * DTO로 바인딩했으면 여기서 Jackson이 터지고 "휴장이라 0건"이 "상류 장애"로 둔갑한다.
     */
    @Test
    fun `items가 배열이 아니면 빈 목록이다`() {
        val body = """
            {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},
            "body":{"numOfRows":100,"pageNo":1,"totalCount":0,"items":""}}}
        """.trimIndent()

        val fetched = fetch(serving(body))

        assertThat(fetched.rows).isEmpty()
        assertThat(fetched.skipped).isZero()
    }

    /** `item`이 객체 하나로 오는 판본도 같은 자리에서 흡수한다 — 안 하면 1건짜리 날이 0건이 된다 */
    @Test
    fun `item이 객체 하나여도 읽는다`() {
        val body = """
            {"response":{"header":{"resultCode":"00"},"body":{"totalCount":1,"items":{"item":
            {"basDt":"20260813","srtnCd":"04020000","clpr":"200570","vs":"100","fltRt":".05"}}}}}
        """.trimIndent()

        val fetched = fetch(serving(body))

        assertThat(fetched.rows).singleElement().satisfies({
            assertThat(it.quoteDate).isEqualTo(LocalDate.of(2026, 8, 13))
            assertThat(it.price).isEqualByComparingTo("200570")
        })
    }

    /** 값·날짜가 이상한 행은 버리고 센다. 0이 아닌 skipped가 "형식이 바뀌었다"는 신호가 된다 */
    @Test
    fun `날짜나 종가가 이상한 행은 건너뛰고 센다`() {
        val body = """
            {"response":{"header":{"resultCode":"00"},"body":{"totalCount":3,"items":{"item":[
            {"basDt":"2026-08-13","srtnCd":"04020000","clpr":"200570"},
            {"basDt":"20260812","srtnCd":"04020000","clpr":"-"},
            {"basDt":"20260811","srtnCd":"04020000","clpr":"199000"}
            ]}}}}
        """.trimIndent()

        val fetched = fetch(serving(body))

        assertThat(fetched.rows).singleElement()
            .satisfies({ assertThat(it.quoteDate).isEqualTo(LocalDate.of(2026, 8, 11)) })
        assertThat(fetched.skipped).isEqualTo(2)
    }

    /**
     * **HTTP 200에 실려 오는 실패가 있다.** 등록되지 않은 키·트래픽 초과가 그렇고 items는 비어 있다 —
     * 코드를 안 보면 "그 구간에 시세가 없다"와 구별할 수 없어, 잡은 초록인데 금만 영원히 안 쌓인다.
     */
    @Test
    fun `resultCode가 00이 아니면 0건이 아니라 실패다`() {
        val body = """
            {"response":{"header":{"resultCode":"30","resultMsg":"SERVICE KEY IS NOT REGISTERED ERROR."},
            "body":{"totalCount":0,"items":""}}}
        """.trimIndent()

        val raw = catchThrowable { fetch(serving(body)) }

        assertThat(raw).isInstanceOf(FscApiException::class.java)
        assertThat((raw as FscApiException).code).isEqualTo("RESULT-30")
        // resultMsg는 싣지 않는다 — 서버가 만든 문자열이라 요청 URL이 되울려 올 수 있다
        assertThat(raw.message).doesNotContain("SERVICE KEY")
    }

    /**
     * 페이지를 하나만 받으므로, 잘린 응답을 통과시키면 뒷날짜가 통째로 빠진 것이
     * "그 기간엔 시세가 없었다"로 보인다. 조용히 틀리는 대신 시끄럽게 실패한다.
     */
    @Test
    fun `totalCount가 받은 행보다 많으면 실패한다`() {
        val body = """
            {"response":{"header":{"resultCode":"00"},"body":{"totalCount":9999,"items":{"item":[
            {"basDt":"20260813","srtnCd":"04020000","clpr":"200570"}
            ]}}}}
        """.trimIndent()

        val raw = catchThrowable { fetch(serving(body)) }

        assertThat(raw).isInstanceOf(FscApiException::class.java)
        assertThat((raw as FscApiException).code).isEqualTo("TRUNCATED")
    }

    /** 설정 누락은 상류 장애가 아니라 우리 문제다 — 조용한 빈 목록이면 "금은 원래 안 나온다"가 된다 */
    @Test
    fun `인증키가 비면 호출 전에 NO_KEY로 실패한다`() {
        // 죽은 포트를 줘도 NO_KEY가 나오면 네트워크로 나가기 전에 막혔다는 뜻이다.
        val raw = catchThrowable { client(deadPort(), key = "").fetchGoldPrices(FROM, TO) }

        assertThat(raw).isInstanceOf(FscApiException::class.java)
        assertThat((raw as FscApiException).code).isEqualTo("NO_KEY")
    }

    @Test
    fun `요청 URI를 되울리는 500 본문에서도 인증키가 새지 않는다`() {
        // 기본 오류 페이지가 하는 그대로 — 요청 URI를 쿼리까지 본문에 렌더링한다.
        val port = serve { ex ->
            respond(ex, 500, "<html><body><b>Message</b> ${ex.requestURI}</body></html>")
        }

        val raw = catchThrowable { fetch(port) }

        assertThat(raw).isInstanceOf(FscApiException::class.java)
        assertThat((raw as FscApiException).code).isEqualTo("HTTP-500")
        assertNoSecretAnywhere(raw)
    }

    /**
     * 공공데이터포털은 인증 오류를 **JSON을 요청해도 XML 봉투로** 주는 경우가 있다.
     * 그러면 Jackson 예외가 원본 본문을 `[Source: (String)"..."]`로 물고 나오는데,
     * 그 본문에 되울려 온 쿼리가 있으면 키가 예외 메시지에 실린다 — 그 메시지는
     * `CommodityCollectSummary.failures`를 타고 어드민 응답까지 나가는 값이다.
     */
    @Test
    fun `요청 URI를 되울리는 비-JSON 200 본문에서도 인증키가 새지 않는다`() {
        val port = serve { ex ->
            respond(ex, 200, "<OpenAPI_ServiceResponse><cmmMsgHeader>${ex.requestURI}</cmmMsgHeader></OpenAPI_ServiceResponse>")
        }

        val raw = catchThrowable { fetch(port) }

        assertThat(raw).isInstanceOf(FscApiException::class.java)
        assertThat((raw as FscApiException).code).isEqualTo("MALFORMED")
        assertNoSecretAnywhere(raw)
    }

    @Test
    fun `연결 실패에서도 인증키가 새지 않는다`() {
        // Reactor가 checkpoint suppressed 프레임에 요청 URI를 실어 보낸다 — cause를 붙이면 그대로 샌다.
        val raw = catchThrowable { fetch(deadPort()) }

        assertThat(raw).isInstanceOf(FscApiException::class.java)
        assertThat((raw as FscApiException).code).isEqualTo("IO")
        assertNoSecretAnywhere(raw)
    }

    @Test
    fun `타임아웃도 FscApiException으로 통일되고 인증키가 새지 않는다`() {
        // **핸들러 sleep을 길게 잡지 말 것.** HttpServer.stop()은 디스패처 스레드를 join하므로
        // 잠든 시간을 tearDown이 통째로 기다린다 — 타임아웃(300ms)보다 넉넉히 길기만 하면 된다.
        val port = serve { Thread.sleep(2_000) }
        val client = client(port).apply { timeout = Duration.ofMillis(300) }

        val raw = catchThrowable { client.fetchGoldPrices(FROM, TO) }

        assertThat(raw).isInstanceOf(FscApiException::class.java)
        assertThat((raw as FscApiException).code).isEqualTo("IO")
        assertNoSecretAnywhere(raw)
    }
}
