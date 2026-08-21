package com.allfolio.unifiedasset.infrastructure.adapter

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 금융위원회 시세 API 클라이언트 (공공데이터포털 data.go.kr)
 *
 * 환경변수: FSC_API_KEY (공공데이터포털 발급 인증키)
 *
 * 제공 기능:
 *   - 개별 종목 현재가 (종가 기준, 최근 거래일) — 주식시세정보
 *   - ETF 현재가 — **증권상품시세정보**. 주식시세정보에 ETF가 없어서 별도다([getEtfPrice])
 *   - KRX 전체 상장종목 목록 (KOSPI/KOSDAQ/KONEX/ETF/ETN)
 *
 * **한 클래스지만 서비스가 셋이다.** 공공데이터포털의 활용신청은 서비스 단위라, 키 하나로
 * 어떤 오퍼레이션은 되고 어떤 오퍼레이션은 `SERVICE_KEY_IS_NOT_REGISTERED_ERROR`가 되는
 * 상태가 실제로 존재한다(2026-08-21 실측: 주식시세는 되고 증권상품시세는 미승인이었다).
 */
@Component
class FscStockClient(
    @Value("\${fsc.api-key:}") private val apiKey: String,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 베이스 URL. **테스트에서만 루프백 스텁으로 돌린다** — 상수로 박아 두면 요청에 무엇이
     * 실리는지(그리고 인증키가 어디로 새는지) 검증할 방법이 없다. 운영은 기본값 그대로다.
     * 같은 주소를 `fsc.base-url` 기본값으로 들고 있는 클라이언트가 둘 더 있다
     * (`FscCommodityClient`·`FscIndexClient`) — 주소를 고칠 땐 셋을 같이 볼 것.
     */
    internal var baseUrl: String = "https://apis.data.go.kr/1160100/service"

    /** 기준일자가 얼마나 묵었는지 재는 시계. KRX 영업일을 세므로 KST다. 테스트에서만 고정한다 */
    internal var clock: Clock = Clock.system(ZoneId.of("Asia/Seoul"))

    private val client = WebClient.builder()
        .codecs { it.defaultCodecs().maxInMemorySize(10 * 1024 * 1024) }
        .build()

    fun isConfigured(): Boolean = apiKey.isNotBlank()

    /**
     * 종목 현재가(종가) 조회.
     * 6자리 한국 종목코드 전용. 설정 없거나 조회 실패 시 null 반환.
     */
    fun getPrice(symbol: String): FscQuote? {
        if (!isConfigured()) return null
        return runCatching {
            val json = client.get()
                .uri("$baseUrl/GetStockSecuritiesInfoService/getStockPriceInfo") {
                    it.queryParam("serviceKey", apiKey)
                        .queryParam("numOfRows", 1)
                        .queryParam("pageNo", 1)
                        .queryParam("resultType", "json")
                        .queryParam("likeSrtnCd", symbol)
                        .build()
                }
                .retrieve()
                .bodyToMono(String::class.java)
                .block(Duration.ofSeconds(5))
                ?: return null

            val resp = objectMapper.readValue(json, FscPriceResponse::class.java)
            val item = resp.response?.body?.items?.item?.firstOrNull() ?: return null
            val price = item.clpr?.toBigDecimalOrNull() ?: return null
            val asOf = parseBasDt(item.basDt)
            log.debug("[FSC] {} price={} (기준일 {})", symbol, price, asOf)
            FscQuote(price, asOf)
        }.onFailure { e ->
            log.warn("[FSC] price lookup failed for {}: {}", symbol, e.message)
        }.getOrNull()
    }

    /**
     * ETF 현재가(종가) 조회. 6자리 한국 종목코드 전용.
     *
     * **[getPrice]로는 ETF를 못 가져온다.** 주식시세정보(`getStockPriceInfo`)에 ETF 코드를
     * 물으면 오류가 아니라 `totalCount=0`이 온다(2026-08-21 실측, `likeSrtnCd=395270`).
     * ETF는 증권상품시세정보라는 **다른 서비스**에 있고, 공공데이터포털은 활용신청이
     * 서비스별이라 같은 `FSC_API_KEY`라도 이쪽이 따로 승인돼 있어야 한다.
     *
     * 폴백 자리가 Yahoo **뒤**인 이유는 [getPrice]와 같다 — 원가로 떨어져 수익률 0%가 되는
     * 것보다 하루 늦은 공식 종가가 낫지만, 신선한 값이 있으면 그쪽이 먼저다.
     *
     * 🔴 **포털 소개 문구의 "실시간"을 믿지 말 것. 이쪽도 D+1이다** — 2026-08-21 11:42 KST에
     * 최신 `basDt`가 `20260820`이었다(당일치 없음). 소개 문구는 "실시간"이라고 하지만
     * 주식시세정보(15094808)도 같은 표현에 실제로는 D+1이었고, 그 D+1이 #191 회귀의
     * 원인이었다. **그래서 이 폴백은 Yahoo 뒤가 맞다** — 추정이 아니라 잰 값이다.
     * (다만 문서의 "오후 1시 이후"보다는 이르다. 같은 날 09:53엔 최신이 `20260819`,
     *  11:42엔 `20260820`이었다.)
     *
     * 응답 형식도 전부 실호출로 대조했다(2026-08-21, `likeSrtnCd=395270`):
     * 봉투는 `response.body.items.item[]`, `clpr`은 명세의 `number` 선언과 달리 **문자열**
     * (`"56905"`)로 온다, `srtnCd`는 요청값과 정확히 일치한다.
     *
     * 실패는 [getPrice]의 관례대로 `null`이다(현재가는 없어도 화면이 굴러간다). 다만 **사유는
     * 남긴다** — 미승인이면 폴백이 영원히 조용한 null이 되고, 그 상태가 "폴백을 붙였다"는
     * 사실에 가려진다.
     */
    fun getEtfPrice(symbol: String): FscQuote? {
        if (!isConfigured()) return null
        return runCatching {
            val json = client.get()
                .uri("$baseUrl/$ETF_PRICE_PATH") {
                    it.queryParam("serviceKey", apiKey)
                        .queryParam("numOfRows", 1)
                        .queryParam("pageNo", 1)
                        .queryParam("resultType", "json")
                        // 날짜를 아예 싣지 않는다. 최신 1건만 필요한데, 무필터면 최신
                        // 기준일자가 1페이지 첫 행으로 오기 때문이다(2026-08-21 실측:
                        // getStockPriceInfo 1페이지=20260819, 마지막 페이지 1627=20200102).
                        //
                        // 🔴 기간을 쓸 일이 생기면 **endBasDt는 배타적**임을 기억할 것 —
                        // 명세가 "기준일자가 검색값보다 작은"이다. begin=end로 주면 공집합이라
                        // 0건이 오는데, 오류가 아니라 정상 응답이라 미지원으로 오해하기 쉽다
                        // (2026-08-21 금시세로 실측: begin=end=20260819 → 0건,
                        //  begin=20260819·end=20260820 → 20260819 2건).
                        //
                        // likeSrtnCd는 *포함* 검색이다("단축코드가 검색값을 포함"). 단축코드가
                        // 6자리로 같은 길이라 6자리를 주면 사실상 일치지만, 그걸 믿지 않고
                        // 아래에서 srtnCd를 대조한다. 이 오퍼레이션엔 일치형 srtnCd가 없다
                        .queryParam("likeSrtnCd", symbol)
                        .build()
                }
                .retrieve()
                .bodyToMono(String::class.java)
                .block(Duration.ofSeconds(5))
                ?: return null

            // 포털 오류는 HTTP 200에 **다른 봉투**로 온다. 정상 봉투로만 읽으면 미승인·쿼터초과가
            // 전부 "값 없음"으로 뭉개진다 — 승인 전인지 승인 후 고장인지 로그에서 갈려야 한다.
            objectMapper.readValue(json, FscErrorResponse::class.java)
                .openApiServiceResponse?.cmmMsgHeader?.errMsg?.let { errMsg ->
                    // 인증키는 쿼리 파라미터로 나간다 — 응답 본문·URL을 그대로 찍지 않는다.
                    // 여기 싣는 건 포털이 정한 오류 코드뿐이다
                    if (errMsg == NOT_REGISTERED) {
                        log.warn(
                            "[FSC] ETF 시세 미승인({}) — 공공데이터포털 15094806(증권상품시세정보) " +
                            "활용신청이 필요하다. 주식시세정보 승인과는 별개다: symbol={}",
                            errMsg, symbol,
                        )
                    } else {
                        log.warn("[FSC] ETF 시세 오류 응답: errMsg={}, symbol={}", errMsg, symbol)
                    }
                    return null
                }

            val resp = objectMapper.readValue(json, FscPriceResponse::class.java)
            val item = resp.response?.body?.items?.item?.firstOrNull() ?: return null

            // likeSrtnCd 지원은 오퍼레이션마다 다르다. 무시되면 첫 행은 남의 종목이고,
            // 그 값을 내 ETF의 "현재가"로 쓰는 것이 이 PR이 고치려는 고장 그 자체다
            val code = item.srtnCd?.trim()
            if (code != symbol) {
                log.warn(
                    "[FSC] ETF 응답이 다른 종목이다 — likeSrtnCd가 먹지 않았을 수 있다: 요청={}, 응답={}",
                    symbol, code,
                )
                return null
            }

            // 정렬은 최신순이다(2026-08-21 실측: likeSrtnCd=395270에 1페이지=20260820,
            // 마지막 페이지 1236=20210730). 그래서 평시엔 이 가드가 걸리지 않는다.
            //
            // 그래도 남겨 두는 이유는 정렬이 뒤집힐까 봐가 아니라 **최신 행 자체가 오래될 수
            // 있어서**다 — 상장폐지·거래정지된 종목은 마지막 거래일에 멈춰 있고, 그 값을
            // 조용히 "현재가"로 쓰느니 없는 편이 낫다
            val quoteDate = parseBasDt(item.basDt)
            if (quoteDate == null) {
                log.warn("[FSC] ETF 응답에 기준일자가 없다(형식 변경 의심): symbol={}, basDt={}", symbol, item.basDt)
                return null
            }
            val ageDays = ChronoUnit.DAYS.between(quoteDate, LocalDate.now(clock))
            if (ageDays > STALE_AFTER_DAYS) {
                log.warn(
                    "[FSC] ETF 종가가 {}일 묵었다 — 현재가로 쓰지 않는다: symbol={}, 기준일={}",
                    ageDays, symbol, quoteDate,
                )
                return null
            }

            val price = item.clpr?.toBigDecimalOrNull() ?: return null
            log.debug("[FSC] ETF {} price={} (기준일 {})", symbol, price, quoteDate)
            FscQuote(price, quoteDate)
        }.onFailure { e ->
            log.warn("[FSC] ETF price lookup failed for {}: {}", symbol, e.message)
        }.getOrNull()
    }

    /**
     * KRX 전체 상장종목 목록 조회 (페이지네이션).
     * 상장 종목 DB 갱신용. 설정 없으면 빈 리스트 반환.
     */
    fun listAllStocks(): List<KrStockItem> {
        if (!isConfigured()) {
            log.warn("[FSC] API 키 미설정 — 종목 목록 조회 건너뜀")
            return emptyList()
        }
        val result = mutableListOf<KrStockItem>()
        var page = 1
        val pageSize = 3000
        while (true) {
            val items = fetchStockPage(page, pageSize)
            result.addAll(items)
            if (items.size < pageSize) break
            page++
        }
        log.info("[FSC] 전체 상장종목 {}개 조회 완료", result.size)
        return result
    }

    private fun fetchStockPage(page: Int, size: Int): List<KrStockItem> = runCatching {
        val json = client.get()
            .uri("$baseUrl/GetKrxListedInfoService/getItemInfo") {
                it.queryParam("serviceKey", apiKey)
                    .queryParam("numOfRows", size)
                    .queryParam("pageNo", page)
                    .queryParam("resultType", "json")
                    .build()
            }
            .retrieve()
            .bodyToMono(String::class.java)
            .block(Duration.ofSeconds(15))
            ?: return emptyList()

        val resp = objectMapper.readValue(json, FscListResponse::class.java)
        resp.response?.body?.items?.item
            ?.filter { it.srtnCd?.isNotBlank() == true && it.itmsNm?.isNotBlank() == true }
            ?.map { item ->
                val market = when (item.mrktCtg?.uppercase()) {
                    "KOSPI" -> "KOSPI"
                    "KOSDAQ" -> "KOSDAQ"
                    "KONEX" -> "KONEX"
                    else -> item.mrktCtg ?: "KRX"
                }
                KrStockItem(
                    symbol = item.srtnCd!!.trim(),
                    name = item.itmsNm!!.trim(),
                    market = market,
                )
            } ?: emptyList()
    }.onFailure { e ->
        log.warn("[FSC] 종목목록 page={} 조회 실패: {}", page, e.message)
    }.getOrElse { emptyList() }

    // ── Response DTOs ──────────────────────────────────────────────

    data class KrStockItem(val symbol: String, val name: String, val market: String)

    /**
     * FSC가 준 시세 한 건.
     *
     * **가격만 돌려주면 안 되는 이유**: FSC는 D+1이라 이 값은 오늘 것이 아니다. 어느 날짜의
     * 종가인지를 같이 실어 보내야 화면이 "8/20 종가 기준"이라고 말할 수 있다(A1·N2의 금과
     * 같은 이유다). [asOf]는 응답의 `basDt`이고, 없거나 못 읽으면 null이다 —
     * **날짜를 못 읽었다고 멀쩡한 종가를 버리지는 않는다.**
     */
    data class FscQuote(val price: BigDecimal, val asOf: LocalDate?)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FscPriceResponse(val response: FscPriceBody? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FscPriceBody(val body: FscPriceItems? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FscPriceItems(val items: FscPriceItemList? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FscPriceItemList(val item: List<FscPriceItem>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FscPriceItem(
        val basDt: String? = null, // 기준일자 yyyyMMdd
        val srtnCd: String? = null,
        val itmsNm: String? = null,
        val clpr: String? = null,  // 종가
        val vs: String? = null,    // 전일대비
        val fltRt: String? = null, // 등락률
        val mkp: String? = null,   // 시가
        val hipr: String? = null,  // 고가
        val lopr: String? = null,  // 저가
        val trqu: String? = null,  // 거래량
    )

    /**
     * 포털 공통 오류 봉투. 정상 응답의 `response`와 **다른 최상위 키**로 온다
     * (`OpenAPI_ServiceResponse`), 그것도 HTTP 200으로. 미승인·쿼터초과가 여기로 온다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FscErrorResponse(
        @JsonProperty("OpenAPI_ServiceResponse") val openApiServiceResponse: FscErrorBody? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FscErrorBody(val cmmMsgHeader: FscErrorHeader? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FscErrorHeader(
        val errMsg: String? = null,
        val returnReasonCode: String? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FscListResponse(val response: FscListBody? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FscListBody(val body: FscListItems? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FscListItems(val items: FscListItemList? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FscListItemList(val item: List<FscListItem>? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FscListItem(
        @JsonProperty("srtnCd")  val srtnCd: String? = null,
        @JsonProperty("isinCd")  val isinCd: String? = null,
        @JsonProperty("itmsNm")  val itmsNm: String? = null,
        @JsonProperty("corpNm")  val corpNm: String? = null,
        @JsonProperty("mrktCtg") val mrktCtg: String? = null,
    )

    /** `yyyyMMdd` → 날짜. 비었거나 형식이 다르면 null */
    private fun parseBasDt(basDt: String?): LocalDate? =
        basDt?.let { runCatching { LocalDate.parse(it, BAS_DT) }.getOrNull() }

    private companion object {
        const val ETF_PRICE_PATH = "GetSecuritiesProductInfoService/getETFPriceInfo"

        /** 활용신청이 안 된 오퍼레이션에 오는 포털 공통 오류 코드 */
        const val NOT_REGISTERED = "SERVICE_KEY_IS_NOT_REGISTERED_ERROR"

        /**
         * 이보다 묵은 기준일자는 현재가로 쓰지 않는다.
         *
         * 14일은 설 연휴(최장 5영업일)+주말+D+1 지연을 다 겪어도 못 넘는 폭이다.
         * 거래정지·상장폐지로 멈춘 종목은 이 폭을 금방 넘으므로 둘이 갈린다.
         */
        const val STALE_AFTER_DAYS = 14L

        val BAS_DT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    }
}
