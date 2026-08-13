package com.allfolio.fx.upbit

import com.allfolio.fx.DailyRate
import com.allfolio.fx.HistoricalRateSource
import com.allfolio.fx.SourceFetch
import org.slf4j.LoggerFactory
import java.time.LocalDate

/**
 * Upbit 일봉 기반 과거 크립토 시세 소스.
 *
 * **페이지네이션이 이 클래스의 존재 이유다.** Upbit은 요청당 200건만 주는데
 * `count=201`도 `count=500`도 오류가 아니라 **조용히 200건만** 돌려준다(실측).
 * 페이지를 안 넘기면 오래된 구간이 소리 없이 비고, 그 날짜의 현금흐름은 계속
 * 현재가 폴백으로 떨어진다 — 오류도 로그도 없이.
 *
 * `to`는 **배타적**이다: `to=2026-08-03T00:00:00+09:00`이 08-02까지 돌려준다(실측).
 * 그래서 날짜 D를 포함하려면 `(D+1)T00:00:00+09:00`을 싣는다.
 */
class UpbitCandleRateSource(
    private val client: UpbitCandleClient,
    private val parser: UpbitCandleParser,
) : HistoricalRateSource {

    private val log = LoggerFactory.getLogger(javaClass)

    override val sourceName = "UPBIT"

    companion object {
        private val SUPPORTED = setOf("BTC", "ETH")
        private const val PAGE = 200
        /** 안전장치. 200건 × 100페이지 = 약 54년이라 어떤 현실적 구간도 덮는다. */
        private const val MAX_PAGES = 100
    }

    override fun supports(currency: String): Boolean = currency.trim().uppercase() in SUPPORTED

    /** `to`는 배타적이므로 포함하려는 마지막 날짜 + 1일을 넘긴다. */
    private fun cursor(exclusiveUpper: LocalDate) = "${exclusiveUpper}T00:00:00+09:00"

    override fun fetch(currency: String, from: LocalDate, to: LocalDate): SourceFetch {
        val code = currency.trim().uppercase()
        require(code in SUPPORTED) { "Upbit 일봉을 지원하지 않는 통화입니다: $currency" }

        val market = "KRW-$code"
        val collected = mutableListOf<DailyRate>()
        // 파서가 버린 행을 페이지마다 더한다. 0으로 박아 두면 BackfillSummary.skipped가
        // 늘 0이 되어 "조용히 삼키지 않는다"는 이 서브시스템의 규약이 무너진다.
        var skipped = 0
        var exclusiveUpper = to.plusDays(1)

        repeat(MAX_PAGES) {
            val page = parser.parse(client.fetchDays(market, cursor(exclusiveUpper), PAGE))
            skipped += page.skipped
            if (page.rates.isEmpty()) return SourceFetch(collected, skipped)

            collected += page.rates.filter { it.baseDate in from..to }

            val oldest = page.rates.minOf { it.baseDate }
            if (oldest <= from) return SourceFetch(collected, skipped)

            // 커서가 반드시 과거로 가야 한다. 안 가면 같은 페이지를 영원히 받는다.
            val next = oldest
            if (next >= exclusiveUpper) {
                throw UpbitCandleException(
                    "Upbit 일봉 페이지가 진행하지 못했습니다 (market=$market to=$exclusiveUpper oldest=$oldest)"
                )
            }
            exclusiveUpper = next
        }

        log.warn("[UpbitCandle] 최대 페이지({})에 도달해 중단 market={} {}~{}", MAX_PAGES, market, from, to)
        return SourceFetch(collected, skipped)
    }
}
