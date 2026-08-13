package com.allfolio.fx

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** ECOS 통계 한 행 — 기준일과 그 날의 값. 단위는 계열마다 다르다(환율은 원, 금리는 연 %) */
data class EcosObservation(val baseDate: LocalDate, val value: BigDecimal)

/** @param skipped 값·날짜가 이상해 버린 행 수. 조용히 삼키지 않고 호출자에게 보고한다. */
data class EcosParseResult(val rates: List<EcosObservation>, val skipped: Int)

class EcosApiException(val code: String, val detail: String) :
    RuntimeException("ECOS 오류 [$code] $detail")

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
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private val TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")
    }

    fun parse(json: String, valuePolicy: EcosValuePolicy): EcosParseResult {
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

            // 원인을 셋으로 가른다 — 세 경우 다 이 log.warn 한 줄로 모이는데, policy를 무조건 찍으면
            // 날짜가 깨진 행에도 "policy=POSITIVE"가 붙어 마치 정책이 그 행을 걸러낸 것처럼 읽힌다.
            // 정책은 실제로 정책이 원인일 때만 찍는다.
            when {
                date == null -> {
                    skipped++
                    log.warn("[ECOS] 행 건너뜀 TIME={} DATA_VALUE={} reason=BAD_DATE", time, value)
                    null
                }
                rate == null -> {
                    skipped++
                    log.warn("[ECOS] 행 건너뜀 TIME={} DATA_VALUE={} reason=BAD_NUMBER", time, value)
                    null
                }
                !valuePolicy.accepts(rate) -> {
                    skipped++
                    log.warn("[ECOS] 행 건너뜀 TIME={} DATA_VALUE={} reason=POLICY policy={}", time, value, valuePolicy)
                    null
                }
                else -> EcosObservation(date, rate)
            }
        }

        return EcosParseResult(rates, skipped)
    }
}
