package com.allfolio.fx

import com.fasterxml.jackson.core.JsonProcessingException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * ECOS 한 번 조회에 필요한 것 — 어느 시계열을 볼지(statCode·itemCode·cycle)와
 * 그 결과를 어떻게 받아들일지(valuePolicy). 둘 다 같은 설정 한 행에서 나오므로 묶는다.
 *
 * @param cycle ECOS 주기 코드. 현재 지원은 `D`뿐이다 — 다른 주기는 요청 날짜 형식과
 *              응답 `TIME` 형식이 함께 바뀌므로, 확인되지 않은 채 넓히면 조용히 0건이 된다.
 * @param valuePolicy 어떤 값을 받아들일지. 환율과 금리가 다르다 — [EcosValuePolicy] 참조
 */
data class EcosQuery(
    val statCode: String,
    val itemCode: String,
    val cycle: String,
    val valuePolicy: EcosValuePolicy,
) {
    companion object {
        /**
         * 현재 유일하게 지원하는 주기. [EcosHistoricalRateSource]·클라이언트(이 파일)·
         * `MarketRateProperties`(`market-rate` 모듈)가 같이 참조한다 — 리터럴 중복 방지.
         */
        internal const val DAILY_CYCLE = "D"
    }
}

interface EcosApiClient {
    /** 지정 기간의 통계를 가져온다. 실패하면 예외를 던진다 — 호출자가 기존 값을 지키도록. */
    fun fetch(query: EcosQuery, from: LocalDate, to: LocalDate): EcosParseResult
}

/**
 * ECOS StatisticSearch REST 호출.
 *
 * URL 형식:
 *   /api/StatisticSearch/{인증키}/json/kr/{시작건수}/{종료건수}/{통계표}/{주기}/{시작일}/{종료일}/{항목1}
 *
 * 인증키가 URL 경로에 들어가므로 로그에 전체 URL을 찍지 않는다.
 */
@Component
class EcosStatisticSearchClient(
    private val properties: EcosProperties,
    private val parser: EcosResponseParser,
) : EcosApiClient {

    private val log = LoggerFactory.getLogger(javaClass)

    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl(properties.baseUrl)
            .codecs { it.defaultCodecs().maxInMemorySize(8 * 1024 * 1024) }
            .build()
    }

    /**
     * 응답 대기 상한. 테스트에서만 줄인다 — 기본 30초를 매번 태우면 타임아웃 경로를 검증할 수 없다.
     * 프로덕션에서 바꿀 값이 아니라 설정으로 빼지 않았다.
     */
    internal var timeout: Duration = DEFAULT_TIMEOUT

    companion object {
        private val DEFAULT_TIMEOUT = Duration.ofSeconds(30)
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")

        /**
         * ECOS 1회 요청 상한. 일별 10년치가 약 2,600행이라 한 번에 받는다.
         * 실제 상한은 이 행 수가 아니라 위 maxInMemorySize(8MB)이며 대략 2만 행에서 먼저 걸린다 —
         * 둘 다 실사용(수천 행)에서는 닿지 않으므로 낮은 쪽에 맞춰 줄이지 않고 그대로 둔다.
         */
        private const val MAX_ROWS = 100_000

        /** 실패 시 로그에 남길 응답 앞부분 길이. 본문 전체는 남기지 않는다(길고, 되울린 URI가 섞인다). */
        private const val BODY_PREVIEW_LENGTH = 200

        /**
         * 되울려 온 우리 요청 URI. 경로 첫 세그먼트가 인증키라 통째로 지운다.
         * 공백·따옴표·꺾쇠에서 멈춘다 — HTML 안에 박혀 와도 뒤따르는 마크업까지 먹지 않도록.
         */
        private val REQUEST_PATH = Regex("""/api/StatisticSearch[^\s<>"']*""")
    }

    /**
     * 응답 본문 미리보기를 안전하게 만든다.
     *
     * 본문은 서버가 준 내용이라 우리 인증키가 실려 돌아올 수 있다 — Tomcat 기본 오류 페이지는
     * 요청 URI를 그대로 렌더링하고, 인증키는 그 URI 경로에 들어 있다.
     * 자르기 전에 지운다: 먼저 자르면 경계에 걸친 키 조각이 남는다.
     *
     * 두 단계를 거친다:
     *   1. 설정된 키 문자열 마스킹([maskEcosApiKey]) — 키가 경로 밖에 단독으로 실려 올 때를 잡는다
     *      ("등록되지 않은 인증키입니다: XXX" 같은 오류 메시지). [EcosStatListClient]와 공유한다
     *   2. 되울려 온 요청 URI 통째 제거 — 1번은 **정확히 일치**할 때만 들어서
     *      퍼센트 인코딩된 키(KEY%2DZZTOP)를 놓치기 때문이다. 경로를 통으로 지우면 인코딩 형태와 무관하게 사라진다.
     *      이 단계가 여기에만 있는 이유는 **자르기** 때문이다 — 절단은 경계에 키 조각을 남기므로
     *      인코딩까지 막아야 하지만, 본문을 원형 그대로 돌려주는 쪽에는 필요도 없고 해롭다.
     *
     * 남는 잔여 위험: 경로 밖에서 인코딩된 채로 실려 오는 키. 그래서 예외 메시지에는 본문을 아예 싣지 않고
     * (로그에만 남긴다) 이 함수는 마지막 방벽이 아니라 심층 방어로 둔다. 로그는 Render 대시보드로 나간다.
     */
    private fun preview(raw: String): String =
        maskEcosApiKey(raw, properties.apiKey).replace(REQUEST_PATH, "[요청 URI 생략]").take(BODY_PREVIEW_LENGTH)

    override fun fetch(query: EcosQuery, from: LocalDate, to: LocalDate): EcosParseResult {
        // 설정 누락은 서버 문제다. IllegalArgumentException으로 던지면 GlobalExceptionHandler가
        // 400 Bad Request로 내보내 클라이언트 잘못처럼 보인다.
        if (properties.apiKey.isBlank()) {
            throw EcosApiException("NO_KEY", "ECOS 인증키가 설정되지 않았습니다 (ECOS_API_KEY)")
        }
        if (query.statCode.isBlank() || query.itemCode.isBlank()) {
            throw EcosApiException("NO_SERIES", "ECOS 통계표·항목 코드가 설정되지 않았습니다")
        }
        // 아래 DATE_FORMAT(yyyyMMdd)과 파서의 TIME 해석이 둘 다 일별 전제다.
        // 다른 주기를 통과시키면 ECOS가 0건을 돌려주고, 그건 "코드가 틀렸다"와 구분되지 않는다.
        if (query.cycle != EcosQuery.DAILY_CYCLE) {
            throw EcosApiException("CYCLE", "지원하지 않는 주기입니다: ${query.cycle} (현재 D만 지원)")
        }

        val path = "/api/StatisticSearch/${properties.apiKey}/json/kr/1/$MAX_ROWS/" +
            "${query.statCode}/${query.cycle}/${from.format(DATE_FORMAT)}/${to.format(DATE_FORMAT)}/${query.itemCode}"

        log.info("[ECOS] 조회 statCode={} itemCode={} {}~{}", query.statCode, query.itemCode, from, to)

        val body = try {
            webClient.get()
                .uri(path)
                .retrieve()
                .bodyToMono(String::class.java)
                .block(timeout)
                ?: throw EcosApiException("EMPTY", "응답 본문이 비어 있습니다")
        } catch (e: WebClientResponseException) {
            // WebClientResponseException의 메시지·스택에는 인증키가 박힌 전체 URI가 들어 있다.
            // 호출자가 예외를 그대로 로깅해도 키가 새지 않도록 여기서 갈아끼운다 — 그래서 cause도 붙이지 않는다.
            val status = e.statusCode.value()
            if (e.statusCode.is2xxSuccessful) {
                // 2xx인데 이 예외가 나왔다는 건 본문을 못 읽었다는 뜻이다(코덱 8MB 초과, 중간 절단 등).
                // "HTTP 200 실패"로 보고하면 운영자가 한국은행 쪽을 보게 되므로 코드를 따로 준다 —
                // Task 11은 code만 받으므로 여기서 구분하지 않으면 구분할 방법이 없다.
                log.warn("[ECOS] 응답 본문 디코드 실패 status={} statCode={} reason={}", status, query.statCode, e.javaClass.simpleName)
                throw EcosApiException("DECODE", "응답 본문을 읽지 못했습니다 (status=$status)")
            }
            log.warn("[ECOS] HTTP {} statCode={} preview={}", status, query.statCode, preview(e.responseBodyAsString))
            throw EcosApiException("HTTP-$status", "ECOS가 HTTP $status 를 반환했습니다")
        } catch (e: WebClientRequestException) {
            // 전송 계층 실패(connection refused·DNS·TLS·reset). 외부 정부 API라 4xx보다 오히려 흔하다.
            // Reactor가 붙이는 *__checkpoint suppressed 예외에 요청 URI가 통째로 들어 있어 여기도 인증키가 샌다.
            // 응답 경로와 같은 이유로 cause를 붙이지 않고, 원인은 클래스 이름만 남긴다.
            log.warn("[ECOS] 연결 실패 statCode={} reason={}", query.statCode, e.cause?.javaClass?.simpleName)
            throw EcosApiException("CONN", "ECOS 연결에 실패했습니다")
        } catch (e: EcosApiException) {
            throw e // 위 EMPTY. 우리가 만든 예외라 이미 깨끗하다.
        } catch (e: Throwable) {
            // 예외 종류를 열거하는 건 블랙리스트라 새 경로가 생길 때마다 샌다. 그래서 남은 전부를 갈아끼운다.
            // 실제로 여기 걸리는 건 block(timeout)의 IllegalStateException이다 —
            // 이건 Reactor가 BlockingSingleSubscriber에서 새로 만들어 던지므로 checkpoint 프레임이 없지만,
            // 그래도 EcosApiException으로 통일해 호출자가 한 종류만 다루게 한다.
            // (DataBufferLimitException은 여기 오지 않는다. 실측 결과 WebClientResponseException으로 감싸여
            //  위 2xx 분기에서 DECODE로 처리된다.)
            if (e is Error) {
                // OutOfMemoryError를 "ECOS 호출 실패"로 둔갑시키면 운영자를 한국은행 쪽으로 보낸다.
                // 치명 오류는 Reactor checkpoint 기계를 타지 않으므로 그대로 올려도 유출되지 않는다.
                throw e
            }
            if (e is InterruptedException || e.cause is InterruptedException) {
                // 실측: 현재 reactor-core는 block()에서 이미 플래그를 복원해 준다. 이 줄은 그 위에 덧대는
                // 멱등한 재확인이다 — 인터럽트 보존이 리액터 내부 구현에 의존하지 않게 못 박는다.
                // 플래그가 지워지면 종료 중 끊긴 백필이 ECOS 장애로 읽히고 Task 10 루프가 다음 통화로 넘어간다.
                Thread.currentThread().interrupt()
            }
            log.warn("[ECOS] 호출 실패 statCode={} reason={}", query.statCode, e.javaClass.simpleName)
            throw EcosApiException("IO", "ECOS 호출에 실패했습니다")
        }

        // 파서는 JSON 모양이 어긋난 경우만 EcosApiException으로 보고한다.
        // 본문이 아예 JSON이 아니면(HTML 오류 페이지 등) Jackson 예외가 그대로 새므로 여기서 감싼다.
        return try {
            parser.parse(body, query.valuePolicy)
        } catch (e: EcosApiException) {
            // 파서는 RESULT.MESSAGE를 그대로 detail에 넣는다. 그건 서버가 준 문자열이라
            // 우리 요청 URI가 되울려 올 수 있고 길이 제한도 없다 — 본문 미리보기와 똑같은 경로다.
            // code는 우리가 분기에 쓰므로 보존하고 detail만 마스킹·절단한다.
            // (위 NO_KEY·NO_SERIES·CYCLE은 우리가 만든 문자열이고 try 밖에 있어 여기 오지 않는다.)
            throw EcosApiException(e.code, preview(e.detail))
        } catch (e: JsonProcessingException) {
            // 본문 미리보기는 우리 로그에만 남기고 예외에는 싣지 않는다. 예외 메시지는 어드민 응답까지
            // 흘러가는데, 본문은 서버가 준 내용이라 우리 요청 URI가 되울려 올 수 있기 때문이다.
            // 마스킹은 설정된 키 문자열과 정확히 일치할 때만 듣는다(URL 인코딩·부분 반향은 못 잡는다) —
            // 그래서 마스킹에만 기대지 않고 예외에서 본문 자체를 뺀다.
            // cause도 붙이지 않는다: JsonParseException 메시지가 원본 본문을 [Source: (String)"..."]로 물고 있다.
            log.warn(
                "[ECOS] 응답이 JSON이 아닙니다 statCode={} reason={} preview={}",
                query.statCode, e.javaClass.simpleName, preview(body),
            )
            throw EcosApiException("MALFORMED", "응답 본문이 올바른 JSON이 아닙니다")
        }
    }
}
