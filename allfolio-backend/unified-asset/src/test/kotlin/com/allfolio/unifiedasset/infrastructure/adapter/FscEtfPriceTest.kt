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
 * 2026-08-21 실측: `likeSrtnCd=395270`(HANARO K-반도체)로 물으면 `totalCount=0`이 온다.
 * 그래서 Yahoo가 막히면 ETF만 폴백 없이 평균 매입단가로 떨어졌다(수익률 0%).
 * ETF는 증권상품시세정보(`GetSecuritiesProductInfoService/getETFPriceInfo`)라는 다른
 * 서비스에 있다.
 *
 * **픽스처 출처를 분명히 해 둔다.** 이 키는 15094806에 아직 **미승인**이라
 * (`SERVICE_KEY_IS_NOT_REGISTERED_ERROR`) 성공 응답을 실물로 받아 보지 못했다. 대신:
 *
 *  - **항목명은 포털 명세에서 확인했다**(2026-08-21, 데이터셋 15094806 페이지의 API 명세):
 *    `Item_ETFPriceInfo`에 `basDt`·`srtnCd`·`itmsNm`·`clpr`·`nav`·`bssIdxIdxNm`이 있다.
 *    형제 오퍼레이션에서 추정한 게 아니다.
 *  - **`likeSrtnCd`도 명세에 있다** — "단축코드가 검색값을 포함하는 데이터를 검색"
 *    (공식 활용자가이드의 예시값도 `069500`이다). 참고로 이 오퍼레이션에 *일치* 검색용
 *    `srtnCd`는 없다(`isinCd`·`itmsNm`만 일치형이 있다).
 *  - 응답 봉투(`response.body.items.item[]`)와 값의 표기 방식은 같은 기관의 형제 셋
 *    (주식시세·지수시세·금시세)에서 실제로 받아 확인한 형태다.
 *
 * **남은 미확인은 정렬 방향 하나다**(명세에 정렬 언급이 없다). 승인 후 첫 호출로 볼 것.
 */
class FscEtfPriceTest {

    private companion object {
        const val API_KEY = "SUPERSECRETFSCKEY1234"

        val KST: ZoneId = ZoneId.of("Asia/Seoul")

        /** HANARO K-반도체 — 주식시세정보에서 0건이 나온 그 종목 */
        const val ETF = "395270"

        val ETF_BODY = """
            {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},
            "body":{"numOfRows":1,"pageNo":1,"totalCount":412,"items":{"item":[
            {"basDt":"20260819","srtnCd":"395270","isinCd":"KR7395270005","itmsNm":"HANARO K-반도체",
            "clpr":"18450","vs":"-320","fltRt":"-1.70","nav":"18462.35","mkp":"18700","hipr":"18780",
            "lopr":"18400","trqu":"152344","trPrc":"2830000000"}
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
            // 픽스처의 기준일자(2026-08-19) 바로 다음 영업일에 물어본 것으로 둔다 —
            // 그러지 않으면 이 파일 전체가 2026-09월이 되는 순간 신선도 가드에 걸려 죽는다
            clock = Clock.fixed(Instant.parse("2026-08-20T09:00:00Z"), KST)
        }

    @Test
    fun `ETF 종가를 돌려준다`() {
        val price = client(serving(ETF_BODY)).getEtfPrice(ETF)

        assertThat(price).isEqualByComparingTo(BigDecimal("18450"))
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
     * 이 오퍼레이션의 **정렬 방향을 아직 실호출로 확인하지 못했다**(키 미승인). 형제
     * 오퍼레이션들은 최신순이지만 — 2026-08-21 실측: `getStockPriceInfo`의 1페이지가
     * 20260819, 마지막 페이지가 20200102 — 만약 이쪽이 오래된 순이라면 1페이지 첫 행은
     * **몇 년 전 종가**다. 그걸 조용히 "현재가"로 쓰면 안 된다.
     *
     * 14일: 설 연휴(5영업일)+주말+D+1 지연을 다 겪어도 못 넘는 폭이고, 정렬이 뒤집혔을 때의
     * 수년 차이와는 자릿수가 다르다.
     */
    @Test
    fun `기준일자가 너무 오래된 값은 쓰지 않는다`() {
        val stale = ETF_BODY.replace("\"basDt\":\"20260819\"", "\"basDt\":\"20200102\"")

        val price = client(serving(stale)).getEtfPrice(ETF)

        assertThat(price).isNull()
    }

    /**
     * 활용신청이 안 된 오퍼레이션은 **HTTP 200에 다른 봉투**로 온다
     * (`OpenAPI_ServiceResponse.cmmMsgHeader.errMsg`). 정상 봉투로 파싱하면 `response`가
     * null이라 그냥 "값 없음"이 되고, 그러면 *승인이 아직인 것*과 *승인 후에 안 되는 것*이
     * 로그에서 갈리지 않는다. 폴백을 붙였다는 사실이 폴백이 동작한다는 착각으로 굳는 자리다.
     *
     * **이 픽스처는 지어낸 게 아니라 2026-08-21에 실제로 받은 본문이다** — 이 키는 지금
     * 15094806에 미승인이라, 오히려 오류 경로 쪽이 실측이고 성공 경로 쪽이 추정이다.
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
            .replace("\"clpr\":\"18450\"", "\"clpr\":18450")
            .replace("\"vs\":\"-320\"", "\"vs\":-320")
            .replace("\"fltRt\":\"-1.70\"", "\"fltRt\":-1.70")

        // 치환이 안 먹으면 이 테스트는 성공 경로를 한 번 더 도는 것뿐이다
        assertThat(numeric).describedAs("픽스처가 실제로 숫자형이어야 증명이 된다")
            .contains("\"clpr\":18450")

        val price = client(serving(numeric)).getEtfPrice(ETF)

        assertThat(price).isEqualByComparingTo(BigDecimal("18450"))
    }

    /** 키가 없으면 네트워크로 나가지도 않는다 — 죽은 포트로 겨눠도 조용히 null이어야 한다 */
    @Test
    fun `인증키가 없으면 호출하지 않는다`() {
        val deadPort = ServerSocket(0).use { it.localPort }

        val price = client(deadPort, key = "").getEtfPrice(ETF)

        assertThat(price).isNull()
    }
}
