package com.allfolio.dart.insider

import com.allfolio.dart.DartApiException
import com.allfolio.dart.DartHttpConnector
import com.allfolio.dart.DartProperties
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.client.reactive.ClientHttpConnector
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * `elestock.json` 응답 한 행. 저장 전 상태다 — `DartInsiderTradeEntity`로 옮길 때 `stockCode`·
 * `collectedAt`이 붙는다(이 응답엔 없다).
 */
data class ElestockRow(
    val rceptNo: String,
    val corpCode: String,
    val repror: String,
    val officerPosition: String?,
    val isRegistered: Boolean?,
    val majorHolderType: String?,
    val reportDate: LocalDate,
    val ownedQty: Long?,
    val changeQty: Long?,
    val ownedRate: BigDecimal?,
    val changeRate: BigDecimal?,
)

/**
 * OpenDART(전자공시시스템 오픈API) 임원·주요주주 특정증권등 소유상황보고 `elestock.json`.
 *
 * **변동사유 필드가 없다.** 30개사 3,922행의 필드 집합이 단일(12개)하고 취득/처분 방법에
 * 해당하는 키가 하나도 없다. 그래서 `changeType` 같은 필드를 여기 추가하지 않는다 — 채울
 * 소스가 없다. 무상증자·스톡옵션 행사를 매수로 오표기하면 금융 서비스에서 회복 불가능하다
 * (설계 원칙 3). 화면은 "소유수량 변동" 사실(증감수량·지분율)만 낸다.
 *
 * **기간 파라미터가 없어 회사 전체 이력(약 2년)이 온다.** 실측 최대 3,395행(삼성전자,
 * 2024-08-20~2026-08-14). 신규 필터는 이 클라이언트가 아니라 호출자(수집 서비스)가 델타
 * `rcept_no`로 건다 — 여기는 받은 것을 그대로 준다.
 *
 * **결측은 `"-"`로 온다.** `officerPosition`·`isRegistered`·`majorHolderType` 셋 다. 빈
 * 문자열이 아니라 하이픈이고, 그대로 저장하면 화면에 하이픈이 나간다 — [dash]에서 null로 정규화한다.
 *
 * **`isRegistered`는 3-값이다.** `등기임원`→true, `비등기임원`→false, `"-"`(결측)→null.
 * non-null Boolean으로 접으면 결측(실측 125건)이 false(비등기)로 둔갑한다.
 *
 * **`majorHolderType`은 원문을 그대로 보존한다.** 값이 `10%이상주주`·`사실상지배주주` 두
 * 종이라 불리언으로 접으면 그 구분 정보가 사라진다.
 *
 * **수량은 콤마 낀 문자열로 온다.** `"47,971,200"`, `"-500"`(음수 증감도 있다) — 콤마를
 * 제거한 뒤 파싱한다.
 *
 * **`0`과 `null`을 혼동하지 않는다.** 지분율 0.005 미만은 OpenDART가 이미 `"0.00"`으로 준다
 * (실측 6행). 이 클라이언트는 원문 문자열을 그대로 `BigDecimal`로 넘기고 **미리 `setScale`하지
 * 않는다** — `NUMERIC(7,2)` 반올림은 DB에 맡긴다. 이 레포엔 `0`(무변동)과 `null`(값 없음)을
 * 혼동해 사고 난 전례가 있다 — `"0.00"`을 null로 접으면 안 된다.
 *
 * **`rcept_dt`가 하이픈 포맷(`2024-10-08`)이다.** `list.json`(`DartListClient`)은
 * `20260818`(하이픈 없음)이다 — **같은 이름 필드에 포맷이 다르다.** `DartListClient`의
 * `yyyyMMdd` 파서를 돌려 쓰면 깨진다. `LocalDate.parse`의 ISO 기본 포맷이 그대로 맞는다.
 *
 * **`status "013"`은 실패가 아니라 빈 목록이다.** 공휴일·무자료 응답
 * (`{"status":"013","message":"조회된 데이타가 없습니다."}`) — `000`·`013` 외는 [DartApiException]이다.
 *
 * **🔴 인증키가 쿼리 파라미터(`crtfc_key=`)에 실린다.** `DartListClient`·`DartCorpCodeClient`와
 * 같은 방어 셋을 지킨다: 전체 URL을 로그에 찍지 않는다 · 예외에 `cause`를 붙이지 않는다
 * (Reactor checkpoint 프레임에 요청 URI가 통째로 들어 있다) · 응답 본문 미리보기를 남기지 않는다.
 *
 * **키가 비면 호출하지 않고 예외를 던진다.** 조용히 빈 목록을 주면 `status 013`과 구분이 안 돼
 * "키를 안 넣었다"가 "그날 이력이 없었다"로 굳는다.
 */
@Component
class DartElestockClient(
    private val props: DartProperties,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(props.baseUrl)
            // **커넥터를 명시한다.** reactor-netty 기본 암호군에는 DHE가 없고 OpenDART는
            // ECDHE를 전부 거절해 교집합이 빈다 — 근거는 [DartHttpConnector] KDoc
            .clientConnector(connector ?: DartHttpConnector.create())
            .build()
    }

    /** HTTP 커넥터. **운영은 null로 두고 기본값을 쓴다** — 근거는 `dedicatedConnector()` 주석 */
    internal var connector: ClientHttpConnector? = null

    /** 응답 대기 상한. 기본은 [DartProperties.timeoutSeconds]다. 테스트에서만 짧게 줄인다 */
    internal var timeout: Duration = Duration.ofSeconds(props.timeoutSeconds)

    fun fetch(corpCode: String): List<ElestockRow> {
        // 설정 누락은 상류 장애가 아니라 우리 문제다. 조용히 빈 목록을 주면 status 013(무자료)과
        // 구분이 안 돼 "키를 안 넣었다"가 "이력이 없었다"로 굳는다
        if (props.apiKey.isBlank()) {
            throw DartApiException("DART_API_KEY가 설정되지 않았습니다")
        }

        // corp_code만 남긴다. 전체 URL을 찍으면 crtfc_key가 그대로 로그에 박힌다
        log.info("[DART] elestock.json 조회 corpCode={}", corpCode)

        val body = try {
            webClient.get()
                .uri { b ->
                    b.path(PATH)
                        .queryParam("crtfc_key", props.apiKey)
                        .queryParam("corp_code", corpCode)
                        .build()
                }
                .retrieve()
                .bodyToMono(String::class.java)
                .block(timeout)
                ?: throw DartApiException("elestock 응답 본문이 비어 있습니다")
        } catch (e: DartApiException) {
            throw e
        } catch (e: WebClientResponseException) {
            // 상태만 남긴다. 본문에는 우리 요청 URL이 되울려 올 수 있고 거기 키가 들어 있다.
            // cause도 붙이지 않는다 — Reactor checkpoint 프레임에 URI가 통째로 있다
            log.warn("[DART] elestock HTTP {}", e.statusCode.value())
            throw DartApiException("OpenDART가 HTTP ${e.statusCode.value()} 를 반환했습니다")
        } catch (e: Throwable) {
            if (e is Error) throw e
            if (e is InterruptedException || e.cause is InterruptedException) Thread.currentThread().interrupt()
            log.warn("[DART] elestock 호출 실패 reason={}", e.javaClass.simpleName)
            throw DartApiException("elestock 호출에 실패했습니다")
        }

        // 본문이 JSON이 아니면(점검 안내 HTML 등) Jackson 예외가 원본 본문을 물고 나온다 —
        // 요청 URI가 되울려 오는 경우가 있으므로 여기서 갈아끼운다. cause도 붙이지 않는다
        val node = try {
            objectMapper.readTree(body)
        } catch (e: JsonProcessingException) {
            log.warn("[DART] elestock 응답이 JSON이 아닙니다 reason={}", e.javaClass.simpleName)
            throw DartApiException("elestock 응답이 올바른 JSON이 아닙니다")
        }

        return when (val status = node.path("status").asText()) {
            "000" -> node.path("list").mapNotNull(::toRow)
            // 무자료 정상 응답(공휴일 등). message는 싣지 않는다 — 서버가 만든 문자열이라 요청이 되울려 올 수 있다
            "013" -> emptyList()
            else -> throw DartApiException("elestock status=$status")
        }
    }

    /** `rcept_no`나 `rcept_dt`가 파싱 불가한 행은 버린다(전체를 죽이지 않는다) */
    private fun toRow(n: JsonNode): ElestockRow? {
        val rceptNo = n.path("rcept_no").asText("").trim()
        if (rceptNo.isBlank()) {
            log.warn("[DART] elestock rcept_no가 없는 행을 건너뜀")
            return null
        }
        val reportDate = try {
            // elestock의 rcept_dt는 하이픈 포맷이다(list.json의 yyyyMMdd와 다르다) — ISO 기본 파서를 쓴다
            LocalDate.parse(n.path("rcept_dt").asText("").trim())
        } catch (e: DateTimeParseException) {
            log.warn("[DART] elestock rcept_dt를 읽을 수 없어 건너뜀 rceptNo={}", rceptNo)
            return null
        }
        return ElestockRow(
            rceptNo = rceptNo,
            corpCode = n.path("corp_code").asText("").trim(),
            repror = n.path("repror").asText("").trim(),
            officerPosition = n.dash("isu_exctv_ofcps"),
            isRegistered = when (n.dash("isu_exctv_rgist_at")) {
                "등기임원" -> true
                "비등기임원" -> false
                else -> null
            },
            majorHolderType = n.dash("isu_main_shrholdr"),
            reportDate = reportDate,
            ownedQty = n.longOrNull("sp_stock_lmp_cnt"),
            changeQty = n.longOrNull("sp_stock_lmp_irds_cnt"),
            ownedRate = n.decimalOrNull("sp_stock_lmp_rate"),
            changeRate = n.decimalOrNull("sp_stock_lmp_irds_rate"),
        )
    }

    /** 결측은 `"-"`로 온다. 그대로 저장하면 화면에 하이픈이 나간다 */
    private fun JsonNode.dash(field: String): String? =
        path(field).asText("").trim().takeIf { it.isNotBlank() && it != "-" }

    /** 콤마 낀 문자열(`"47,971,200"`, `"-500"`)을 정수로. 파싱 불가 시 null */
    private fun JsonNode.longOrNull(field: String): Long? =
        path(field).asText("").replace(",", "").trim().toLongOrNull()

    /**
     * 콤마 낀 문자열을 [BigDecimal]로. **여기서 `setScale`하지 않는다** — `"0.00"`은 무변동이지
     * 결측이 아니고, `NUMERIC(7,2)` 반올림은 DB에 맡긴다.
     */
    private fun JsonNode.decimalOrNull(field: String): BigDecimal? =
        path(field).asText("").replace(",", "").trim().toBigDecimalOrNull()

    companion object {
        /** 오퍼레이션 경로. 테스트가 "예외 어디에도 이 조각이 없다"로 유출을 본다 */
        internal const val PATH = "/elestock.json"
    }
}
