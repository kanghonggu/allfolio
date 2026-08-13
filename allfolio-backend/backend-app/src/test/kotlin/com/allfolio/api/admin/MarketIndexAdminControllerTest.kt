package com.allfolio.api.admin

import com.allfolio.market.index.DomesticIndexCollectSummary
import com.allfolio.market.index.IndexCollectService
import com.allfolio.market.index.IndexSlot
import com.allfolio.market.index.KisIndexClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.abs

class MarketIndexAdminControllerTest {

    private val collectService: IndexCollectService = mock(IndexCollectService::class.java)
    private val controller = MarketIndexAdminController(mock(KisIndexClient::class.java), collectService)

    private val summary = DomesticIndexCollectSummary(
        tradeDate = LocalDate.of(2026, 8, 12),
        slot = "CLOSE",
        requested = 3,
        collected = 3,
        inserted = 3,
        updated = 0,
        failed = 0,
        failures = emptyList(),
    )

    /**
     * 이 테스트가 이 파일에서 유일하게 중요한 것이다.
     *
     * [IndexCollectService.collect]는 받은 시각을 **UTC로 해석해** KST로 옮긴 뒤 거래일과
     * 시장상태를 뽑는다. 여기서 `LocalDateTime.now()`를 넘기면 KST 머신에서 +9가 두 번 먹어
     * 15:50 CLOSE 수집이 자정을 넘겨 다음 날 거래일로 박힌다. 그 행은 값이 멀쩡해서
     * 사후에 눈으로는 못 잡는다.
     *
     * KST 개발 머신에서는 두 값이 9시간 벌어지므로 이 단언이 실수를 잡는다.
     * (UTC 러너에서는 둘이 같아 이 테스트가 아무것도 못 잡는다 — 잡히는 자리는 로컬이고,
     * 실수가 만들어지는 자리도 로컬이라 그걸로 충분하다.)
     */
    @Test
    fun `수집 서비스에 UTC 현재 시각을 넘긴다`() {
        `when`(
            collectService.collect(
                any(IndexSlot::class.java) ?: IndexSlot.CLOSE,
                any(LocalDateTime::class.java) ?: LocalDateTime.MIN,
            )
        ).thenReturn(summary)

        val before = LocalDateTime.now(ZoneOffset.UTC)
        controller.collect(IndexSlot.CLOSE)

        val captor = ArgumentCaptor.forClass(LocalDateTime::class.java)
        verify(collectService).collect(
            any(IndexSlot::class.java) ?: IndexSlot.CLOSE,
            captor.capture() ?: LocalDateTime.MIN,
        )

        val skewSeconds = abs(Duration.between(before, captor.value).seconds)
        assertThat(skewSeconds)
            .describedAs("collect(now)는 UTC여야 한다. LocalDateTime.now()를 넘기면 KST에서 9시간 어긋난다")
            .isLessThan(60)
    }

    /** 슬롯은 그대로 내려가야 한다 — 바꿔치기되면 값이 그럴듯한 채로 엉뚱한 지점에 저장된다 */
    @Test
    fun `요청한 슬롯을 그대로 넘긴다`() {
        `when`(
            collectService.collect(
                any(IndexSlot::class.java) ?: IndexSlot.CLOSE,
                any(LocalDateTime::class.java) ?: LocalDateTime.MIN,
            )
        ).thenReturn(summary)

        controller.collect(IndexSlot.MID)

        val slotCaptor = ArgumentCaptor.forClass(IndexSlot::class.java)
        verify(collectService).collect(
            slotCaptor.capture() ?: IndexSlot.CLOSE,
            any(LocalDateTime::class.java) ?: LocalDateTime.MIN,
        )
        assertThat(slotCaptor.value).isEqualTo(IndexSlot.MID)
    }
}
