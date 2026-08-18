package com.allfolio.dart

import com.allfolio.dart.insider.InsiderCollectSummary
import com.allfolio.dart.DartApiException
import com.allfolio.dart.list.DartCollectSummary
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 공시 수집이 커밋된 뒤 elestock이 실패해도 공시는 남아야 한다.
 */
class DartCollectOrchestrationTest {

    private val endDe = LocalDate.of(2026, 8, 18)
    private val now = LocalDateTime.of(2026, 8, 18, 19, 0)

    @Test
    fun `기본 범위는 D-1부터 D까지다`() {
        var captured: Pair<LocalDate, LocalDate>? = null

        DartRunPlan.run(
            endDe, now,
            collectDisclosures = { b, e, _ ->
                captured = b to e
                DartCollectSummary(b, e, 1, 1, 0, false, emptyList())
            },
            collectInsiders = { _, _ -> InsiderCollectSummary(0, 0, emptyList()) },
        )

        assertThat(captured).isEqualTo(endDe.minusDays(1) to endDe)
    }

    @Test
    fun `elestock이 실패해도 공시 수집 결과를 돌려준다`() {
        val result = DartRunPlan.run(
            endDe, now,
            collectDisclosures = { b, e, _ -> DartCollectSummary(b, e, 2, 2, 3, false, listOf("R1")) },
            collectInsiders = { _, _ -> throw DartApiException("elestock status=020") },
        )

        assertThat(result.disclosure.newCount).isEqualTo(3)
        assertThat(result.insider.failures).hasSize(1)
    }

    @Test
    fun `델타가 비면 elestock을 부르지 않는다`() {
        var called = false

        DartRunPlan.run(
            endDe, now,
            collectDisclosures = { b, e, _ -> DartCollectSummary(b, e, 0, 1, 0, true, emptyList()) },
            collectInsiders = { _, _ -> called = true; InsiderCollectSummary(0, 0, emptyList()) },
        )

        assertThat(called).isFalse()
    }
}
