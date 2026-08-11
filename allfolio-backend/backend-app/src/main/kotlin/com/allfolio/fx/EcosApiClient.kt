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

interface EcosApiClient {
    /** 지정 기간의 일별 통계를 가져온다. 실패하면 예외를 던진다 — 호출자가 기존 값을 지키도록. */
    fun fetchDailyRates(
        statCode: String,
        itemCode: String,
        from: LocalDate,
        to: LocalDate,
    ): EcosParseResult
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

    companion object {
        private val TIMEOUT = Duration.ofSeconds(30)
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")

        /**
         * ECOS 1회 요청 상한. 일별 10년치가 약 2,600행이라 한 번에 받는다.
         * 실제 상한은 이 행 수가 아니라 위 maxInMemorySize(8MB)이며 대략 2만 행에서 먼저 걸린다 —
         * 둘 다 실사용(수천 행)에서는 닿지 않으므로 낮은 쪽에 맞춰 줄이지 않고 그대로 둔다.
         */
        private const val MAX_ROWS = 100_000

        /** 실패 시 로그에 남길 응답 앞부분 길이. 본문 전체는 남기지 않는다(길고, 되울린 URI가 섞인다). */
        private const val BODY_PREVIEW_LENGTH = 200
    }

    /**
     * 응답 본문 미리보기를 안전하게 만든다.
     *
     * 본문은 서버가 준 내용이라 우리 인증키가 실려 돌아올 수 있다 — Tomcat 기본 오류 페이지는
     * 요청 URI를 그대로 렌더링하고, 인증키는 그 URI 경로에 들어 있다.
     * 자르기 전에 지운다: 먼저 자르면 경계에 걸친 키 조각이 남는다.
     */
    private fun preview(raw: String): String {
        // 빈 문자열로 replace하면 문자 사이마다 마스크가 끼어든다. 키가 없으면 가릴 것도 없다.
        val masked = if (properties.apiKey.isBlank()) raw else raw.replace(properties.apiKey, "***")
        return masked.take(BODY_PREVIEW_LENGTH)
    }

    override fun fetchDailyRates(
        statCode: String,
        itemCode: String,
        from: LocalDate,
        to: LocalDate,
    ): EcosParseResult {
        // 설정 누락은 서버 문제다. IllegalArgumentException으로 던지면 GlobalExceptionHandler가
        // 400 Bad Request로 내보내 클라이언트 잘못처럼 보인다.
        if (properties.apiKey.isBlank()) {
            throw EcosApiException("NO_KEY", "ECOS 인증키가 설정되지 않았습니다 (ECOS_API_KEY)")
        }
        if (statCode.isBlank() || itemCode.isBlank()) {
            throw EcosApiException("NO_SERIES", "ECOS 통계표·항목 코드가 설정되지 않았습니다")
        }

        val path = "/api/StatisticSearch/${properties.apiKey}/json/kr/1/$MAX_ROWS/" +
            "$statCode/D/${from.format(DATE_FORMAT)}/${to.format(DATE_FORMAT)}/$itemCode"

        log.info("[ECOS] 조회 statCode={} itemCode={} {}~{}", statCode, itemCode, from, to)

        val body = try {
            webClient.get()
                .uri(path)
                .retrieve()
                .bodyToMono(String::class.java)
                .block(TIMEOUT)
                ?: throw EcosApiException("EMPTY", "응답 본문이 비어 있습니다")
        } catch (e: WebClientResponseException) {
            // WebClientResponseException의 메시지·스택에는 인증키가 박힌 전체 URI가 들어 있다.
            // 호출자가 예외를 그대로 로깅해도 키가 새지 않도록 여기서 갈아끼운다 — 그래서 cause도 붙이지 않는다.
            val status = e.statusCode.value()
            log.warn("[ECOS] HTTP {} statCode={} preview={}", status, statCode, preview(e.responseBodyAsString))
            throw EcosApiException("HTTP-$status", "ECOS가 HTTP $status 를 반환했습니다")
        } catch (e: WebClientRequestException) {
            // 전송 계층 실패(connection refused·DNS·TLS·reset). 외부 정부 API라 4xx보다 오히려 흔하다.
            // Reactor가 붙이는 *__checkpoint suppressed 예외에 요청 URI가 통째로 들어 있어 여기도 인증키가 샌다.
            // 응답 경로와 같은 이유로 cause를 붙이지 않고, 원인은 클래스 이름만 남긴다.
            log.warn("[ECOS] 연결 실패 statCode={} reason={}", statCode, e.cause?.javaClass?.simpleName)
            throw EcosApiException("CONN", "ECOS 연결에 실패했습니다")
        } catch (e: EcosApiException) {
            throw e // 위 EMPTY. 우리가 만든 예외라 이미 깨끗하다.
        } catch (e: Throwable) {
            // 예외 종류를 열거하는 건 블랙리스트라 계속 샌다 — 예를 들어 DataBufferLimitException은
            // IllegalStateException을 상속하면서 WebClient 체인 안에서 터져 checkpoint 프레임을 달고 나온다.
            // 그래서 나머지를 전부 삼키고 갈아끼운다. 타임아웃도 여기로 모여 EcosApiException으로 통일된다.
            log.warn("[ECOS] 호출 실패 statCode={} reason={}", statCode, e.javaClass.simpleName)
            throw EcosApiException("IO", "ECOS 호출에 실패했습니다")
        }

        // 파서는 JSON 모양이 어긋난 경우만 EcosApiException으로 보고한다.
        // 본문이 아예 JSON이 아니면(HTML 오류 페이지 등) Jackson 예외가 그대로 새므로 여기서 감싼다.
        return try {
            parser.parse(body)
        } catch (e: JsonProcessingException) {
            // 본문 미리보기는 우리 로그에만 남기고 예외에는 싣지 않는다. 예외 메시지는 어드민 응답까지
            // 흘러가는데, 본문은 서버가 준 내용이라 우리 요청 URI가 되울려 올 수 있기 때문이다.
            // 마스킹은 설정된 키 문자열과 정확히 일치할 때만 듣는다(URL 인코딩·부분 반향은 못 잡는다) —
            // 그래서 마스킹에만 기대지 않고 예외에서 본문 자체를 뺀다.
            // cause도 붙이지 않는다: JsonParseException 메시지가 원본 본문을 [Source: (String)"..."]로 물고 있다.
            log.warn(
                "[ECOS] 응답이 JSON이 아닙니다 statCode={} reason={} preview={}",
                statCode, e.javaClass.simpleName, preview(body),
            )
            throw EcosApiException("MALFORMED", "응답 본문이 올바른 JSON이 아닙니다")
        }
    }
}
