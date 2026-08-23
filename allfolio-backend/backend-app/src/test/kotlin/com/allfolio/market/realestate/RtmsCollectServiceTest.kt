package com.allfolio.market.realestate

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * 실거래가 수집.
 *
 * 이 서비스가 지키는 것은 셋이다 — **예산을 넘기지 않는다 · 오래된 달을 다시 받지 않는다 ·
 * 조합 하나가 실패해도 나머지를 돌린다.** 셋 다 조용히 어긋나는 종류라 여기서 못을 박는다.
 */
class RtmsCollectServiceTest {

    private val now = LocalDateTime.of(2026, 8, 21, 19, 30)
    private val today = YearMonth.of(2026, 8)

    // ── 예산 ──────────────────────────────────────────────────────────────

    /**
     * **시작한 조합은 페이징을 끝까지 돈다.** 절반만 받고 기록하면 그 조합은 "받았다"로
     * 남아 나머지가 영영 안 들어온다.
     */
    @Test
    fun `페이징을 끝까지 돌고 호출 수를 센다`() {
        // 450건 = 200 + 200 + 50 → 3콜 (실측 분당 2026-07)
        val client = FakeClient(pages = mapOf("11680" to 450))
        val store = FakeStore()

        val s = service(client, store).collect(listOf("11680" to today), today, now)

        assertThat(client.callCount).isEqualTo(3)
        assertThat(s.apiCalls).isEqualTo(3)
        assertThat(s.dealsUpserted).isEqualTo(450)
    }

    /** 예산이 남지 않으면 **시작조차 하지 않는다** */
    @Test
    fun `예산을 넘기면 남은 조합을 건너뛴다`() {
        val client = FakeClient(pages = mapOf("A" to 450, "B" to 10))
        val store = FakeStore()

        val s = service(client, store).collect(
            listOf("A" to today, "B" to today), today, now, budget = 2,
        )

        // A가 3콜을 써 예산(2)을 넘겼고, B는 시작조차 안 한다
        assertThat(s.fetched).isEqualTo(1)
        assertThat(s.budgetExhausted).isEqualTo(1)
        assertThat(client.seen).containsExactly("A")
    }

    /** 예산을 다 써도 **이미 받은 것은 기록된다** — 그래야 다음 실행이 이어서 간다 */
    @Test
    fun `예산 소진 전에 받은 조합은 기록된다`() {
        val client = FakeClient(pages = mapOf("A" to 10, "B" to 10))
        val store = FakeStore()

        service(client, store).collect(listOf("A" to today, "B" to today), today, now, budget = 1)

        assertThat(store.records).hasSize(1)
        assertThat(store.records.single().sggCode).isEqualTo("A")
    }

    // ── 재수집 정책 ────────────────────────────────────────────────────────

    /**
     * **최근 3개월은 다시 받는다.** 신고 기한이 계약 후 30일이라 이번 달 데이터가 다음 달에도
     * 늘고, 해제는 그보다 더 늦게 붙는다(실측: `26.07` 계약에 `26.08.15` 해제).
     */
    @Test
    fun `최근 세 달은 이미 받았어도 다시 받는다`() {
        val client = FakeClient(pages = mapOf("11680" to 10))
        val store = FakeStore()
        for (m in listOf(YearMonth.of(2026, 8), YearMonth.of(2026, 7), YearMonth.of(2026, 6))) {
            store.records += RtmsFetchRecord("11680", m, 10, 1, now.minusDays(1))
        }

        val s = service(client, store).collect(
            listOf(
                "11680" to YearMonth.of(2026, 8),
                "11680" to YearMonth.of(2026, 7),
                "11680" to YearMonth.of(2026, 6),
            ),
            today, now,
        )

        assertThat(s.fetched).isEqualTo(3)
        assertThat(s.skipped).isZero()
    }

    /** 세 달을 넘긴 달은 한 번 받으면 끝이다 — 값이 거의 안 바뀌는데 예산만 쓴다 */
    @Test
    fun `오래된 달은 다시 받지 않는다`() {
        val client = FakeClient(pages = mapOf("11680" to 10))
        val store = FakeStore()
        val old = YearMonth.of(2026, 5)   // 3개월 전
        store.records += RtmsFetchRecord("11680", old, 10, 1, now.minusMonths(3))

        val s = service(client, store).collect(listOf("11680" to old), today, now)

        assertThat(s.skipped).isEqualTo(1)
        assertThat(s.fetched).isZero()
        assertThat(client.callCount).isZero()
    }

    /**
     * **거래 0건이었던 달과 안 받아 본 달은 다르다.** 0건도 기록이 남으므로 다시 안 받는다 —
     * 기록이 없을 때만 받는다.
     */
    @Test
    fun `거래 0건이었던 오래된 달도 다시 받지 않는다`() {
        val client = FakeClient(pages = mapOf("11680" to 0))
        val store = FakeStore()
        val old = YearMonth.of(2026, 1)
        store.records += RtmsFetchRecord("11680", old, dealCount = 0, apiCalls = 1, fetchedAt = now)

        val s = service(client, store).collect(listOf("11680" to old), today, now)

        assertThat(s.skipped).isEqualTo(1)
        assertThat(client.callCount).isZero()
    }

    @Test
    fun `안 받아 본 달은 오래됐어도 받는다`() {
        val client = FakeClient(pages = mapOf("11680" to 5))
        val store = FakeStore()

        val s = service(client, store).collect(listOf("11680" to YearMonth.of(2020, 3)), today, now)

        assertThat(s.fetched).isEqualTo(1)
    }

    // ── 실패 격리 ──────────────────────────────────────────────────────────

    /** 한 지역이 실패해도 다른 지역은 돌아야 한다 */
    @Test
    fun `조합 하나가 실패해도 나머지를 돌린다`() {
        val client = FakeClient(pages = mapOf("GOOD" to 10), failing = setOf("BAD"))
        val store = FakeStore()

        val s = service(client, store).collect(
            listOf("BAD" to today, "GOOD" to today), today, now,
        )

        assertThat(s.fetched).isEqualTo(1)
        assertThat(s.failures).hasSize(1)
        assertThat(s.failures.single()).contains("BAD")
        assertThat(s.dealsUpserted).isEqualTo(10)
    }

    /** 실패한 조합은 **기록하지 않는다** — 기록하면 다음에 다시 안 받는다 */
    @Test
    fun `실패한 조합은 받았다고 기록하지 않는다`() {
        val client = FakeClient(pages = emptyMap(), failing = setOf("BAD"))
        val store = FakeStore()

        service(client, store).collect(listOf("BAD" to today), today, now)

        assertThat(store.records).isEmpty()
    }

    /** 사유에 인증키가 없어야 한다 — 이 값은 어드민 응답까지 나간다 */
    @Test
    fun `실패 사유에 인증키를 싣지 않는다`() {
        val client = FakeClient(pages = emptyMap(), failing = setOf("BAD"))
        val store = FakeStore()

        val s = service(client, store).collect(listOf("BAD" to today), today, now)

        assertThat(s.failures.single()).doesNotContain("serviceKey")
    }

    // ── 집계 ──────────────────────────────────────────────────────────────

    /** 버린 행 수가 요약에 실려야 한다 — 조용히 사라지면 형식 변화를 못 잡는다 */
    @Test
    fun `버린 행 수를 요약에 싣는다`() {
        val client = FakeClient(pages = mapOf("11680" to 10), droppedPerPage = 2)
        val store = FakeStore()

        val s = service(client, store).collect(listOf("11680" to today), today, now)

        assertThat(s.rowsDropped).isEqualTo(2)
    }

    private fun service(c: RtmsClient, s: RtmsDealStore) = RtmsCollectService(c, s)

    // ── 테스트 대역 ────────────────────────────────────────────────────────

    private inner class FakeClient(
        private val pages: Map<String, Int>,
        private val failing: Set<String> = emptySet(),
        private val droppedPerPage: Int = 0,
    ) : RtmsClient("KEY", "http://unused", ObjectMapper()) {
        var callCount = 0
        val seen = mutableListOf<String>()

        override fun fetchDeals(sggCode: String, month: YearMonth, page: Int): RtmsFetch {
            if (page == 1) seen += sggCode
            if (sggCode in failing) throw RtmsApiException("실거래가 조회 실패 $sggCode")
            callCount++
            val total = pages[sggCode] ?: 0
            val from = (page - 1) * RtmsClient.PAGE_SIZE
            val count = (total - from).coerceIn(0, RtmsClient.PAGE_SIZE)
            return RtmsFetch(
                List(count) { deal(sggCode, it + from) },
                skipped = if (page == 1) droppedPerPage else 0,
                totalCount = total,
            )
        }

        private fun deal(sgg: String, i: Int) = RtmsDeal(
            aptSeq = "$sgg-$i", aptName = "단지$i",
            exclusiveAreaM2 = BigDecimal("84.93"),
            dealDate = LocalDate.of(2026, 7, 1),
            dealAmountKrw = 900_000_000L, floor = 5, buildYear = 2002,
            sggCode = sgg, umdName = "동", cancelled = false, cancelledOn = null,
        )
    }

    private class FakeStore : RtmsDealStore {
        val records = mutableListOf<RtmsFetchRecord>()
        var upserted = 0

        override fun upsertAll(deals: List<RtmsDeal>, collectedAt: LocalDateTime): Int {
            upserted += deals.size
            return deals.size
        }

        override fun findFetch(sggCode: String, month: YearMonth) =
            records.firstOrNull { it.sggCode == sggCode && it.month == month }

        override fun recordFetch(record: RtmsFetchRecord) {
            records += record
        }
    }
}
