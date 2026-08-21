package com.allfolio.market.benchmark

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
 * 금융위원회 지수시세정보 — 주식시장 지수 일별 시세 (`getStockMarketIndex`).
 *
 * 인증·베이스·응답 두 겹(`response.body.items.item[]`)·키 방어는 같은 기관의 다른 오퍼레이션을
 * 부르는 [com.allfolio.market.commodity.fsc.FscCommodityClient]와 **같다** — 예외 타입까지
 * 그쪽 것을 그대로 쓴다. 같은 포털의 같은 실패(`NO_KEY`·`RESULT-nn`·`HTTP-nnn`·`MALFORMED`·`IO`)를
 * 이름만 다른 클래스로 한 벌 더 만들 이유가 없다.
 *
 * **다른 것 하나: 응답을 `(idxNm, idxCsf)` 쌍으로 다시 거른다.**
 * 금은 한 응답에 섞여 오는 종목을 소스가 골랐지만(`srtnCd`), 지수는 여기서 고른다 —
 * 쿼리에 `idxNm`을 실어 서버가 줄여 주기는 하지만 **그 필터를 믿지 않는다.** `idxNm`은
 * 유일하지 않아서(`"IT 서비스"`가 KOSPI시리즈·KOSDAQ시리즈에 둘 다 있다) 서버 필터가
 * 정확 일치라도 시리즈가 갈리지 않고, 틀린 시리즈의 값도 그럴듯한 지수 숫자라
 * **잘못 저장돼도 아무도 못 알아챈다.**
 *
 * **등락률(`fltRt`)·전일대비(`vs`)는 읽지 않는다.** 반환이 (날짜, 종가) 쌍이라 실을 곳이 없다 —
 * 안 읽는 것이 방어다. 그 값들은 `.05`·`-.89`처럼 앞의 0 없이 오거나 `-`·빈 문자열로 오는데,
 * 나중에 싣기로 하는 날 `BigDecimal(...)`을 그냥 부르면 **쓰지도 않는 필드 때문에 멀쩡한
 * 종가가 통째로 버려진다.** 그때는 `FscCommodityClient.decimalOrNull`을 그대로 가져올 것.
 *
 * **🔴 인증키가 쿼리 파라미터(`serviceKey=`)에 실린다.** `FscCommodityClient`와 같은 방어 셋을
 * 지킨다: 전체 URL을 로그에 찍지 않는다 · 예외에 `cause`를 붙이지 않는다(Reactor의 checkpoint
 * 프레임에 요청 URI가 통째로 들어 있다) · 응답 본문 미리보기를 남기지 않는다(기본 오류 페이지가
 * 요청 URI를 되울려 렌더링한다). 이 예외 메시지는 수집 요약을 타고 어드민 응답과
 * GitHub Actions 주석까지 나가는 값이다.
 */
@Component
class FscIndexClient(
    @Value("\${fsc.api-key:}") private val apiKey: String,
    // 기본값은 FscCommodityClient·FscStockClient가 쓰는 것과 같은 주소다. 애너테이션 인자는
    // 컴파일 상수여야 해서 상수 참조로 묶지 못한다 — 주소를 고칠 땐 세 파일을 같이 볼 것
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

    /** `FscCommodityClient`의 관례. 다만 이 클라이언트도 false를 예외로 바꾼다 — 클래스 KDoc 참조 */
    fun isConfigured(): Boolean = apiKey.isNotBlank()

    /**
     * `from..to`(**포함**) 구간에서 [item]이 가리키는 지수의 일별 종가를 가져온다 —
     * **포털 파라미터는 그 반대라 아래에서 하루를 더한다**(`endBasDt` 주석 참조).
     *
     * 반환이 `List<Pair<LocalDate, BigDecimal>>`인 것은 우연이 아니다 —
     * `BenchmarkDailyStore.upsert`가 받는 타입 그대로라 중간 변환이 없다.
     * 순서는 응답 순서(최신 → 과거)를 그대로 둔다. upsert가 순서를 안 보기 때문이다.
     */
    fun fetch(
        item: BenchmarkIndexProperties.BenchmarkIndexItem,
        from: LocalDate,
        to: LocalDate,
    ): List<Pair<LocalDate, BigDecimal>> {
        // 설정 누락은 상류 장애가 아니라 우리 문제다 — 사유가 남아야 운영자가 Render 환경변수를 보러 간다
        if (!isConfigured()) {
            throw FscApiException("NO_KEY", "공공데이터포털 인증키가 설정되지 않았습니다 (FSC_API_KEY)")
        }

        // 지수 이름과 구간만 남긴다. 전체 URL을 찍으면 serviceKey가 그대로 로그에 박힌다
        log.info("[FSC] 지수시세 조회 {}({}) {}~{}", item.idxNm, item.idxCsf, from, to)

        val body = try {
            webClient.get()
                .uri { b ->
                    b.path(PATH)
                        .queryParam("serviceKey", apiKey)
                        .queryParam("resultType", "json")
                        .queryParam("numOfRows", PAGE_SIZE)
                        .queryParam("pageNo", 1)
                        // 서버 필터. **응답을 줄이려는 것이지 정확성의 근거가 아니다** —
                        // 정확성은 아래 (idxNm, idxCsf) 재검증이 책임진다
                        .queryParam("idxNm", item.idxNm)
                        // **`yyyyMMdd`다. ISO(`yyyy-MM-dd`)가 아니다** —
                        // LocalDate.toString()을 그대로 넘기면 조용히 0건이 된다
                        .queryParam("beginBasDt", DATE_FORMAT.format(from))
                        // **🔴 하루를 더하는 것은 `endBasDt`가 배타적이기 때문이다 — 지우지 말 것.**
                        // 활용가이드가 `endBasDt`를 "기준일자가 검색값보다 **작은** 데이터를 검색"으로
                        // 정의한다(`beginBasDt`만 "크거나 같은"). **이 오퍼레이션으로 직접 쟀다**
                        // (2026-08-21, 운영 키, `idxNm=코스피`):
                        //   `beginBasDt=endBasDt=20260819` → `resultCode=00` · `totalCount=0`
                        //   `beginBasDt=20260819&endBasDt=20260820` → `basDt=20260819`
                        // **뒤 호출이 대조군이다** — 그게 없으면 앞의 0건이 "배타적"인지 "그날 값이
                        // 없다"인지 못 가른다. (금시세 `getGoldPriceInfo`도 같은 날 같은 결과였다.)
                        //
                        // 안 더하면 마지막 날이 조용히 빠진다: 기본 창(14일)은 다음 실행이 메우지만,
                        // 백필은 끝날을 잃고 `from == to` 조회는 언제나 0건이다.
                        .queryParam("endBasDt", DATE_FORMAT.format(to.plusDays(1)))
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
            parse(body, item)
        } catch (e: JsonProcessingException) {
            log.warn("[FSC] 응답이 JSON이 아닙니다 reason={}", e.javaClass.simpleName)
            throw FscApiException("MALFORMED", "응답 본문이 올바른 JSON이 아닙니다")
        }
    }

    private fun parse(
        json: String,
        item: BenchmarkIndexProperties.BenchmarkIndexItem,
    ): List<Pair<LocalDate, BigDecimal>> {
        val response = objectMapper.readTree(json).path("response")

        // **HTTP 200에 실려 오는 실패가 있다.** 등록되지 않은 키·트래픽 초과가 그렇고, 그때
        // items는 비어 있다 — 코드를 안 보면 "휴장이라 시세가 없다"와 구별할 수 없어
        // 요약이 초록인 채 KOSPI만 영원히 안 쌓인다. resultMsg는 싣지 않는다(서버가 만든
        // 문자열이라 요청 URL이 되울려 올 수 있다)
        //
        // **빈 코드를 통과시키는 것은 의도다** — 근거는 `FscCommodityClient.parse`의 주석과 같다.
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
        val nodes: List<JsonNode> = when {
            itemNode.isArray -> itemNode.toList()
            itemNode.isObject -> listOf(itemNode)
            else -> emptyList()
        }
        if (nodes.isEmpty()) return emptyList()

        // **잘린 응답을 조용히 넘기지 않는다.** 페이지를 하나만 받으므로 구간이 넓으면 뒷날짜가
        // 통째로 빠질 수 있는데, 그 증상은 실패가 아니라 "그 기간엔 시세가 없었다"로 보인다.
        // 1년 조회가 242영업일을 한 페이지로 주는 것은 실측했다(서버가 idxNm으로 줄여 준다) —
        // 그 전제가 깨지는 날 조용히 틀리는 대신 시끄럽게 실패하도록 둔다.
        // **필터 전 행 수로 본다** — 잘림은 우리가 고른 지수와 무관한 서버 사정이다
        val totalCount = body.path("totalCount").asInt(nodes.size)
        if (totalCount > nodes.size) {
            throw FscApiException(
                "TRUNCATED",
                "응답이 잘렸습니다 (전체 ${totalCount}건 중 ${nodes.size}건) — 구간을 나눠 호출하세요",
            )
        }

        var skipped = 0
        val rows = nodes.mapNotNull { node ->
            // **여기가 이 클래스의 핵심이다.** 이름이 같아도 시리즈가 다르면 다른 지수다 —
            // 쿼리에 idxNm을 실었어도 서버 필터를 믿지 않는다
            if (node.path("idxNm").asText("") != item.idxNm) return@mapNotNull null
            if (node.path("idxCsf").asText("") != item.idxCsf) return@mapNotNull null

            val basDt = node.path("basDt").asText("")
            val clpr = node.path("clpr").asText("")

            val quoteDate = runCatching { LocalDate.parse(basDt, DATE_FORMAT) }.getOrNull()
            val close = decimalOrNull(clpr)

            if (quoteDate == null || close == null || close.signum() <= 0) {
                skipped++
                log.warn("[FSC] 행 건너뜀 idxNm={} basDt={} clpr={}", item.idxNm, basDt, clpr)
                null
            } else {
                quoteDate to close
            }
        }

        // 0이 아닌 skipped가 "형식이 바뀌었다"는 신호가 된다. 반환 타입이 (날짜, 종가) 쌍이라
        // 이 수를 실어 보낼 자리가 없어 로그로만 남긴다
        if (skipped > 0) log.warn("[FSC] {} 행 {}건을 건너뛰었습니다", item.type, skipped)

        return rows
    }

    /**
     * `-`·빈 문자열 같은 값이 섞여도 예외 대신 null로 돌린다.
     * `FscCommodityClient.decimalOrNull`과 같은 것이다 —
     * `.05`·`-.19`처럼 앞의 0이 없는 소수는 `BigDecimal(String)`이 그대로 읽는다.
     */
    private fun decimalOrNull(raw: String): BigDecimal? =
        if (raw.isBlank()) null else runCatching { BigDecimal(raw) }.getOrNull()

    companion object {
        /** 오퍼레이션 경로. 테스트가 "예외 어디에도 이 조각이 없다"로 유출을 본다 */
        internal const val PATH = "/GetMarketIndexInfoService/getStockMarketIndex"

        /** `resultCode`의 정상값. `NORMAL SERVICE.`가 이 코드로 온다 */
        private const val RESULT_OK = "00"

        /**
         * 한 번에 받는 행 수. `idxNm`으로 줄인 1년 조회가 242행(영업일)이라 여유가 열 배 넘는다.
         * 넘치면 위 `TRUNCATED`가 시끄럽게 실패시킨다
         */
        private const val PAGE_SIZE = 3000

        /** `beginBasDt`·`endBasDt`·`basDt` 공통 형식 */
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

        private val DEFAULT_TIMEOUT = Duration.ofSeconds(30)
    }
}
