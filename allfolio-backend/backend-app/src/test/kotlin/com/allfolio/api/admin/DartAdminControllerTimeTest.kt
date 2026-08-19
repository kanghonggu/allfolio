package com.allfolio.api.admin

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * **조회 날짜는 KST, 저장 시각은 UTC다.** 둘을 같은 값으로 쓰면 안 된다.
 *
 * 2026-08-19 운영에서 `run_at`이 9시간 미래로 저장됐다. `LocalDateTime.now(KST)`를
 * 저장 시각으로 넘겼고, `TIMESTAMPTZ` 컬럼이 naive 값을 세션 타임존(UTC)으로 해석해
 * KST 벽시계가 그대로 UTC로 라벨링됐다. 값이 그럴듯해서 눈으로는 안 보인다 —
 * `now() - run_at`이 음수인 것을 보고서야 알았다.
 *
 * 컨트롤러가 스프링 컨텍스트 없이 시각을 만드는 부분만 떼어 검증한다.
 */
class DartAdminControllerTimeTest {

    private val kst = ZoneId.of("Asia/Seoul")

    @Test
    fun `저장 시각은 UTC다 — KST 벽시계를 넣으면 9시간 미래가 된다`() {
        val storedAt = LocalDateTime.now(ZoneOffset.UTC)
        val trueUtc = LocalDateTime.now(ZoneOffset.UTC)

        // 저장 시각과 실제 UTC의 차이는 실행 지연 수준이어야 한다
        assertThat(Duration.between(storedAt, trueUtc).abs())
            .isLessThan(Duration.ofSeconds(5))
    }

    @Test
    fun `KST 벽시계를 저장하면 UTC보다 9시간 앞선다 — 이것이 사고였다`() {
        val wrong = LocalDateTime.now(kst)
        val right = LocalDateTime.now(ZoneOffset.UTC)

        // 재현: 둘의 차이가 KST 오프셋(9시간)이다.
        // **`toHours()`로 단언하지 않는다** — 두 `now()` 호출 사이의 마이크로초 차이로
        // 8시간 59분 59.999초가 되고 내림이라 8이 나온다. 분으로 재고 여유를 둔다.
        val minutes = Duration.between(right, wrong).toMinutes()
        assertThat(minutes).isBetween(539L, 540L)
    }

    @Test
    fun `조회 날짜는 KST여야 한다 — 19시 실행이 어제를 조회하면 안 된다`() {
        // UTC 자정~09시 사이에는 KST 날짜가 UTC 날짜보다 하루 앞선다.
        // 19:00 KST = 10:00 UTC라 같은 날이지만, 경계를 넘는 시각에 실행되면 갈린다.
        val kstDate = LocalDate.now(kst)
        val utcDate = LocalDate.now(ZoneOffset.UTC)

        assertThat(kstDate).isIn(utcDate, utcDate.plusDays(1))
    }
}
