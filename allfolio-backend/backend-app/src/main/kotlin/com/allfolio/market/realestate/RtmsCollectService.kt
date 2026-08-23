package com.allfolio.market.realestate

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.time.YearMonth

/** 한 번의 수집 결과. 어드민 응답과 Actions 주석으로 그대로 나간다 */
data class RtmsCollectSummary(
    val requested: Int,
    /** 실제로 API를 부른 조합 수 */
    val fetched: Int,
    /** 이미 받아 둬서 건너뛴 조합 수 */
    val skipped: Int,
    val dealsUpserted: Int,
    /** 파싱 단계에서 버린 행 수 */
    val rowsDropped: Int,
    /** 페이징 포함 실제 호출 수. **일 1,000콜 예산의 근거** */
    val apiCalls: Int,
    /** 예산이 남지 않아 못 부른 조합 수 */
    val budgetExhausted: Int,
    val failures: List<String>,
)

/**
 * 국토부 실거래가 수집.
 *
 * ## 재수집 정책이 이 서비스의 핵심이다
 *
 * 두 가지가 뒤늦게 온다:
 *
 *  1. **신고 지연** — 계약 후 30일 내 신고라, 이번 달 데이터는 다음 달에도 늘어난다
 *  2. **해제(취소)** — 이미 받은 거래가 나중에 해제로 바뀐다(실측 2.6%)
 *
 * 그래서 **최근 [FRESH_MONTHS]개월은 다시 받고, 그보다 오래된 달은 한 번만 받는다.**
 * 오래된 달을 계속 다시 받으면 일 1,000콜 예산이 금방 사라지는데 값은 거의 안 바뀐다.
 *
 * ## 예산을 넘기지 않는다
 *
 * 질의 단위가 `(시군구, 년월)`이고 한 조합이 200건을 넘으면 페이징으로 호출이 더 든다
 * (실측: 분당 2026-07이 450건 → 3콜). **남은 예산을 넘길 것 같으면 그 조합을 아예
 * 시작하지 않는다** — 절반만 받고 기록하면 나머지가 영원히 안 들어온다.
 *
 * ## 조합 하나가 실패해도 나머지를 돌린다
 *
 * 한 시군구의 한 달이 상류 오류로 실패했다고 다른 지역까지 멈추면 안 된다.
 * 사유는 [RtmsCollectSummary.failures]로 모아 올린다 — `CommodityCollectService`와 같은 판단이다.
 */
@Service
class RtmsCollectService(
    private val client: RtmsClient,
    private val store: RtmsDealStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @param targets 받을 `(시군구, 년월)` 조합. **사용자가 실제로 보유한 시군구로 한정한다** —
     *        전국을 긁는 설계가 아니다
     * @param today 재수집 판단 기준일. 호출자가 주입한다 — 컨테이너가 UTC라 여기서
     *        `now()`를 부르면 KST 기준 '이번 달'이 어긋난다
     * @param budget 이번 실행에서 쓸 수 있는 최대 호출 수
     */
    fun collect(
        targets: List<Pair<String, YearMonth>>,
        today: YearMonth,
        now: LocalDateTime,
        budget: Int = DEFAULT_BUDGET,
    ): RtmsCollectSummary {
        var fetched = 0
        var skipped = 0
        var upserted = 0
        var dropped = 0
        var calls = 0
        var exhausted = 0
        val failures = mutableListOf<String>()

        for ((sgg, month) in targets) {
            if (!needsFetch(sgg, month, today)) {
                skipped++
                continue
            }
            // 한 조합이 최대 몇 콜인지 모르므로 최소 한 콜은 남아 있어야 시작한다.
            // 시작한 조합은 페이징을 끝까지 돈다 — 절반만 받고 기록하면 나머지가 영영 안 온다.
            if (calls >= budget) {
                exhausted++
                continue
            }

            try {
                val (deals, drop, used) = fetchAllPages(sgg, month)
                calls += used
                dropped += drop
                upserted += store.upsertAll(deals, now)
                store.recordFetch(RtmsFetchRecord(sgg, month, deals.size, used, now))
                fetched++
            } catch (e: Exception) {
                // 키가 메시지에 없다는 것은 RtmsClient가 보장한다 — 이 값은 어드민 응답까지 나간다
                failures += "$sgg ${ym(month)}: ${e.message}"
                log.warn("[실거래가] {} {} 수집 실패: {}", sgg, month, e.message)
            }
        }

        return RtmsCollectSummary(
            requested = targets.size, fetched = fetched, skipped = skipped,
            dealsUpserted = upserted, rowsDropped = dropped, apiCalls = calls,
            budgetExhausted = exhausted, failures = failures,
        )
    }

    /** @return (거래, 버린 행, 쓴 호출 수) */
    private fun fetchAllPages(sgg: String, month: YearMonth): Triple<List<RtmsDeal>, Int, Int> {
        val all = mutableListOf<RtmsDeal>()
        var dropped = 0
        var page = 1
        var calls = 0
        while (true) {
            val fetch = client.fetchDeals(sgg, month, page)
            calls++
            all += fetch.deals
            dropped += fetch.skipped
            if (!client.hasMore(fetch, page) || fetch.deals.isEmpty()) break
            page++
            // 상류가 totalCount를 이상하게 주는 날 무한 루프에 빠지지 않게 한다.
            // 한 시군구의 한 달이 이보다 많을 일은 없다(실측 최다 450건).
            if (page > MAX_PAGES) {
                log.warn("[실거래가] {} {} 페이지 상한 도달 — totalCount를 확인할 것", sgg, month)
                break
            }
        }
        return Triple(all, dropped, calls)
    }

    /**
     * 다시 받아야 하는가.
     *
     * 안 받아 본 조합이면 당연히 받고, **최근 [FRESH_MONTHS]개월이면 이미 받았어도 다시 받는다**
     * (신고 지연·해제 반영). 그보다 오래된 달은 한 번 받으면 끝이다.
     */
    private fun needsFetch(sgg: String, month: YearMonth, today: YearMonth): Boolean {
        store.findFetch(sgg, month) ?: return true
        val monthsAgo = (today.year - month.year) * 12 + (today.monthValue - month.monthValue)
        return monthsAgo < FRESH_MONTHS
    }

    private fun ym(m: YearMonth) = "%04d%02d".format(m.year, m.monthValue)

    companion object {
        /**
         * 이 개월 수 안쪽은 다시 받는다.
         *
         * **3인 이유**: 부동산 거래 신고 기한이 계약 후 30일이라 이번 달 데이터가 다음 달에도
         * 늘어난다. 거기에 해제가 더 늦게 붙는다(실측 해제일이 계약일보다 한 달 뒤인 건이 있었다 —
         * `26.07` 계약에 `26.08.15` 해제). 두 달로 잡으면 그 해제를 놓친다.
         */
        const val FRESH_MONTHS = 3

        /**
         * 한 실행의 호출 상한. 포털 일 한도가 1,000이고 다른 수집기는 이 오퍼레이션을 쓰지 않는다.
         * 여유를 두는 것은 **수동 재수집이나 백필이 같은 날 겹칠 수 있기 때문**이다.
         */
        const val DEFAULT_BUDGET = 800

        /** 한 조합의 페이지 상한. 실측 최다가 450건(3페이지)이라 넉넉하다 */
        const val MAX_PAGES = 20
    }
}
