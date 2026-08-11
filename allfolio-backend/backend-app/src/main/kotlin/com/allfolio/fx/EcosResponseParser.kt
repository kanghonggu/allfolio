package com.allfolio.fx

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** ECOS 통계 한 행 — 기준일과 통화 1단위당 원화 값 */
data class EcosRate(val baseDate: LocalDate, val rateKrw: BigDecimal)

/** @param skipped 값·날짜가 이상해 버린 행 수. 조용히 삼키지 않고 호출자에게 보고한다. */
data class EcosParseResult(val rates: List<EcosRate>, val skipped: Int)

class EcosApiException(code: String, message: String) :
    RuntimeException("ECOS 오류 [$code] $message")

/**
 * ECOS StatisticSearch 응답 파서.
 *
 * 정상: {"StatisticSearch":{"row":[{"TIME":"20250811","DATA_VALUE":"1390.2"}, ...]}}
 * 오류: {"RESULT":{"CODE":"INFO-200","MESSAGE":"..."}}
 *
 * 두 형태가 최상위에서 갈리므로 트리로 읽고 분기한다.
 */
@Component
class EcosResponseParser(
    private val mapper: ObjectMapper = ObjectMapper(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")
    }

    fun parse(json: String): EcosParseResult {
        val root = mapper.readTree(json)

        val result = root.path("RESULT")
        if (!result.isMissingNode) {
            throw EcosApiException(result.path("CODE").asText(""), result.path("MESSAGE").asText(""))
        }

        val rows = root.path("StatisticSearch").path("row")
        if (!rows.isArray) {
            throw EcosApiException("PARSE", "예상치 못한 응답 형식입니다")
        }

        var skipped = 0
        val rates = rows.mapNotNull { row ->
            val time = row.path("TIME").asText("")
            val value = row.path("DATA_VALUE").asText("")

            val date = runCatching { LocalDate.parse(time, TIME_FORMAT) }.getOrNull()
            val rate = runCatching { BigDecimal(value) }.getOrNull()

            if (date == null || rate == null || rate <= BigDecimal.ZERO) {
                skipped++
                log.debug("[ECOS] 행 건너뜀 TIME={} DATA_VALUE={}", time, value)
                null
            } else {
                EcosRate(date, rate)
            }
        }

        return EcosParseResult(rates, skipped)
    }
}
