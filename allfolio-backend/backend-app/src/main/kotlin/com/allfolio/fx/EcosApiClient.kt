package com.allfolio.fx

import com.fasterxml.jackson.core.JsonProcessingException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
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

        /** ECOS 1회 요청 상한. 일별 10년치가 약 2,600행이라 한 번에 받는다. */
        private const val MAX_ROWS = 100_000

        /** 파싱 실패 시 로그·예외에 남길 응답 앞부분 길이. 본문 전체는 남기지 않는다. */
        private const val BODY_PREVIEW_LENGTH = 200
    }

    override fun fetchDailyRates(
        statCode: String,
        itemCode: String,
        from: LocalDate,
        to: LocalDate,
    ): EcosParseResult {
        require(properties.apiKey.isNotBlank()) { "ECOS 인증키가 설정되지 않았습니다 (ECOS_API_KEY)" }
        require(statCode.isNotBlank() && itemCode.isNotBlank()) {
            "ECOS 통계표·항목 코드가 설정되지 않았습니다"
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
            log.warn("[ECOS] HTTP {} statCode={} preview={}", status, statCode, e.responseBodyAsString.take(BODY_PREVIEW_LENGTH))
            throw EcosApiException("HTTP-$status", "ECOS가 HTTP $status 를 반환했습니다")
        }

        // 파서는 JSON 모양이 어긋난 경우만 EcosApiException으로 보고한다.
        // 본문이 아예 JSON이 아니면(HTML 오류 페이지 등) Jackson 예외가 그대로 새므로 여기서 감싼다.
        return try {
            parser.parse(body)
        } catch (e: JsonProcessingException) {
            val preview = body.take(BODY_PREVIEW_LENGTH)
            log.warn("[ECOS] 응답이 JSON이 아닙니다 statCode={} preview={}", statCode, preview)
            throw EcosApiException("MALFORMED", "응답 본문이 올바른 JSON이 아닙니다: $preview")
                .apply { initCause(e) }
        }
    }
}
