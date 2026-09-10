package com.allfolio.snapshot.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * 일간 성과 엔진 (AF-199).
 *
 * ## 이 파일이 왜 지금 생기나
 *
 * 이 엔진은 **참조 테스트가 0건이었다.** 변이 기준선(AF-198)에서 지정된 변이 넷을 넣었더니
 * **넷 다 통과했다** — 계산식을 어떻게 망가뜨려도 아무도 안 말려 줬다는 뜻이다.
 * (`docs/mutation-baseline-returns-core.md` §1)
 *
 * 가장 위험한 것이 M1이었다. `- externalCashFlow`를 빼면 **입금이 그날 수익으로 잡힌다.**
 * QA에서 실제로 나왔던 `+2060%` 부류이고, `ReturnsCalculator` 쪽은 그 케이스를 무는데
 * 같은 일을 하는 이 엔진은 안 물고 있었다.
 *
 * ## 각 테스트가 어느 변이를 죽이나
 *
 * | 테스트 | 죽이는 변이 |
 * | --- | --- |
 * | `입금은 수익이 아니다` | M1 — 외부현금흐름 조정 제거 |
 * | `출금도 수익률을 왜곡하지 않는다` | M1 (반대 부호) |
 * | `누적은 곱으로 잇는다` · `첫날 누적은 당일과 같다` | M2 — 기하 연결을 산술로 |
 * | `전일 NAV가 0이면 계산하지 않는다` | M3 — 가드 무력화(0으로 나누기) |
 * | `알파는 포트폴리오에서 벤치마크를 뺀 값이다` | M4 — 부호 반전 |
 *
 * 나머지는 티켓이 요구한 경계(첫날·반올림)와 입력 검증이다.
 */
class DailyPerformanceEngineTest {

    private fun bd(v: String) = BigDecimal(v)

    /** scale 10까지 맞춰 비교한다 — 엔진이 그 자리에서 반올림한다 */
    private fun assertBd(expected: String, actual: BigDecimal) =
        assertEquals(0, bd(expected).compareTo(actual), "expected $expected but was $actual")

    // ── 외부 현금흐름 (M1) ──────────────────────────────────────

    /**
     * 🔴 **이 파일의 존재 이유.**
     *
     * 1,000을 넣어 NAV가 1,000 → 2,000이 됐다. 늘어난 1,000은 **전부 입금**이고 수익이 아니다.
     * 조정을 빼면 `+100%`가 되어 화면에 그대로 나간다.
     */
    @Test
    fun `입금은 수익이 아니다`() {
        val s = DailyPerformanceEngine.calculate(
            todayNav = bd("2000"), yesterdayNav = bd("1000"), externalCashFlow = bd("1000"),
        )
        assertBd("0", s.dailyReturn)
    }

    /** 출금은 음수로 들어온다. 부호를 한쪽만 맞춰 두면 여기서 갈린다. */
    @Test
    fun `출금도 수익률을 왜곡하지 않는다`() {
        // 1,000을 빼서 2,000 → 1,000. 순수 손익은 0이다.
        val s = DailyPerformanceEngine.calculate(
            todayNav = bd("1000"), yesterdayNav = bd("2000"), externalCashFlow = bd("-1000"),
        )
        assertBd("0", s.dailyReturn)
    }

    /** 현금흐름이 있고 **동시에** 손익도 있는 경우 — 둘이 섞이면 안 된다 */
    @Test
    fun `입금과 손익이 같이 있어도 손익만 잡는다`() {
        // 전일 1,000 → 입금 1,000 → 100을 벌어 2,100. 수익률은 100/1000 = 10%
        val s = DailyPerformanceEngine.calculate(
            todayNav = bd("2100"), yesterdayNav = bd("1000"), externalCashFlow = bd("1000"),
        )
        assertBd("0.1", s.dailyReturn)
    }

    // ── 누적 연결 (M2) ─────────────────────────────────────────

    /**
     * 누적은 **곱**으로 잇는다. `(1+0.1)(1+0.1)-1 = 0.21`이지 `0.1+0.1 = 0.2`가 아니다.
     * 산술로 바꾸면 구간이 늘수록 벌어져 **기간이 길수록 발견이 늦다.**
     */
    @Test
    fun `누적은 곱으로 잇는다`() {
        val s = DailyPerformanceEngine.calculate(
            todayNav = bd("1100"), yesterdayNav = bd("1000"),
            previousCumulativeReturn = bd("0.1"),
        )
        assertBd("0.1", s.dailyReturn)
        assertBd("0.21", s.cumulativeReturn)
    }

    /** 첫날은 전일 누적이 없다. `(1+0)(1+r)-1 = r`이라 당일 수익률과 같아야 한다. */
    @Test
    fun `첫날 누적은 당일과 같다`() {
        val s = DailyPerformanceEngine.calculate(
            todayNav = bd("1050"), yesterdayNav = bd("1000"),
            previousCumulativeReturn = null,
        )
        assertBd("0.05", s.dailyReturn)
        assertBd("0.05", s.cumulativeReturn)
    }

    /** 손실 구간에서도 곱이다 — 부호가 섞일 때 산술과 가장 크게 갈린다 */
    @Test
    fun `손실이 섞여도 곱으로 잇는다`() {
        // (1+0.5)(1−0.2)−1 = 0.2
        val s = DailyPerformanceEngine.calculate(
            todayNav = bd("800"), yesterdayNav = bd("1000"),
            previousCumulativeReturn = bd("0.5"),
        )
        assertBd("-0.2", s.dailyReturn)
        assertBd("0.2", s.cumulativeReturn)
    }

    // ── 전일 NAV 0 가드 (M3) ───────────────────────────────────

    /**
     * 🔴 계좌 개설 첫날은 전일 NAV가 0이다. **가드가 없으면 0으로 나눈다.**
     *
     * 수익률을 0으로 두는 것이 맞다 — 기저가 없으면 비율이 정의되지 않는다.
     */
    @Test
    fun `전일 NAV가 0이면 계산하지 않는다`() {
        val s = DailyPerformanceEngine.calculate(todayNav = bd("1000"), yesterdayNav = BigDecimal.ZERO)

        assertBd("1000", s.nav)
        assertBd("0", s.dailyReturn)
        assertBd("0", s.cumulativeReturn)
        assertNull(s.benchmarkReturn)
        assertNull(s.alpha)
    }

    /** 전일 NAV가 0이면 **전일 누적이 있어도** 계산하지 않는다 — 가드가 먼저다 */
    @Test
    fun `전일 NAV가 0이면 누적도 이어붙이지 않는다`() {
        val s = DailyPerformanceEngine.calculate(
            todayNav = bd("1000"), yesterdayNav = BigDecimal.ZERO,
            previousCumulativeReturn = bd("0.5"),
        )
        assertBd("0", s.cumulativeReturn)
    }

    // ── 알파 (M4) ──────────────────────────────────────────────

    /**
     * 알파 = 포트폴리오 − 벤치마크. 뒤집으면 **지수를 이겼는지 졌는지가 반대로 보인다.**
     */
    @Test
    fun `알파는 포트폴리오에서 벤치마크를 뺀 값이다`() {
        val s = DailyPerformanceEngine.calculate(
            todayNav = bd("1020"), yesterdayNav = bd("1000"),
            benchmarkReturn = bd("0.01"),
        )
        assertBd("0.02", s.dailyReturn)
        assertBd("0.01", s.alpha!!)
    }

    /** 진 날은 음수여야 한다 — 부호 반전이면 여기서도 갈린다 */
    @Test
    fun `벤치마크에 지면 알파가 음수다`() {
        val s = DailyPerformanceEngine.calculate(
            todayNav = bd("1005"), yesterdayNav = bd("1000"),
            benchmarkReturn = bd("0.02"),
        )
        assertBd("-0.015", s.alpha!!)
    }

    @Test
    fun `벤치마크가 없으면 알파도 없다`() {
        val s = DailyPerformanceEngine.calculate(todayNav = bd("1100"), yesterdayNav = bd("1000"))
        assertNull(s.benchmarkReturn)
        assertNull(s.alpha)
    }

    // ── 경계 ───────────────────────────────────────────────────

    /**
     * 나눗셈은 scale 10 · HALF_UP이다. `2/3 = 0.6666666666…`의 열한째 자리가 6이라
     * 올림이 되어 `0.6666666667`이다. 버림으로 바뀌면 마지막 자리가 갈린다.
     */
    @Test
    fun `수익률은 소수 열째 자리에서 반올림한다`() {
        val s = DailyPerformanceEngine.calculate(todayNav = bd("5"), yesterdayNav = bd("3"))
        assertEquals("0.6666666667", s.dailyReturn.toPlainString())
    }

    @Test
    fun `NAV가 음수면 예외다`() {
        assertThrows(PerformanceException::class.java) {
            DailyPerformanceEngine.calculate(todayNav = bd("-1"), yesterdayNav = bd("1000"))
        }
        assertThrows(PerformanceException::class.java) {
            DailyPerformanceEngine.calculate(todayNav = bd("1000"), yesterdayNav = bd("-1"))
        }
    }

    /** 움직임이 없는 날 — 0을 0으로 만드는 경로가 예외 없이 지나가야 한다 */
    @Test
    fun `변동이 없으면 수익률은 0이다`() {
        val s = DailyPerformanceEngine.calculate(todayNav = bd("1000"), yesterdayNav = bd("1000"))
        assertBd("0", s.dailyReturn)
        assertBd("0", s.cumulativeReturn)
    }
}
