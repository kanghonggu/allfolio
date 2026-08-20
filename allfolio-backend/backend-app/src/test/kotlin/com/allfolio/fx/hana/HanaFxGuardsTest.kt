package com.allfolio.fx.hana

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * 하나은행은 마크업이 바뀌면 예외가 아니라 조용히 빈/부분 테이블을 준다.
 * 판정은 "저장을 막을 것인가"만 결정하고, 저장 자체는 서비스가 한다.
 */
class HanaFxGuardsTest {

    private val guards = HanaFxGuards()

    private fun rows(vararg pairs: Pair<String, String>) =
        pairs.map { (c, r) -> HanaFxRow(c, BigDecimal(r), null, null, null, null) }

    /** USD 한 행 + 나머지 더미. 행 수 가드만 볼 때 쓴다 */
    private fun rowsOfSize(n: Int) =
        rows("USD" to "1390") + (2..n).map { HanaFxRow("C$it", BigDecimal("10"), null, null, null, null) }

    @Test
    fun `정상이면 아무것도 걸리지 않는다`() {
        val anomalies = guards.check(
            rows = rows("USD" to "1390", "JPY" to "9.5"),
            previousRates = mapOf("USD" to BigDecimal("1385")),
            previousRowCount = 2,
            force = false,
        )

        assertThat(anomalies).isEmpty()
    }

    @Test
    fun `USD가 없으면 걸린다`() {
        val anomalies = guards.check(
            rows = rows("JPY" to "9.5"),
            previousRates = emptyMap(),
            previousRowCount = null,
            force = false,
        )

        assertThat(anomalies).anyMatch { it.contains("USD") }
    }

    @Test
    fun `행 수가 직전의 절반 미만이면 걸린다`() {
        val anomalies = guards.check(
            rows = rows("USD" to "1390"),
            previousRates = emptyMap(),
            previousRowCount = 58,
            force = false,
        )

        assertThat(anomalies).anyMatch { it.contains("행 수") }
    }

    @Test
    fun `행 수가 직전의 절반이면 걸리지 않는다`() {
        val anomalies = guards.check(
            rows = rows("USD" to "1390", "JPY" to "9.5"),
            previousRates = emptyMap(),
            previousRowCount = 4,
            force = false,
        )

        assertThat(anomalies).isEmpty()
    }

    @Test
    fun `직전 값 대비 2퍼센트를 넘게 움직이면 걸린다`() {
        val anomalies = guards.check(
            rows = rows("USD" to "1420"),           // 1385 → 1420 = +2.53%
            previousRates = mapOf("USD" to BigDecimal("1385")),
            previousRowCount = 1,
            force = false,
        )

        assertThat(anomalies).anyMatch { it.contains("USD") && it.contains("변동") }
    }

    @Test
    fun `2퍼센트 이내면 걸리지 않는다`() {
        val anomalies = guards.check(
            rows = rows("USD" to "1410"),           // 1385 → 1410 = +1.80%
            previousRates = mapOf("USD" to BigDecimal("1385")),
            previousRowCount = 1,
            force = false,
        )

        assertThat(anomalies).isEmpty()
    }

    @Test
    fun `force는 변동 가드만 뚫고 USD 부재는 못 뚫는다`() {
        val moved = guards.check(
            rows = rows("USD" to "1420"),
            previousRates = mapOf("USD" to BigDecimal("1385")),
            previousRowCount = 1,
            force = true,
        )
        assertThat(moved).isEmpty()

        val missing = guards.check(
            rows = rows("JPY" to "9.5"),
            previousRates = emptyMap(),
            previousRowCount = null,
            force = true,
        )
        assertThat(missing).anyMatch { it.contains("USD") }
    }

    @Test
    fun `force는 행 수 급감도 못 뚫는다`() {
        val anomalies = guards.check(
            rows = rows("USD" to "1390"),
            previousRates = emptyMap(),
            previousRowCount = 58,
            force = true,
        )

        assertThat(anomalies).anyMatch { it.contains("행 수") }
    }

    @Test
    fun `하락도 2퍼센트를 넘으면 걸린다`() {
        // 파싱이 엉뚱한 컬럼을 읽으면 값은 어느 쪽으로든 튄다. 상승만 보면 절반을 놓친다
        val anomalies = guards.check(
            rows = rows("USD" to "1350"),           // 1385 → 1350 = -2.53%
            previousRates = mapOf("USD" to BigDecimal("1385")),
            previousRowCount = 1,
            force = false,
        )

        assertThat(anomalies).anyMatch { it.contains("USD") && it.contains("변동") }
    }

    @Test
    fun `정확히 2퍼센트면 걸리지 않는다`() {
        val anomalies = guards.check(
            rows = rows("USD" to "1020"),           // 1000 → 1020 = 정확히 +2.00%
            previousRates = mapOf("USD" to BigDecimal("1000")),
            previousRowCount = 1,
            force = false,
        )

        assertThat(anomalies).isEmpty()
    }

    @Test
    fun `2퍼센트를 아주 조금만 넘어도 걸린다`() {
        // 경계를 양쪽에서 조여야 임계값 상수를 바꾼 변이가 잡힌다
        val anomalies = guards.check(
            rows = rows("USD" to "1020.01"),        // 1000 → 1020.01 = +2.001%
            previousRates = mapOf("USD" to BigDecimal("1000")),
            previousRowCount = 1,
            force = false,
        )

        assertThat(anomalies).anyMatch { it.contains("USD") && it.contains("변동") }
    }

    @Test
    fun `행 수가 절반에서 하나만 모자라도 걸린다`() {
        val anomalies = guards.check(
            rows = rowsOfSize(49),
            previousRates = emptyMap(),
            previousRowCount = 100,
            force = false,
        )

        assertThat(anomalies).anyMatch { it.contains("행 수") }
    }

    @Test
    fun `USD 아닌 통화의 변동도 검사한다`() {
        val anomalies = guards.check(
            rows = rows("USD" to "1390", "JPY" to "9.9"),   // USD +0.36%, JPY 9.5 → 9.9 = +4.2%
            previousRates = mapOf("USD" to BigDecimal("1385"), "JPY" to BigDecimal("9.5")),
            previousRowCount = 2,
            force = false,
        )

        assertThat(anomalies).singleElement().matches { it.contains("JPY") && it.contains("변동") }
    }

    @Test
    fun `직전 값이 0이면 변동 검사를 건너뛴다`() {
        // 0으로 나누면 ArithmeticException이 터져 수집 전체가 죽는다. 비교를 포기하는 쪽이 맞다
        val anomalies = guards.check(
            rows = rows("USD" to "1390"),
            previousRates = mapOf("USD" to BigDecimal.ZERO),
            previousRowCount = 1,
            force = false,
        )

        assertThat(anomalies).isEmpty()
    }

    @Test
    fun `빈 테이블은 USD 부재와 행 수 급감을 모두 보고한다`() {
        // 가장 흔한 실패 모드다. 둘이 함께 걸린다는 사실이 "마크업이 바뀌었다"와
        // "USD가 빠졌다"를 가르므로, 하나로 줄이면 운영자가 보강 신호를 잃는다
        val anomalies = guards.check(
            rows = emptyList(),
            previousRates = mapOf("USD" to BigDecimal("1385")),
            previousRowCount = 58,
            force = false,
        )

        assertThat(anomalies).hasSize(2)
        assertThat(anomalies[0]).contains("USD")
        assertThat(anomalies[1]).contains("행 수")
    }

    @Test
    fun `직전 행 수가 0이면 행 수 검사를 건너뛴다`() {
        // 직전 회차 조회가 빈 목록이면 호출자는 null이 아니라 0을 넘기기 쉽다.
        // 없는 데이터와 비교해 막으면 안 된다 — 사고가 아니라 계약이다
        val anomalies = guards.check(
            rows = rows("USD" to "1390"),
            previousRates = emptyMap(),
            previousRowCount = 0,
            force = false,
        )

        assertThat(anomalies).isEmpty()
    }

    @Test
    fun `행 수 메시지가 적용된 임계값을 보여준다`() {
        val anomalies = guards.check(
            rows = rowsOfSize(49),
            previousRates = emptyMap(),
            previousRowCount = 100,
            force = false,
        )

        assertThat(anomalies).singleElement().matches { it.contains("49 < 50") && it.contains("직전 100") }
    }

    @Test
    fun `변동 메시지가 임계값을 상수에서 가져온다`() {
        val anomalies = guards.check(
            rows = rows("USD" to "1420"),
            previousRates = mapOf("USD" to BigDecimal("1385")),
            previousRowCount = 1,
            force = false,
        )

        // 실제 규칙은 상대·절대 **둘 다**다. 메시지가 상대만 말하면 운영자가
        // "2.5% 움직였는데 왜 안 걸렸지"를 코드를 열어야만 알 수 있다
        assertThat(anomalies).singleElement().matches { it.contains("2%") && it.contains("0.01") }
    }

    @Test
    fun `비교 대상이 없는 첫 수집은 통과시킨다`() {
        // 직전 값도 직전 행 수도 없다. 여기서 막으면 수집을 영영 시작할 수 없다
        val anomalies = guards.check(
            rows = rows("USD" to "1390"),
            previousRates = emptyMap(),
            previousRowCount = null,
            force = false,
        )

        assertThat(anomalies).isEmpty()
    }

    @Test
    fun `저가 통화의 1틱 반올림 변동은 2퍼센트를 넘어도 걸리지 않는다`() {
        // KHR은 소수 2자리로 고시된다. 최소 표현 단위 0.01이 0.34 기준으로 이미 2.94%라
        // 상대 임계값만으로는 이 통화가 **원리적으로** 가드를 만족할 수 없다.
        // 실제로 2026-08-19~20 수집이 이 한 통화 때문에 4연속 422로 막혔고,
        // 그동안 평가 경로가 쓰는 USD까지 함께 저장되지 못했다
        val anomalies = guards.check(
            rows = rows("USD" to "1394.4", "KHR" to "0.35"),
            previousRates = mapOf("USD" to BigDecimal("1390"), "KHR" to BigDecimal("0.34")),
            previousRowCount = 2,
            force = false,
        )

        assertThat(anomalies).isEmpty()
    }

    @Test
    fun `저가 통화도 절대 하한을 넘게 움직이면 걸린다`() {
        // 하한은 반올림 잡음을 걸러내려는 것이지 저가 통화를 검사에서 빼려는 게 아니다.
        // 2틱은 더 이상 표현 한계로 설명되지 않는다
        val anomalies = guards.check(
            rows = rows("USD" to "1394.4", "KHR" to "0.36"),
            previousRates = mapOf("USD" to BigDecimal("1390"), "KHR" to BigDecimal("0.34")),
            previousRowCount = 2,
            force = false,
        )

        assertThat(anomalies).singleElement().matches { it.contains("KHR") && it.contains("변동") }
    }

    @Test
    fun `절대 하한을 아주 조금만 넘어도 걸린다`() {
        // 경계를 양쪽에서 조여야 하한 상수를 바꾼 변이가 잡힌다.
        // 위 테스트만 있으면 하한을 0.02로 올려도 통과한다
        val anomalies = guards.check(
            rows = rows("USD" to "1394.4", "KHR" to "0.3501"),
            previousRates = mapOf("USD" to BigDecimal("1390"), "KHR" to BigDecimal("0.34")),
            previousRowCount = 2,
            force = false,
        )

        assertThat(anomalies).singleElement().matches { it.contains("KHR") && it.contains("변동") }
    }

    @Test
    fun `절대 하한이 고액 통화의 2퍼센트 가드를 약화시키지 않는다`() {
        // 하한을 넉넉하게 잡고 싶은 유혹이 있는데, 그러면 이 가드가 지키려던 USD가 뚫린다.
        // USD의 2%는 27원대라 어떤 반올림 잡음보다도 세 자릿수 크다
        val anomalies = guards.check(
            rows = rows("USD" to "1420"),
            previousRates = mapOf("USD" to BigDecimal("1385")),
            previousRowCount = 1,
            force = false,
        )

        assertThat(anomalies).singleElement().matches { it.contains("USD") && it.contains("변동") }
    }
}
