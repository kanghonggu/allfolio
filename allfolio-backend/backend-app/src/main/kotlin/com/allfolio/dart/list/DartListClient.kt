package com.allfolio.dart.list

import com.allfolio.dart.DartApiException
import com.allfolio.dart.DartProperties
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.client.reactive.ClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** `list.json` 응답 한 행. 저장 전 상태라 정규화(`DartReportName`)는 아직 하지 않았다 */
data class DartListRow(
    val rceptNo: String,
    val corpCode: String,
    val corpName: String,
    val stockCode: String?,
    val corpCls: String?,
    val reportNm: String,
    val rceptDt: LocalDate,
    val flrNm: String?,
    val rm: String?,
)

/** @param emptyResult `status 013` — 공휴일 등으로 정상적으로 결과가 빈 경우 */
data class DartListPage(val rows: List<DartListRow>, val totalPage: Int, val emptyResult: Boolean)

/**
 * OpenDART(전자공시시스템 오픈API) 공시검색 `list.json`.
 *
 * **`status 013`은 실패가 아니다.** 공휴일에 오는 정상 응답이다(2026-08-17 광복절 대체공휴일
 * 실측: `{"status":"013","message":"조회된 데이타가 없습니다."}`). 실패로 올리면 대체공휴일마다
 * 배치가 빨갛게 된다. `000`·`013` 둘 다 아니면 [DartApiException]이다.
 *
 * **`stock_code`는 빈 문자열로 온다.** NULL이 아니다 — 실측 3,273건이 전부 `corp_cls=E`의
 * 빈 문자열이었다. 여기서 null로 정규화하지 않으면 `idx_disclosure_feed`의 부분 인덱스
 * (`WHERE stock_code IS NOT NULL`)가 통째로 무용지물이 된다. `corp_cls`·`flr_nm`·`rm`도 같다.
 *
 * **`report_nm`은 trim만 한다.** `DartReportName`의 접두어 제거·구분자 통일은 여기서 하지
 * 않는다 — Task 8 수집 서비스가 원문을 보존한 채로 그 정규화를 한다.
 *
 * **`rcept_dt`는 `yyyyMMdd`다.** `elestock`(Task 10)은 하이픈 포맷이라 다르다 — 그쪽 파서를
 * 돌려 쓰지 말 것.
 *
 * **🔴 인증키가 쿼리 파라미터(`crtfc_key=`)에 실린다.** `FredApiClient`·`FscCommodityClient`와
 * 같은 방어 셋을 지킨다: 전체 URL을 로그에 찍지 않는다 · 예외에 `cause`를 붙이지 않는다
 * (Reactor checkpoint 프레임에 요청 URI가 통째로 들어 있다) · 응답 본문 미리보기를 남기지 않는다.
 *
 * **키가 비면 호출하지 않고 예외를 던진다.** 조용히 빈 목록을 주면 `status 013`과 구분이 안 돼
 * "키를 안 넣었다"가 "그날 공시가 없었다"로 굳는다.
 */
@Component
class DartListClient(
    private val props: DartProperties,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(props.baseUrl)
            .codecs { it.defaultCodecs().maxInMemorySize(8 * 1024 * 1024) }
            .also { builder -> connector?.let(builder::clientConnector) }
            .build()
    }

    /** HTTP 커넥터. **운영은 null로 두고 기본값을 쓴다** — 근거는 `dedicatedConnector()` 주석 */
    internal var connector: ClientHttpConnector? = null

    /**
     * 응답 대기 상한. 기본은 [DartProperties.timeoutSeconds]다. 테스트에서만 짧게 줄인다 —
     * 근거는 `FredApiClient.timeout`·`FscCommodityClient.timeout`의 주석과 같다.
     */
    internal var timeout: Duration = Duration.ofSeconds(props.timeoutSeconds)

    fun fetchPage(bgnDe: LocalDate, endDe: LocalDate, pageNo: Int): DartListPage {
        // 설정 누락은 상류 장애가 아니라 우리 문제다. 조용히 빈 목록을 주면 status 013(공휴일)과
        // 구분이 안 돼 "키를 안 넣었다"가 "그날 공시가 없었다"로 굳는다
        if (props.apiKey.isBlank()) {
            throw DartApiException("DART_API_KEY가 설정되지 않았습니다")
        }

        // 구간·페이지만 남긴다. 전체 URL을 찍으면 crtfc_key가 그대로 로그에 박힌다
        log.info("[DART] list.json 조회 {}~{} page={}", bgnDe, endDe, pageNo)

        val body = try {
            webClient.get()
                .uri { b ->
                    b.path(PATH)
                        .queryParam("crtfc_key", props.apiKey)
                        .queryParam("bgn_de", bgnDe.format(DATE_FORMAT))
                        .queryParam("end_de", endDe.format(DATE_FORMAT))
                        .queryParam("page_no", pageNo)
                        .queryParam("page_count", props.pageCount)
                        .build()
                }
                .retrieve()
                .bodyToMono(String::class.java)
                .block(timeout)
                ?: throw DartApiException("OpenDART 응답 본문이 비어 있습니다")
        } catch (e: DartApiException) {
            throw e
        } catch (e: WebClientResponseException) {
            // 상태만 남긴다. 본문에는 우리 요청 URL이 되울려 올 수 있고 거기 키가 들어 있다.
            // cause도 붙이지 않는다 — Reactor checkpoint 프레임에 URI가 통째로 있다
            log.warn("[DART] HTTP {}", e.statusCode.value())
            throw DartApiException("OpenDART가 HTTP ${e.statusCode.value()} 를 반환했습니다")
        } catch (e: Throwable) {
            if (e is Error) throw e
            if (e is InterruptedException || e.cause is InterruptedException) Thread.currentThread().interrupt()
            log.warn("[DART] 호출 실패 reason={}", e.javaClass.simpleName)
            throw DartApiException("OpenDART 호출에 실패했습니다")
        }

        // 본문이 JSON이 아니면(점검 안내 HTML 등) Jackson 예외가 원본 본문을 물고 나온다 —
        // 요청 URI가 되울려 오는 경우가 있으므로 여기서 갈아끼운다. cause도 붙이지 않는다
        val node = try {
            objectMapper.readTree(body)
        } catch (e: JsonProcessingException) {
            log.warn("[DART] 응답이 JSON이 아닙니다 reason={}", e.javaClass.simpleName)
            throw DartApiException("OpenDART 응답이 올바른 JSON이 아닙니다")
        }

        return when (val status = node.path("status").asText()) {
            "000" -> DartListPage(
                rows = node.path("list").mapNotNull(::toRow),
                totalPage = node.path("total_page").asInt(0),
                emptyResult = false,
            )
            // 공휴일 정상 응답. message는 싣지 않는다 — 서버가 만든 문자열이라 요청이 되울려 올 수 있다
            "013" -> DartListPage(emptyList(), 0, emptyResult = true)
            else -> throw DartApiException("OpenDART status=$status")
        }
    }

    /** `rcept_no`·`rcept_dt`가 파싱 불가한 행은 버린다(전체를 죽이지 않는다). 실측 0건, 방어적 검사 */
    private fun toRow(n: JsonNode): DartListRow? {
        val rceptNo = n.path("rcept_no").asText("").trim()
        if (rceptNo.isBlank()) {
            log.warn("[DART] rcept_no가 없는 행을 건너뜀")
            return null
        }
        val rceptDt = try {
            LocalDate.parse(n.path("rcept_dt").asText("").trim(), DATE_FORMAT)
        } catch (e: DateTimeParseException) {
            log.warn("[DART] rcept_dt를 읽을 수 없어 건너뜀 rceptNo={}", rceptNo)
            return null
        }
        return DartListRow(
            rceptNo = rceptNo,
            corpCode = n.path("corp_code").asText("").trim(),
            corpName = n.path("corp_name").asText("").trim(),
            stockCode = n.path("stock_code").asText("").trim().ifBlank { null },
            corpCls = n.path("corp_cls").asText("").trim().ifBlank { null },
            reportNm = n.path("report_nm").asText("").trim(),
            rceptDt = rceptDt,
            flrNm = n.path("flr_nm").asText("").trim().ifBlank { null },
            rm = n.path("rm").asText("").trim().ifBlank { null },
        )
    }

    companion object {
        /** 오퍼레이션 경로. 테스트가 "예외 어디에도 이 조각이 없다"로 유출을 본다 */
        internal const val PATH = "/list.json"

        /** `bgn_de`·`end_de`·`rcept_dt` 공통 형식. `elestock`(Task 10)의 하이픈 포맷과 다르다 */
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    }
}
