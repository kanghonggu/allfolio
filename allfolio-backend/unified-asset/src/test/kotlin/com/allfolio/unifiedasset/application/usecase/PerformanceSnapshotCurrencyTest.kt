package com.allfolio.unifiedasset.application.usecase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * `record()`는 performance_daily와 nav_currency_daily **둘 다** 쓴다 (AF-106).
 *
 * 읽기 쪽(`JdbcNavFxHistorySource`)은 fail-closed다 — performance_daily에 있는 날짜 중
 * 통화 행이 없는 날이 하나라도 있으면 그 구간의 기여도 분해를 통째로 포기한다. 계좌 sync
 * 경로가 오늘 날짜로 쓰고 화면 프리셋은 전부 오늘로 끝나므로, 통화 행을 안 남기는 호출
 * 경로가 하나만 있어도 화면 블록이 영원히 안 뜬다. 그래서 "둘 다 쓴다"가 계약이다.
 *
 * 날짜는 오늘일 수 없는 과거를 쓴다 — 이 머신이 KST라 `LocalDate.now()`로 검증하면
 * 파라미터를 무시하는 구현에도 통과한다.
 */
class PerformanceSnapshotCurrencyTest {

    private val ymd = LocalDate.of(2024, 2, 29)

    @Test
    fun `두 테이블에 같은 날짜로 쓴다`() {
        val jdbc = CapturingJdbcTemplate()
        val store = RecordingNavCurrencyStore()
        val userId = UUID.randomUUID()

        snapshotService(jdbc, store)
            .record(userId, mapOf("KRW" to BigDecimal("1000"), "USD" to BigDecimal("10")), ymd)

        val (portfolioId, date, values) = store.calls.singleOrNull()
            ?: error("nav_currency_daily 기록이 없다 — calls=${store.calls.size}")
        assertEquals(userId, portfolioId)
        // 날짜가 갈리면 읽기 쪽이 그 날의 통화 행을 못 찾아 분해를 포기한다
        assertEquals(jdbc.insertedDate(), date) { "performance_daily와 다른 날짜로 썼다" }
        assertEquals(ymd, date)
        assertEquals(setOf("KRW", "USD"), values.map { it.currency }.toSet())
    }

    @Test
    fun `합계 불변식 — 통화별 value_native × fx_rate의 합이 nav와 1원 이내다`() {
        val jdbc = CapturingJdbcTemplate()
        val store = RecordingNavCurrencyStore()

        snapshotService(jdbc, store).record(
            UUID.randomUUID(),
            mapOf("KRW" to BigDecimal("1000000"), "USD" to BigDecimal("1000")),
            ymd,
        )

        val values = store.calls.single().third
        val sum = values.fold(BigDecimal.ZERO) { acc, v -> acc + v.valueNative.multiply(v.fxRate) }
        val nav = jdbc.insertedNav()
        // AF-106 화면이 기대는 항등식. 통화당 원 단위 반올림만큼만 어긋난다 —
        // fx_rate에 다른 값(1 등)을 실으면 여기서 벌어진다.
        assertTrue((sum - nav).abs() <= BigDecimal.ONE) { "합계 불변식 이탈: Σ=$sum nav=$nav values=$values" }
        // 1,000,000 + 1,000 × 1300.5 = 2,300,500
        assertEquals(0, BigDecimal("2300500").compareTo(nav)) { "nav=$nav" }
    }

    @Test
    fun `미지원 통화가 fx_rate = 1로 실린다`() {
        val jdbc = CapturingJdbcTemplate()
        val store = RecordingNavCurrencyStore()

        // 예외를 던지면 스냅샷 전체가 깨진다 — 미환산 자산은 rate=1 행으로 남아 진단 지표가 된다
        snapshotService(jdbc, store).record(UUID.randomUUID(), mapOf("JPY" to BigDecimal("50000")), ymd)

        val jpy = store.calls.single().third.single()
        assertEquals("JPY", jpy.currency)
        assertEquals(0, BigDecimal.ONE.compareTo(jpy.fxRate)) { "미지원 통화 환율이 1이 아니다: $jpy" }
        assertEquals(0, BigDecimal("50000").compareTo(jpy.valueNative))
        assertEquals(0, BigDecimal("50000").compareTo(jdbc.insertedNav()))
    }

    @Test
    fun `빈 맵이면 nav = 0이고 replace가 빈 목록으로 불린다`() {
        val jdbc = CapturingJdbcTemplate()
        val store = RecordingNavCurrencyStore()

        snapshotService(jdbc, store).record(UUID.randomUUID(), emptyMap(), ymd)

        assertEquals(0, BigDecimal.ZERO.compareTo(jdbc.insertedNav()))
        // 빈 목록이어도 반드시 불러야 한다 — replace는 DELETE 후 early return이라,
        // 이 호출이 "자산을 전부 판 날"에 묵은 통화 행이 남는 걸 막는다
        val (_, date, values) = store.calls.singleOrNull()
            ?: error("자산이 없을 때 replace가 불리지 않았다 — 묵은 통화 행이 남는다")
        assertEquals(ymd, date)
        assertTrue(values.isEmpty()) { "빈 목록이 아니다: $values" }
    }
}
