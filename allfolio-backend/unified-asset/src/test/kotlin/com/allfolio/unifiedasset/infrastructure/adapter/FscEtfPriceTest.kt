package com.allfolio.unifiedasset.infrastructure.adapter

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference

/**
 * ETF 현재가 폴백([FscStockClient.getEtfPrice]).
 *
 * **왜 별도 오퍼레이션인가.** 주식시세정보(`getStockPriceInfo`)는 ETF를 담고 있지 않다 —
 * 2026-08-21 실측: `likeSrtnCd=395270`(HANARO Fn K-반도체)로 물으면 `totalCount=0`이 온다.
 * 그래서 Yahoo가 막히면 ETF만 폴백 없이 평균 매입단가로 떨어졌다(수익률 0%).
 * ETF는 증권상품시세정보(`GetSecuritiesProductInfoService/getETFPriceInfo`)라는 다른
 * 서비스에 있다.
 *
 * **픽스처는 2026-08-21 11:42 KST에 실제로 받은 응답 그대로다.** 처음 이 테스트를 쓸 땐
 * 키가 15094806에 미승인이라 항목명만 포털 명세로 확정하고 값은 지어냈었는데, 승인 후
 * 실물로 교체했다. 여기서 단언이 깨지면 우리가 형식을 잘못 읽었거나 상류가 바꾼 것이다.
 *
 * 실호출로 확정된 것들:
 *
 *  - 봉투는 형제 오퍼레이션과 같은 `response.body.items.item[]`이다.
 *  - 🔴 **`clpr`이 문자열로 온다**(`"56905"`). 포털 명세는 `number`로 선언해 두었다 —
 *    선언이 런타임을 검사하지 않는다는 걸 그대로 보여 준다([[af-104-market-screen]]).
 *  - `likeSrtnCd`로 물으면 응답 `srtnCd`가 요청값과 정확히 일치한다. 참고로 이 오퍼레이션에
 *    *일치* 검색용 `srtnCd`는 없다(`isinCd`·`itmsNm`만 일치형이 있다) — 그래서 *포함* 검색을
 *    쓰고 응답을 대조한다.
 *  - **정렬은 최신순**이다: `likeSrtnCd=395270`에 1페이지가 `20260820`, 마지막 페이지
 *    (1236)가 `20210730`.
 *  - **D+1이다**: 8/21에 물었는데 최신이 `20260820`이다. 폴백이 Yahoo 뒤인 근거다.
 */
class FscEtfPriceTest {

    private companion object {
        const val API_KEY = "SUPERSECRETFSCKEY1234"

        val KST: ZoneId = ZoneId.of("Asia/Seoul")

        /** HANARO Fn K-반도체 — 주식시세정보에서 0건이 나온 그 종목 */
        const val ETF = "395270"

        val ETF_BODY = """
            {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},
            "body":{"numOfRows":1,"pageNo":1,"totalCount":1236,"items":{"item":[
            {"basDt":"20260820","srtnCd":"395270","isinCd":"KR7395270002","itmsNm":"HANARO Fn K-반도체",
            "clpr":"56905","vs":"4455","fltRt":"8.49","nav":"56879.44","mkp":"54800","hipr":"57155",
            "lopr":"53410","trqu":"1534005","trPrc":"85599304384","mrktTotAmt":"3596396000000",
            "stLstgCnt":"63200000","bssIdxIdxNm":"FnGuide K-반도체 지수","bssIdxClpr":"17028.87",
            "nPptTotAmt":"3597624534492"}
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
        exchange.responseHeaders.add("Content-Type", "application/json;charset=UTF-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun serving(body: String): Int = serve { respond(it, 200, body) }

    private fun client(port: Int, key: String = API_KEY) =
        FscStockClient(key, ObjectMapper()).apply {
            baseUrl = "http://127.0.0.1:$port"
            // 이 픽스처를 실제로 받은 시각(2026-08-21 11:42 KST)에 고정한다 — 그러지 않으면
            // 이 파일 전체가 2026-09월이 되는 순간 신선도 가드에 걸려 죽는다
            clock = Clock.fixed(Instant.parse("2026-08-21T02:42:00Z"), KST)
        }

    @Test
    fun `ETF 종가를 돌려준다`() {
        val price = client(serving(ETF_BODY)).getEtfPrice(ETF)

        assertThat(price).isEqualByComparingTo(BigDecimal("56905"))
    }

    @Test
    fun `종목코드는 likeSrtnCd로 싣고 기간 파라미터는 싣지 않는다`() {
        val query = AtomicReference<String>()
        val port = serve { ex ->
            query.set(ex.requestURI.query)
            respond(ex, 200, ETF_BODY)
        }

        client(port).getEtfPrice(ETF)

        // 값으로 갈라 본다 — 순서·인코딩을 문자열로 단언하면 구현이 옳아도 깨진다
        val params = query.get().split("&")
            .map { it.split("=", limit = 2) }
            .associate { it[0] to it.getOrElse(1) { "" } }

        assertThat(params["likeSrtnCd"]).isEqualTo(ETF)
        assertThat(params["resultType"]).isEqualTo("json")
        // 최신 1건만 필요하므로 날짜를 아예 싣지 않는다. (기간을 쓸 일이 생기면 endBasDt가
        // 배타적이라는 점에 주의 — begin=end는 공집합이라 조용히 0건이다)
        assertThat(params).doesNotContainKeys("beginBasDt", "endBasDt")
    }

    /**
     * `likeSrtnCd`가 (오퍼레이션마다 지원이 달라) 무시되면 응답 첫 행은 **남의 종목**이 된다.
     * 그 값을 내 ETF의 현재가로 쓰는 것보다 값이 없는 편이 낫다 — 화면에 틀린 숫자가 뜨는 것이
     * 이 PR이 고치려는 바로 그 고장이다.
     */
    @Test
    fun `다른 종목의 행이 오면 쓰지 않는다`() {
        val other = ETF_BODY.replace("\"srtnCd\":\"395270\"", "\"srtnCd\":\"069500\"")

        val price = client(serving(other)).getEtfPrice(ETF)

        assertThat(price).isNull()
    }

    /**
     * **정렬은 최신순으로 확인됐다**(2026-08-21 실측: `likeSrtnCd=395270`에 1페이지가
     * `20260820`, 마지막 페이지 1236이 `20210730`). 그러니 평시에 이 가드는 걸리지 않는다.
     *
     * **그래도 지우지 말 것.** 이 가드가 막는 건 정렬 사고가 아니라 **최신 행 자체가 오래된
     * 종목**이다 — 거래정지·상장폐지된 ETF는 마지막 거래일에 멈춰 있고, 그 값을 조용히
     * "현재가"로 쓰면 화면엔 멀쩡한 숫자가 뜬다. 값이 없는 편이 낫다.
     *
     * 14일: 설 연휴(최장 5영업일)+주말+D+1을 다 겪어도 못 넘는 폭이라 정상 휴장과 갈린다.
     */
    @Test
    fun `기준일자가 너무 오래된 값은 쓰지 않는다`() {
        val stale = ETF_BODY.replace("\"basDt\":\"20260820\"", "\"basDt\":\"20200102\"")

        val price = client(serving(stale)).getEtfPrice(ETF)

        assertThat(price).isNull()
    }

    /**
     * 활용신청이 안 된 오퍼레이션은 **HTTP 200에 다른 봉투**로 온다
     * (`OpenAPI_ServiceResponse.cmmMsgHeader.errMsg`). 정상 봉투로 파싱하면 `response`가
     * null이라 그냥 "값 없음"이 되고, 그러면 *승인이 아직인 것*과 *승인 후에 안 되는 것*이
     * 로그에서 갈리지 않는다. 폴백을 붙였다는 사실이 폴백이 동작한다는 착각으로 굳는 자리다.
     *
     * **이 픽스처는 승인 전(2026-08-21 오전)에 실제로 받은 본문이다.** 지금은 승인돼 같은
     * 호출이 정상 응답을 주지만, 그렇다고 이 경로가 죽은 건 아니다 — 쿼터 초과나 키 교체
     * 때 같은 봉투가 다시 온다.
     */
    @Test
    fun `미승인 인증키는 구분되는 경고로 남긴다`() {
        val notRegistered = """
            {"OpenAPI_ServiceResponse":{"cmmMsgHeader":{
            "errMsg":"SERVICE_KEY_IS_NOT_REGISTERED_ERROR",
            "returnAuthMsg":"등록되지 않은 서비스키","returnReasonCode":"30"}}}
        """.trimIndent()

        val logger = LoggerFactory.getLogger(FscStockClient::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            val price = client(serving(notRegistered)).getEtfPrice(ETF)

            assertThat(price).isNull()
            val warned = appender.list.filter { it.level == Level.WARN }.joinToString("\n") { it.formattedMessage }
            assertThat(warned).contains("SERVICE_KEY_IS_NOT_REGISTERED_ERROR")
            assertThat(warned).describedAs("무엇을 해야 하는지가 로그에 있어야 한다").contains("15094806")
            assertThat(warned).describedAs("인증키가 로그로 새면 안 된다").doesNotContain(API_KEY)
        } finally {
            logger.detachAppender(appender)
        }
    }

    /**
     * 종목이 없으면 `totalCount=0`에 빈 배열이다. **2026-08-21 실측 응답 그대로다** —
     * 주식시세정보에 `likeSrtnCd=395270`을 물었을 때 오는 바로 그 본문이고, 이 PR이 존재하는
     * 이유이기도 하다.
     */
    @Test
    fun `0건이면 값이 없다`() {
        val empty = """
            {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},
            "body":{"numOfRows":1,"pageNo":1,"totalCount":0,"items":{"item":[]}}}}
        """.trimIndent()

        val price = client(serving(empty)).getEtfPrice(ETF)

        assertThat(price).isNull()
    }

    /**
     * **포털 명세는 `clpr`을 `number`로 선언해 놓고, 형제 오퍼레이션들은 실제로 문자열을 준다**
     * (2026-08-21 실측: 주식·금·지수 전부 `"clpr":"247500"`). 어느 쪽이 올지 모르니 둘 다 받는다.
     *
     * 이 프로젝트는 선언된 타입을 믿었다가 자릿수가 날아간 적이 있다([[af-104-market-screen]]) —
     * 선언은 런타임을 검사하지 않는다.
     */
    @Test
    fun `종가가 숫자로 와도 문자열로 와도 같은 값이다`() {
        val numeric = ETF_BODY
            .replace("\"clpr\":\"56905\"", "\"clpr\":56905")
            .replace("\"vs\":\"4455\"", "\"vs\":4455")
            .replace("\"fltRt\":\"8.49\"", "\"fltRt\":8.49")

        // 치환이 안 먹으면 이 테스트는 성공 경로를 한 번 더 도는 것뿐이다
        assertThat(numeric).describedAs("픽스처가 실제로 숫자형이어야 증명이 된다")
            .contains("\"clpr\":56905")

        val price = client(serving(numeric)).getEtfPrice(ETF)

        assertThat(price).isEqualByComparingTo(BigDecimal("56905"))
    }

    /** 키가 없으면 네트워크로 나가지도 않는다 — 죽은 포트로 겨눠도 조용히 null이어야 한다 */
    @Test
    fun `인증키가 없으면 호출하지 않는다`() {
        val deadPort = ServerSocket(0).use { it.localPort }

        val price = client(deadPort, key = "").getEtfPrice(ETF)

        assertThat(price).isNull()
    }
}
