package com.allfolio.market.rate.fred

import com.allfolio.fx.RateValuePolicy
import com.allfolio.market.rate.RateFetch
import com.allfolio.market.rate.RateObservation
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate

class FredApiException(val code: String, val detail: String) :
    RuntimeException("FRED 오류 [$code] $detail")

/**
 * FRED `series/observations` 응답 파서.
 *
 * 정상: `{"observations":[{"date":"2026-08-13","value":"4.25"}, ...]}`
 *
 * **`value`가 마침표(`"."`)면 관측이 없는 날이다.** 휴일·미공표일에 그렇게 온다.
 * 이걸 숫자로 읽으려 하면 실패하고, 0으로 해석하면 더 나쁘다 —
 * [RateValuePolicy.PERCENT]는 0을 **일부러** 통과시키므로(0.00% 공표일이 실재한다)
 * 값 검증으로는 절대 못 잡는다. 그래서 여기서 걸러 [RateFetch.skipped]로 센다.
 */
@Component
class FredObservationParser(
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun parse(json: String, valuePolicy: RateValuePolicy): RateFetch {
        val root = mapper.readTree(json)

        val observations = root.path("observations")
        if (!observations.isArray) {
            // 오류 본문에는 error_message가 실린다. 그 문자열은 우리가 만든 게 아니라
            // 서버가 준 것이라 요청 URL이 되울려 올 수 있다 — 그래서 싣지 않는다
            throw FredApiException("PARSE", "응답에 observations가 없습니다")
        }

        var skipped = 0
        val rows = observations.mapNotNull { node ->
            val date = node.path("date").asText("")
            val raw = node.path("value").asText("")

            val quoteDate = runCatching { LocalDate.parse(date) }.getOrNull()
            // "."은 결측이다. BigDecimal(".")은 예외를 던지므로 runCatching이 잡지만,
            // 의도를 코드로 남긴다 — 다음 사람이 "왜 굳이"라고 지우지 않도록
            val value = if (raw == MISSING) null else runCatching { BigDecimal(raw) }.getOrNull()

            if (quoteDate == null || value == null || !valuePolicy.accepts(value)) {
                skipped++
                log.warn("[FRED] 행 건너뜀 date={} value={}", date, raw)
                null
            } else {
                RateObservation(quoteDate, value)
            }
        }
        return RateFetch(rows, skipped)
    }

    companion object {
        /** FRED가 관측 없음을 나타내는 값 */
        private const val MISSING = "."
    }
}
