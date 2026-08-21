package com.allfolio.market.benchmark

import com.allfolio.market.fsc.FscApiException
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
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicReference

/**
 * 지수시세정보(`getStockMarketIndex`) 클라이언트.
 *
 * 픽스처는 **2026-08-17에 실제로 호출해 받은 응답에서 발췌**한 것이다 — 필드 이름과 값은
 * 손대지 않았고(공개 시세라 값을 그대로 적는다) 행만 골랐다. 여기서 단언이 깨지면 우리가
 * 형식을 잘못 읽었거나 상류가 바꾼 것이지, 테스트가 낡은 게 아니다.
 *
 * **이 픽스처의 요점은 `"IT 서비스"`가 두 시리즈에 함께 있다는 것이다.** 이름만으로 고르는
 * 구현은 이 응답에서만 틀리고 `"코스피"`로는 안 틀린다 — 그래서 남의 지수를 픽스처에 남겨 둔다.
 *
 * 인증키는 **쿼리 파라미터**(`serviceKey=`)에 실린다 — `FscCommodityClientTest`와 같은 그물을
 * 친다: 예외 어디에도 키가 없어야 하고, 요청 경로 조각도 없어야 한다.
 */
class FscIndexClientTest {

    private companion object {
        const val API_KEY = "SUPERSECRETFSCKEY1234"

        val FROM: LocalDate = LocalDate.of(2026, 8, 5)
        val TO: LocalDate = LocalDate.of(2026, 8, 13)

        /**
         * 실측 응답(2026-08-17)에서 **4행을 발췌**한 것이다. 실제 1주 조회는 `totalCount=672`였고,
         * 여기 `totalCount`는 발췌한 행 수에 맞춰 4로 낮췄다 — 안 맞추면 `TRUNCATED`가 걸린다.
         * 필드 이름과 값은 응답 그대로다.
         *
         * **같은 `idxNm`이 시리즈 둘에 걸쳐 있다** — `"IT 서비스"`가 `KOSPI시리즈`와
         * `KOSDAQ시리즈`에 각각 있다. 그 두 행을 남긴 것이 이 발췌의 요점이다.
         *
         * `fltRt`가 `-.89`처럼 **앞의 0 없이** 온다는 것도 여기 고정돼 있다.
         */
        val REAL_BODY = """
            {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},
            "body":{"numOfRows":3000,"pageNo":1,"totalCount":4,"items":{"item":[
            {"basDt":"20260813","idxNm":"코스피","idxCsf":"KOSPI시리즈","clpr":"6813.34","vs":"234.3","fltRt":"3.56"},
            {"basDt":"20260813","idxNm":"IT 서비스","idxCsf":"KOSDAQ시리즈","clpr":"669.09","vs":"-5.98","fltRt":"-.89"},
            {"basDt":"20260813","idxNm":"IT 서비스","idxCsf":"KOSPI시리즈","clpr":"1284.83","vs":"26.53","fltRt":"2.11"},
            {"basDt":"20260812","idxNm":"코스피","idxCsf":"KOSPI시리즈","clpr":"6579.04","vs":"233.51","fltRt":"3.68"}
            ]}}}}
        """.trimIndent()

        fun item(idxNm: String, idxCsf: String) =
            BenchmarkIndexProperties.BenchmarkIndexItem().apply {
                this.type = "KOSPI"
                this.idxNm = idxNm
                this.idxCsf = idxCsf
            }

        val KOSPI = item("코스피", "KOSPI시리즈")
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
    private fun client(port: Int, key: String = API_KEY) = FscIndexClient(
        apiKey = key,
        baseUrl = "http://localhost:$port",
        objectMapper = ObjectMapper(),
    ).apply { connector = dedicatedConnector() }

    private fun fetch(port: Int, target: BenchmarkIndexProperties.BenchmarkIndexItem = KOSPI) =
        client(port).fetch(target, FROM, TO)

    /** 아무도 듣지 않는 포트. 여는 즉시 닫아 두므로 연결이 거부된다. */
    private fun deadPort(): Int = ServerSocket(0).use { it.localPort }

    /** 예외 전체(메시지 + cause 체인 + suppressed + 모든 스택프레임)에 비밀이 없는지 본다. */
    private fun assertNoSecretAnywhere(t: Throwable) {
        val dump = t.stackTraceToString()
        assertThat(dump).doesNotContain(API_KEY)
        assertThat(dump).doesNotContain(FscIndexClient.PATH)
    }

    private fun queryOf(rawQuery: String): Map<String, String> =
        rawQuery.split("&").associate { pair ->
            val (name, value) = pair.split("=", limit = 2)
            URLDecoder.decode(name, "UTF-8") to URLDecoder.decode(value, "UTF-8")
        }

    /**
     * 날짜 파라미터를 **문자열이 아니라 값으로** 되읽는다. `"20260813"`으로 단언하면 형식과 구간
     * 해석이 한 덩어리로 묶여, 깨졌을 때 어느 쪽이 틀렸는지 못 가른다. 형식은 이 파싱이 지킨다 —
     * ISO(`2026-08-13`)를 실어 보내면 `yyyyMMdd` 파싱이 여기서 터진다.
     */
    private fun dateOf(query: Map<String, String>, name: String): LocalDate =
        LocalDate.parse(query.getValue(name), DateTimeFormatter.ofPattern("yyyyMMdd"))

    /**
     * 파라미터 이름·날짜 형식은 2026-08-17 실측으로 확정한 것이다.
     * **`yyyyMMdd`가 여기 고정돼 있다** — ISO(`yyyy-MM-dd`)로 맞추려는 시도가
     * 조용히 0건이 되는 대신 여기서 깨져야 한다.
     *
     * `idxNm`을 쿼리에 싣는 것은 **응답을 줄이려는 것이지 정확성의 근거가 아니다** —
     * 정확성은 아래 `(idxNm, idxCsf)` 응답 필터가 책임진다.
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

        client(port).fetch(KOSPI, FROM, TO)

        val query = queryOf(seenQuery.get())
        assertThat(seenPath.get()).isEqualTo(FscIndexClient.PATH)
        assertThat(query)
            .containsEntry("serviceKey", API_KEY)
            .containsEntry("resultType", "json")
            .containsEntry("pageNo", "1")
            .containsEntry("idxNm", "코스피")
            .containsKey("numOfRows")
        assertThat(dateOf(query, "beginBasDt")).isEqualTo(FROM)
        // **`endBasDt`는 하루 뒤가 실린다 — 배타적이기 때문이다.** 근거는 아래 단일일 테스트에 있다
        assertThat(dateOf(query, "endBasDt")).isEqualTo(TO.plusDays(1))
    }

    /**
     * **`endBasDt`는 배타적이다.** 활용가이드가 `endBasDt`를 "기준일자가 검색값보다 **작은**
     * 데이터를 검색"으로 정의한다(`beginBasDt`만 "크거나 같은"). **이 오퍼레이션
     * (`getStockMarketIndex`)으로 직접 쟀다** — 2026-08-21, 운영 키, `idxNm=코스피`:
     *
     * | 요청 | 응답 |
     * |---|---|
     * | `beginBasDt=20260819&endBasDt=20260819` | `resultCode=00` · `totalCount=0` |
     * | `beginBasDt=20260819&endBasDt=20260820` | `basDt=20260819` |
     *
     * **아래 칸이 대조군이다** — 그게 없으면 위의 0건이 "배타적"인지 "그날 값이 없다"인지
     * 못 가른다. 8/17(광복절 대체공휴일)을 경계로 쓰면 이 구분이 무너지므로 8/18~8/19로 쟀다.
     * 금시세(`getGoldPriceInfo`)도 같은 날 같은 결과였다.
     *
     * 안 더하면 **마지막 날이 조용히 빠진다** — 기본 창(14일)은 다음 실행이 메우지만,
     * 백필은 끝날을 잃고 `from == to` 조회는 언제나 0건이다.
     */
    @Test
    fun `하루짜리 구간도 그날을 포함하도록 endBasDt에 하루를 더해 보낸다`() {
        val day = LocalDate.of(2026, 8, 19)
        val seenQuery = AtomicReference<String>()
        val port = serve { ex ->
            seenQuery.set(ex.requestURI.rawQuery)
            respond(ex, 200, REAL_BODY)
        }

        client(port).fetch(KOSPI, day, day)

        val query = queryOf(seenQuery.get())
        assertThat(dateOf(query, "beginBasDt")).isEqualTo(day)
        assertThat(dateOf(query, "endBasDt")).isEqualTo(day.plusDays(1))
    }

    /**
     * **`basDt`는 `yyyyMMdd`다.** 그리고 응답에 남의 지수가 섞여 와도 `(idxNm, idxCsf)` 쌍에
     * 맞는 두 건만 나온다 — 2026-08-13 KOSPI 종가 6,813.34는 실측값이다.
     */
    @Test
    fun `idxNm과 idxCsf 쌍에 맞는 행만 날짜와 종가로 돌려준다`() {
        val rows = fetch(serving(REAL_BODY))

        assertThat(rows).containsExactly(
            LocalDate.of(2026, 8, 13) to BigDecimal("6813.34"),
            LocalDate.of(2026, 8, 12) to BigDecimal("6579.04"),
        )
    }

    /**
     * **이 테스트가 이 클래스의 존재 이유다.**
     *
     * `idxNm`만으로 고르면 `"IT 서비스"`가 KOSPI시리즈·KOSDAQ시리즈 두 건으로 나오고,
     * 둘 다 그럴듯한 지수 값이라 **어느 쪽이 저장됐는지 아무도 못 알아챈다.**
     * `"코스피"`로는 이 실수가 안 드러난다 — 그 이름이 마침 유일해서다.
     */
    @Test
    fun `같은 이름이라도 idxCsf가 다르면 버린다`() {
        val rows = fetch(serving(REAL_BODY), item("IT 서비스", "KOSDAQ시리즈"))

        // KOSPI시리즈의 "IT 서비스"(1284.83)가 섞이면 안 된다
        assertThat(rows).containsExactly(
            LocalDate.of(2026, 8, 13) to BigDecimal("669.09"),
        )
    }

    /**
     * **응답의 등락률·전일대비는 앞의 0이 없는 소수로 오고(`.05` · `-.89`), 아예 비거나
     * `-`로 오기도 한다.** 이 클라이언트는 그 필드들을 **읽지 않는다** — 저장하는 것이
     * (날짜, 종가) 둘뿐이라 실을 곳이 없다.
     *
     * 그래서 이 테스트가 지키는 규칙은 하나다: **쓰지도 않는 필드 때문에 멀쩡한 종가를
     * 버리지 않는다.** 금 수집기(#178)가 `decimalOrNull`로 같은 것을 막는다. 나중에
     * 등락률을 싣기로 하는 날 `BigDecimal(...)`을 그냥 부르면 세 번째 행이 통째로 사라지는데,
     * 그 증상은 예외가 아니라 "그 날은 시세가 없었다"이다.
     */
    @Test
    fun `등락률이 앞의 0 없는 소수거나 비어 있어도 행을 버리지 않는다`() {
        val body = """
            {"response":{"header":{"resultCode":"00"},"body":{"totalCount":3,"items":{"item":[
            {"basDt":"20260813","idxNm":"코스피","idxCsf":"KOSPI시리즈","clpr":"6813.34","vs":"234.3","fltRt":".05"},
            {"basDt":"20260812","idxNm":"코스피","idxCsf":"KOSPI시리즈","clpr":"6579.04","vs":"-5.98","fltRt":"-.89"},
            {"basDt":"20260811","idxNm":"코스피","idxCsf":"KOSPI시리즈","clpr":"6345.53","vs":"","fltRt":"-"}
            ]}}}}
        """.trimIndent()

        val rows = fetch(serving(body))

        assertThat(rows.map { it.first }).containsExactly(
            LocalDate.of(2026, 8, 13),
            LocalDate.of(2026, 8, 12),
            LocalDate.of(2026, 8, 11),
        )
        assertThat(rows.last().second).isEqualByComparingTo("6345.53")
    }

    /**
     * **`totalCount=0`이면 `items`가 빈 문자열로 온다** — 공공데이터포털에 흔한 모양이다.
     * DTO로 바인딩했으면 여기서 Jackson이 터지고 "휴장이라 0건"이 "상류 장애"로 둔갑한다.
     */
    @Test
    fun `items가 배열이 아니면 빈 목록이다`() {
        val body = """
            {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},
            "body":{"numOfRows":3000,"pageNo":1,"totalCount":0,"items":""}}}
        """.trimIndent()

        assertThat(fetch(serving(body))).isEmpty()
    }

    /** `item`이 객체 하나로 오는 판본도 같은 자리에서 흡수한다 — 안 하면 1건짜리 날이 0건이 된다 */
    @Test
    fun `item이 객체 하나여도 읽는다`() {
        val body = """
            {"response":{"header":{"resultCode":"00"},"body":{"totalCount":1,"items":{"item":
            {"basDt":"20260813","idxNm":"코스피","idxCsf":"KOSPI시리즈","clpr":"6813.34","fltRt":"3.56"}}}}}
        """.trimIndent()

        assertThat(fetch(serving(body))).containsExactly(
            LocalDate.of(2026, 8, 13) to BigDecimal("6813.34"),
        )
    }

    /**
     * 값·날짜가 이상한 행은 버린다. **쌍에 맞는 행만 검사한다** — 남의 지수가 깨져 있다고
     * 우리 지수 수집이 흔들릴 이유가 없다.
     */
    @Test
    fun `날짜나 종가가 이상한 행은 건너뛴다`() {
        val body = """
            {"response":{"header":{"resultCode":"00"},"body":{"totalCount":4,"items":{"item":[
            {"basDt":"2026-08-13","idxNm":"코스피","idxCsf":"KOSPI시리즈","clpr":"6813.34"},
            {"basDt":"20260812","idxNm":"코스피","idxCsf":"KOSPI시리즈","clpr":"-"},
            {"basDt":"20260811","idxNm":"코스피","idxCsf":"KOSPI시리즈","clpr":"0"},
            {"basDt":"20260810","idxNm":"코스피","idxCsf":"KOSPI시리즈","clpr":"6345.53"}
            ]}}}}
        """.trimIndent()

        assertThat(fetch(serving(body))).containsExactly(
            LocalDate.of(2026, 8, 10) to BigDecimal("6345.53"),
        )
    }

    /**
     * **HTTP 200에 실려 오는 실패가 있다.** 등록되지 않은 키·트래픽 초과가 그렇고 items는
     * 비어 있다 — 코드를 안 보면 **"휴장이라 0건"과 구별할 수 없어** 잡은 초록인데
     * KOSPI만 영원히 안 쌓인다.
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
     *
     * **필터 뒤가 아니라 응답 행 수로 본다** — 잘림은 우리가 고른 지수와 무관한 서버 사정이다.
     */
    @Test
    fun `totalCount가 받은 행보다 많으면 실패한다`() {
        val body = """
            {"response":{"header":{"resultCode":"00"},"body":{"totalCount":9999,"items":{"item":[
            {"basDt":"20260813","idxNm":"코스피","idxCsf":"KOSPI시리즈","clpr":"6813.34"}
            ]}}}}
        """.trimIndent()

        val raw = catchThrowable { fetch(serving(body)) }

        assertThat(raw).isInstanceOf(FscApiException::class.java)
        assertThat((raw as FscApiException).code).isEqualTo("TRUNCATED")
    }

    /** 설정 누락은 상류 장애가 아니라 우리 문제다 — 조용한 빈 목록이면 "원래 안 나온다"가 된다 */
    @Test
    fun `인증키가 비면 호출 전에 NO_KEY로 실패한다`() {
        // 죽은 포트를 줘도 NO_KEY가 나오면 네트워크로 나가기 전에 막혔다는 뜻이다.
        val raw = catchThrowable { client(deadPort(), key = "").fetch(KOSPI, FROM, TO) }

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
     * 수집 요약을 타고 어드민 응답과 GitHub Actions 주석까지 나가는 값이다.
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

        val raw = catchThrowable { client.fetch(KOSPI, FROM, TO) }

        assertThat(raw).isInstanceOf(FscApiException::class.java)
        assertThat((raw as FscApiException).code).isEqualTo("IO")
        assertNoSecretAnywhere(raw)
    }
}
