package com.allfolio.fx.upbit

import com.allfolio.fx.DailyRate
import com.allfolio.fx.HistoricalRateSource
import com.allfolio.fx.SourceFetch
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Upbit 일봉 기반 과거 크립토 시세 소스.
 *
 * **페이지네이션이 이 클래스의 존재 이유다.** Upbit은 요청당 200건만 주는데
 * `count=201`도 `count=500`도 오류가 아니라 **조용히 200건만** 돌려준다(실측).
 * 페이지를 안 넘기면 오래된 구간이 소리 없이 비고, 그 날짜의 현금흐름은 계속
 * 현재가 폴백으로 떨어진다 — 오류도 로그도 없이.
 *
 * `to`는 **배타적**이다(실측): KST 08-03 자정을 넘기면 08-02까지 돌려준다.
 * 그래서 날짜 D를 포함하려면 D+1일의 KST 자정을 싣는다. 실제 전송은 UTC(Z) 표기다 —
 * 아래 [cursor] 참조.
 *
 * **레이트리밋**: `remaining-req: group=candles; min=600; sec=9` — 초당 10회 남짓이다.
 * 페이지를 연달아 던지므로 아주 긴 구간(수십 페이지)을 한 번에 요청하면 429에 걸릴 수 있다.
 * 실측: 26년 구간(약 48페이지)은 걸렸고, 5년(약 10페이지)은 왕복 지연만으로 자연히 분산돼
 * 걸리지 않는다. 스로틀을 넣지 않은 이유가 이것이다 — 현실적 백필 구간에서는 불필요하고,
 * 필요해지면 구간을 나눠 돌리면 된다(백필은 멱등하다).
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

        /** Upbit 일봉의 하루 경계는 KST 09:00이다. 커서는 KST 자정 기준으로 만든다. */
        private val KST = ZoneOffset.ofHours(9)
    }

    override fun supports(currency: String): Boolean = currency.trim().uppercase() in SUPPORTED

    /**
     * `to` 커서. 배타적이므로 포함하려는 마지막 날짜 + 1일을 넘긴다. KST 자정을 **UTC(Z) 표기로** 보낸다.
     *
     * `+09:00`을 그대로 실으면 Upbit이 400을 준다. WebClient는 쿼리 값의 `+`를 인코딩하지 않고
     * (RFC 3986상 쿼리에서 합법), Upbit은 그 `+`를 공백으로 읽어 날짜 파싱에 실패한다.
     * 실제 요청으로 확인했다 — `%2B09:00`은 200, 날 `+09:00`은 400.
     *
     * 스텁 서버 테스트로는 절대 못 잡는 종류다(스텁은 우리가 디코드하니까).
     * UTC로 보내면 `+`가 아예 없어 인코딩 문제가 구조적으로 사라진다.
     */
    private fun cursor(exclusiveUpper: LocalDate): String =
        OffsetDateTime.of(exclusiveUpper, LocalTime.MIDNIGHT, KST).toInstant().toString()

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
            // 그 이전 이력이 없다 = 정상 종료. 다만 구간을 다 못 채웠으면 finish가 알린다.
            if (page.rates.isEmpty()) return finish(collected, skipped, market, from, to)

            collected += page.rates.filter { it.baseDate in from..to }

            val oldest = page.rates.minOf { it.baseDate }
            if (oldest <= from) return finish(collected, skipped, market, from, to)

            // 커서가 반드시 과거로 가야 한다. 안 가면 같은 페이지를 영원히 받는다.
            if (oldest >= exclusiveUpper) {
                throw UpbitCandleException(
                    "Upbit 일봉 페이지가 진행하지 못했습니다 (market=$market to=$exclusiveUpper oldest=$oldest)"
                )
            }
            exclusiveUpper = oldest
        }

        // 여기 닿으면 구조적으로 이상하다 — 100페이지면 약 54년이라 정상 요청으로는 못 온다.
        // 위 no-progress 가드와 같은 계열의 사건이므로 같은 방식으로 실패시킨다.
        // WARN만 남기고 부분 결과를 돌려주면 "덜 채웠다"가 성공과 구분되지 않는다.
        throw UpbitCandleException(
            "Upbit 일봉 페이지가 최대치($MAX_PAGES)를 넘었습니다 (market=$market $from~$to)"
        )
    }

    /**
     * 수집을 마치며 **요청 구간을 다 못 채웠으면 알린다.**
     *
     * 상장 이전 구간을 요청하면 이력이 없는 게 정상이라 예외로 만들지 않는다.
     * 하지만 조용히 돌려주면 호출자는 부분 결과를 완전한 성공과 구분할 수 없다 —
     * `FxRateBackfillService`의 0건 검사는 통과하고 행은 저장되며 아무도 모른다.
     * 그리고 못 채운 날짜의 현금흐름은 현재가 폴백으로 떨어진다.
     * **이 기능이 없애려던 바로 그 조용한 구멍이다.**
     */
    private fun finish(
        rates: List<DailyRate>,
        skipped: Int,
        market: String,
        from: LocalDate,
        to: LocalDate,
    ): SourceFetch {
        val covered = rates.minOfOrNull { it.baseDate }
        if (covered == null || covered > from) {
            log.warn(
                "[UpbitCandle] 요청 구간을 다 채우지 못했다 market={} 요청={}~{} 채운시작일={}",
                market, from, to, covered,
            )
        }
        return SourceFetch(rates, skipped)
    }
}
