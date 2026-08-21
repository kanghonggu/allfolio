package com.allfolio.market.commodity.fsc

import com.allfolio.market.fsc.FscApiException
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.reactive.ClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 금시세 응답 한 행.
 *
 * **`srtnCd`를 버리지 않고 실어 보낸다.** 한 응답에 종목이 둘(금 1kg `04020000` ·
 * 미니금 100g `04020100`) 섞여 오고, 어느 쪽을 쓸지는 설정이 정한다 —
 * 그 선택을 [FscCommoditySource]가 하려면 행마다 종목코드가 붙어 있어야 한다.
 * 여기서 미리 걸러 버리면 소스는 자기가 무엇을 받았는지 알 수 없다.
 *
 * @param changeValue 응답의 `vs`(전일대비, 원/g). @param changeRate 응답의 `fltRt`(등락률, %).
 *        **지금은 아무도 안 쓴다** — `CommodityObservation`이 (날짜, 값) 둘뿐이라 포트가 못 싣고,
 *        `CommodityCollectService`가 저장된 이력으로 전일대비를 다시 계산한다.
 *
 *        그래도 읽는 이유는 단순하다: **소스가 이미 주는 값이고 비용이 필드 둘이다.**
 *        전일대비를 계산값이 아니라 소스 값으로 바꾸기로 하는 날 — 거래소가 정한 수치가
 *        우리 사다리보다 옳은 경우가 있다 — 응답 형식을 처음부터 다시 조사하지 않아도 된다.
 *        그 형식이 자명하지 않다는 게 요점이다: 앞의 0이 없는 소수로 온다(`.05` · `-.19`).
 *
 *        **값이 깨져도 행을 버리지 않는다** — 쓰지도 않는 필드 때문에 멀쩡한 종가를 버리는
 *        쪽이 훨씬 나쁘다. 그래서 둘 다 nullable이다.
 */
data class FscGoldRow(
    val srtnCd: String,
    val quoteDate: LocalDate,
    val price: BigDecimal,
    val changeValue: BigDecimal?,
    val changeRate: BigDecimal?,
)

/** @param skipped 날짜·종가가 이상해 버린 행 수. `CommodityFetch.skipped`로 그대로 나간다 */
data class FscGoldFetch(val rows: List<FscGoldRow>, val skipped: Int)

/**
 * 금융위원회 일반상품시세정보 — KRX 금시장 일별 시세 (`getGoldPriceInfo`).
 *
 * 인증·베이스·응답 두 겹(`response.body.items.item[]`)은 같은 기관의 다른 오퍼레이션을 부르는
 * `FscStockClient`(unified-asset)와 같다. **다르게 한 것 셋:**
 *
 *  1. **키 미설정을 `null`이 아니라 예외로 알린다.** `FscStockClient`는 `isConfigured()`가
 *     false면 조용히 `null`/빈 목록을 준다 — 현재가 조회는 값이 없어도 화면이 굴러가기 때문이다.
 *     수집 경로는 반대다: 빈 목록은 `emptySeries`(정상적으로 빈 계열)로 접수돼 요약이 초록으로
 *     끝나고, "키를 안 넣었다"가 "금이 원래 안 나온다"로 굳는다. `FredApiClient`가 같은 이유로
 *     `NO_KEY`를 던진다. `isConfigured()` 자체는 관례대로 남겨 두되 그 뒤가 다르다.
 *  2. **`runCatching`으로 실패를 삼키지 않는다.** 사유는 종목별 실패로 요약에 실려야 한다.
 *  3. **베이스 URL이 설정이다**(`fsc.base-url`). 상수로 박으면 루프백 스텁으로 요청 형태와
 *     키 유출을 검증할 수 없다 — 기본값은 `FscStockClient`가 쓰는 것과 같은 주소다
 *     (그쪽도 같은 이유로 테스트에서만 덮을 수 있게 열려 있다).
 *
 * **🔴 인증키가 쿼리 파라미터(`serviceKey=`)에 실린다.** `FredApiClient`와 같은 방어 셋을 지킨다:
 * 전체 URL을 로그에 찍지 않는다 · 예외에 `cause`를 붙이지 않는다(Reactor의 checkpoint 프레임에
 * 요청 URI가 통째로 들어 있다) · 응답 본문 미리보기를 남기지 않는다(기본 오류 페이지가 요청
 * URI를 되울려 렌더링한다). 이 예외 메시지는 `CommodityCollectSummary.failures`를 타고
 * 어드민 응답과 GitHub Actions 주석까지 나가는 값이다.
 */
@Component
class FscCommodityClient(
    @Value("\${fsc.api-key:}") private val apiKey: String,
    // 기본값은 FscStockClient가 들고 있는 것과 같은 주소다. 애너테이션 인자는 컴파일
    // 상수여야 해서 상수 참조로 묶지 못한다 — 주소를 고칠 땐 두 파일을 같이 볼 것
    @Value("\${fsc.base-url:https://apis.data.go.kr/1160100/service}") private val baseUrl: String,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(baseUrl)
            .codecs { it.defaultCodecs().maxInMemorySize(8 * 1024 * 1024) }
            .also { builder -> connector?.let(builder::clientConnector) }
            .build()
    }

    /** 응답 대기 상한. 테스트에서만 줄인다 — 근거는 `FredApiClient.timeout`의 주석과 같다 */
    internal var timeout: Duration = DEFAULT_TIMEOUT

    /** HTTP 커넥터. **운영은 null로 두고 기본값을 쓴다** — 근거는 `FredApiClient.connector` 주석 */
    internal var connector: ClientHttpConnector? = null

    /** `FscStockClient`의 관례. 다만 이 클라이언트는 false를 예외로 바꾼다 — 클래스 KDoc 참조 */
    fun isConfigured(): Boolean = apiKey.isNotBlank()

    /**
     * `from..to`(포함) 구간의 금시세를 가져온다. **종목을 가리지 않는다** — 응답에 실린 모든
     * 종목을 그대로 돌려주고, 고르는 일은 [FscCommoditySource]가 설정을 보고 한다.
     */
    fun fetchGoldPrices(from: LocalDate, to: LocalDate): FscGoldFetch {
        // 설정 누락은 상류 장애가 아니라 우리 문제다 — 사유가 남아야 운영자가 Render 환경변수를 보러 간다
        if (!isConfigured()) {
            throw FscApiException("NO_KEY", "공공데이터포털 인증키가 설정되지 않았습니다 (FSC_API_KEY)")
        }

        // 구간만 남긴다. 전체 URL을 찍으면 serviceKey가 그대로 로그에 박힌다
        log.info("[FSC] 금시세 조회 {}~{}", from, to)

        val body = try {
            webClient.get()
                .uri { b ->
                    b.path(PATH)
                        .queryParam("serviceKey", apiKey)
                        .queryParam("resultType", "json")
                        .queryParam("numOfRows", PAGE_SIZE)
                        .queryParam("pageNo", 1)
                        // **`yyyyMMdd`다. FRED의 ISO(`yyyy-MM-dd`)가 아니다** —
                        // LocalDate.toString()을 그대로 넘기면 조용히 0건이 된다
                        .queryParam("beginBasDt", DATE_FORMAT.format(from))
                        .queryParam("endBasDt", DATE_FORMAT.format(to))
                        .build()
                }
                .retrieve()
                .bodyToMono(String::class.java)
                .block(timeout)
                ?: throw FscApiException("EMPTY", "응답 본문이 비어 있습니다")
        } catch (e: FscApiException) {
            throw e
        } catch (e: WebClientResponseException) {
            // 상태만 남긴다. 본문에는 우리 요청 URL이 되울려 올 수 있고 거기 키가 들어 있다.
            // cause도 붙이지 않는다 — Reactor checkpoint 프레임에 URI가 통째로 있다
            log.warn("[FSC] HTTP {}", e.statusCode.value())
            throw FscApiException(
                "HTTP-${e.statusCode.value()}",
                "공공데이터포털이 HTTP ${e.statusCode.value()} 를 반환했습니다",
            )
        } catch (e: Throwable) {
            if (e is Error) throw e
            if (e is InterruptedException || e.cause is InterruptedException) Thread.currentThread().interrupt()
            log.warn("[FSC] 호출 실패 reason={}", e.javaClass.simpleName)
            throw FscApiException("IO", "공공데이터포털 호출에 실패했습니다")
        }

        // 본문이 JSON이 아니면(인증 오류 시 XML 봉투를 주는 경우가 있다) Jackson 예외가 원본 본문을
        // `[Source: (String)"..."]`로 물고 나온다 — 그 본문에 되울려 온 쿼리가 있으면 키가 새므로
        // 여기서 갈아끼운다. cause도 붙이지 않는다(같은 본문을 물고 있다)
        return try {
            parse(body)
        } catch (e: JsonProcessingException) {
            log.warn("[FSC] 응답이 JSON이 아닙니다 reason={}", e.javaClass.simpleName)
            throw FscApiException("MALFORMED", "응답 본문이 올바른 JSON이 아닙니다")
        }
    }

    private fun parse(json: String): FscGoldFetch {
        val response = objectMapper.readTree(json).path("response")

        // **HTTP 200에 실려 오는 실패가 있다.** 등록되지 않은 키·트래픽 초과가 그렇고, 그때
        // items는 비어 있다 — 코드를 안 보면 "그 구간에 시세가 없다"와 구별할 수 없어
        // 요약이 초록인 채 금만 영원히 안 쌓인다. resultMsg는 싣지 않는다(서버가 만든
        // 문자열이라 요청 URL이 되울려 올 수 있다)
        //
        // **빈 코드를 통과시키는 것은 의도다.** `isNotBlank()`를 빼면 header가 없거나 이름이
        // 바뀐 응답이 전부 실패가 된다 — 그런데 그 응답의 items는 멀쩡할 수 있다.
        // 이 검사가 막으려는 것은 "실패인데 0건처럼 보이는 것"이지 "header가 없는 것"이 아니고,
        // 형식이 조금만 흔들려도 전량 실패시키는 쪽이 더 나쁜 실패다. 헤더 자체가 사라지는
        // 형식 변화는 이 그물을 통과한다 — 그건 감수한 구멍이지 빠뜨린 검사가 아니다.
        val resultCode = response.path("header").path("resultCode").asText("")
        if (resultCode.isNotBlank() && resultCode != RESULT_OK) {
            log.warn("[FSC] 정상 코드가 아닙니다 resultCode={}", resultCode)
            throw FscApiException("RESULT-$resultCode", "공공데이터포털이 정상 코드가 아닌 결과를 반환했습니다")
        }

        val body = response.path("body")

        // **`totalCount=0`이면 `items`가 빈 문자열 `""`로 온다** — 공공데이터포털에 흔한 모양이다.
        // DTO 바인딩이었으면 여기서 Jackson이 터지고 "0건"이 "장애"로 둔갑한다. 그래서 트리로 읽고
        // 배열이 아니면 빈 목록으로 본다. `item`이 객체 하나로 오는 판본도 같은 자리에서 흡수한다
        val itemNode = body.path("items").path("item")
        val items: List<JsonNode> = when {
            itemNode.isArray -> itemNode.toList()
            itemNode.isObject -> listOf(itemNode)
            else -> emptyList()
        }
        if (items.isEmpty()) return FscGoldFetch(emptyList(), 0)

        // **잘린 응답을 조용히 넘기지 않는다.** 페이지를 하나만 받으므로 구간이 넓으면 뒷날짜가
        // 통째로 빠질 수 있는데, 그 증상은 실패가 아니라 "그 기간엔 시세가 없었다"로 보인다.
        // 최대 구간(어드민 732일)이라도 종목 둘 × 영업일이면 1,100행 남짓이라 한 페이지에 들어온다 —
        // 그 전제가 깨지는 날 조용히 틀리는 대신 시끄럽게 실패하도록 둔다
        val totalCount = body.path("totalCount").asInt(items.size)
        if (totalCount > items.size) {
            throw FscApiException(
                "TRUNCATED",
                "응답이 잘렸습니다 (전체 ${totalCount}건 중 ${items.size}건) — 구간을 나눠 호출하세요",
            )
        }

        var skipped = 0
        val rows = items.mapNotNull { node ->
            val srtnCd = node.path("srtnCd").asText("")
            val basDt = node.path("basDt").asText("")
            val clpr = node.path("clpr").asText("")

            val quoteDate = runCatching { LocalDate.parse(basDt, DATE_FORMAT) }.getOrNull()
            val price = runCatching { BigDecimal(clpr) }.getOrNull()

            // 종목코드가 없으면 소스가 어느 종목인지 가릴 수 없다 — 통과시키면 미니금이 금으로 섞인다
            if (srtnCd.isBlank() || quoteDate == null || price == null || price.signum() <= 0) {
                skipped++
                log.warn("[FSC] 행 건너뜀 basDt={} srtnCd={} clpr={}", basDt, srtnCd, clpr)
                null
            } else {
                FscGoldRow(
                    srtnCd = srtnCd,
                    quoteDate = quoteDate,
                    price = price,
                    changeValue = decimalOrNull(node.path("vs").asText("")),
                    changeRate = decimalOrNull(node.path("fltRt").asText("")),
                )
            }
        }
        return FscGoldFetch(rows, skipped)
    }

    /** `.05`·`-.19`처럼 앞의 0이 없는 소수도 `BigDecimal(String)`이 그대로 읽는다 */
    private fun decimalOrNull(raw: String): BigDecimal? =
        if (raw.isBlank()) null else runCatching { BigDecimal(raw) }.getOrNull()

    companion object {
        /** 오퍼레이션 경로. 테스트가 "예외 어디에도 이 조각이 없다"로 유출을 본다 */
        internal const val PATH = "/GetGeneralProductInfoService/getGoldPriceInfo"

        /** `resultCode`의 정상값. `NORMAL SERVICE.`가 이 코드로 온다 */
        private const val RESULT_OK = "00"

        /**
         * 한 번에 받는 행 수. 어드민 최대 구간(732일) × 종목 둘 × 영업일이면 1,100행 남짓이라
         * 여유가 두 배 넘는다. 넘치면 위 `TRUNCATED`가 시끄럽게 실패시킨다
         */
        private const val PAGE_SIZE = 3000

        /** `beginBasDt`·`endBasDt`·`basDt` 공통 형식 */
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

        private val DEFAULT_TIMEOUT = Duration.ofSeconds(30)
    }
}
