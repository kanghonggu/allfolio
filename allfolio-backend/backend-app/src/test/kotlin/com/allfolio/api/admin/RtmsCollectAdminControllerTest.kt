package com.allfolio.api.admin

import com.allfolio.market.realestate.RtmsClient
import com.allfolio.market.realestate.RtmsCollectService
import com.allfolio.market.realestate.RtmsCollectSummary
import com.allfolio.market.realestate.RtmsDealStore
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.time.YearMonth

/**
 * 실거래가 수집 트리거.
 *
 * **응답 코드가 이 컨트롤러의 계약이다** — 전멸 502 · 파라미터 오류 400 · 부분 실패 200.
 * 조용한 수집 중단은 반드시 보여야 하고, 매일 빨간 잡은 아무도 안 본다.
 */
class RtmsCollectAdminControllerTest {

    private var lastTargets: List<Pair<String, YearMonth>> = emptyList()
    private var lastBudget: Int = -1
    private var summary = summary()

    private fun summary(
        requested: Int = 1, fetched: Int = 1, skipped: Int = 0,
        failures: List<String> = emptyList(),
    ) = RtmsCollectSummary(
        requested = requested, fetched = fetched, skipped = skipped,
        dealsUpserted = 10, rowsDropped = 0, apiCalls = fetched,
        budgetExhausted = 0, failures = failures,
    )

    private val service = object : RtmsCollectService(
        RtmsClient("K", "http://unused", ObjectMapper()),
        object : RtmsDealStore {
            override fun upsertAll(deals: List<com.allfolio.market.realestate.RtmsDeal>, collectedAt: LocalDateTime) = 0
            override fun findFetch(sggCode: String, month: YearMonth) = null
            override fun recordFetch(record: com.allfolio.market.realestate.RtmsFetchRecord) {}
        },
    ) {
        override fun collect(
            targets: List<Pair<String, YearMonth>>,
            today: YearMonth,
            now: LocalDateTime,
            budget: Int,
        ): RtmsCollectSummary {
            lastTargets = targets
            lastBudget = budget
            return summary
        }
    }

    private val controller = RtmsCollectAdminController(service)

    private fun badRequest(block: () -> Unit) =
        catchThrowableOfType({ block() }, ResponseStatusException::class.java)

    // ── 대상 생성 ──────────────────────────────────────────────────────────

    @Test
    fun `시군구와 개월수를 곱해 조합을 만든다`() {
        controller.collect(sgg = "11110,11680", months = 3, budget = null)

        assertThat(lastTargets).hasSize(6)
        assertThat(lastTargets.map { it.first }.distinct()).containsExactly("11110", "11680")
        // 이번 달부터 거슬러 3개월
        assertThat(lastTargets.map { it.second }.distinct()).hasSize(3)
    }

    @Test
    fun `중복 시군구는 한 번만 넣는다`() {
        controller.collect(sgg = "11110, 11110 ,11110", months = 1, budget = null)

        assertThat(lastTargets).hasSize(1)
    }

    @Test
    fun `예산을 넘기면 서비스 기본값을 쓴다`() {
        controller.collect(sgg = "11110", months = 1, budget = null)

        assertThat(lastBudget).isEqualTo(RtmsCollectService.DEFAULT_BUDGET)
    }

    // ── 파라미터 검증 ──────────────────────────────────────────────────────

    /**
     * **5자리가 아니면 포털이 조용히 0건을 준다** — 오류가 아니라 빈 결과라 안 보인다.
     * 그래서 우리가 먼저 막는다.
     */
    @Test
    fun `법정동 코드가 다섯 자리가 아니면 거절한다`() {
        val t = badRequest { controller.collect(sgg = "1111", months = 1, budget = null) }

        assertThat(t.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(t.reason).contains("5자리")
    }

    @Test
    fun `법정동 코드에 숫자가 아닌 값이 오면 거절한다`() {
        val t = badRequest { controller.collect(sgg = "1111A", months = 1, budget = null) }

        assertThat(t.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `시군구가 비면 거절한다`() {
        val t = badRequest { controller.collect(sgg = " , ", months = 1, budget = null) }

        assertThat(t.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(t.reason).contains("비어")
    }

    @Test
    fun `개월수가 범위를 벗어나면 거절한다`() {
        assertThat(badRequest { controller.collect("11110", 0, null) }.statusCode)
            .isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(badRequest { controller.collect("11110", 37, null) }.statusCode)
            .isEqualTo(HttpStatus.BAD_REQUEST)
    }

    /** 실수 한 번에 하루치 예산이 날아가지 않게 한다 */
    @Test
    fun `조합이 너무 많으면 거절한다`() {
        val many = (1..30).joinToString(",") { "1%04d".format(it) }

        val t = badRequest { controller.collect(sgg = many, months = 36, budget = null) }

        assertThat(t.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(t.reason).contains("조합이 너무 많습니다")
    }

    // ── 응답 코드 ──────────────────────────────────────────────────────────

    /** 부르려 했는데 하나도 성공 못 하면 상류 장애다 */
    @Test
    fun `전멸이면 502다`() {
        summary = summary(fetched = 0, failures = listOf("11110 202608: 조회 실패"))

        val t = badRequest { controller.collect("11110", 1, null) }

        assertThat(t.statusCode).isEqualTo(HttpStatus.BAD_GATEWAY)
    }

    /**
     * **`fetched == 0`만으로 502를 내면 안 된다.** 전부 이미 받아 둔 조합이면 정상이다 —
     * 매일 도는 잡이 그때마다 빨개지면 아무도 안 본다.
     */
    @Test
    fun `전부 건너뛴 경우는 정상이다`() {
        summary = summary(requested = 3, fetched = 0, skipped = 3)

        val res = controller.collect("11110", 3, null)

        assertThat(res.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(res.body!!.skipped).isEqualTo(3)
    }

    /** 일부 실패는 200이다 — 나머지는 들어왔다 */
    @Test
    fun `부분 실패는 200이다`() {
        summary = summary(requested = 2, fetched = 1, failures = listOf("11680 202608: 실패"))

        val res = controller.collect("11110,11680", 1, null)

        assertThat(res.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(res.body!!.failures).hasSize(1)
    }
}
