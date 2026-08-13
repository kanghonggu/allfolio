package com.allfolio.api.admin

import com.allfolio.market.index.DomesticIndexCollectSummary
import com.allfolio.market.index.IndexCollectService
import com.allfolio.market.index.IndexSlot
import com.allfolio.market.index.KisIndexClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
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

    // ── 전체 실패 = 장애 (AF-101) ──────────────────────────────────────────────
    //
    // IndexCollectService는 지수 하나가 터져도 나머지를 살리려고 예외 대신 요약으로 돌려준다.
    // 그래서 컨트롤러가 그대로 200을 내면 **세 지수가 전부 실패해도** 크론 잡이 초록으로 끝난다.
    // 지수 데이터가 끊긴 걸 아무도 모르게 되는 자리라 상태 코드로 못 박는다.

    private fun stubSummary(result: DomesticIndexCollectSummary) {
        `when`(
            collectService.collect(
                any(IndexSlot::class.java) ?: IndexSlot.CLOSE,
                any(LocalDateTime::class.java) ?: LocalDateTime.MIN,
            )
        ).thenReturn(result)
    }

    @Test
    fun `전부 실패하면 502를 낸다`() {
        stubSummary(
            summary.copy(
                requested = 3,
                collected = 0,
                inserted = 0,
                updated = 0,
                failed = 3,
                failures = listOf("KOSPI: KIS 응답에 output이 없습니다", "KOSDAQ: timeout", "KOSPI200: timeout"),
            )
        )

        val thrown = assertThrows(ResponseStatusException::class.java) {
            controller.collect(IndexSlot.CLOSE)
        }

        assertThat(thrown.statusCode).isEqualTo(HttpStatus.BAD_GATEWAY)
        // 잡 요약과 에러 애너테이션에 남는 문구다. 슬롯과 사유가 없으면 운영자가
        // "지수 수집 실패"만 보고 어느 지점이 왜 비었는지 다시 로그를 뒤져야 한다.
        assertThat(thrown.reason).contains("CLOSE").contains("KIS 응답에 output이 없습니다")
    }

    /**
     * 부분 실패는 200이다. 하나가 간헐적으로 실패할 때마다 잡을 빨갛게 칠하면
     * 매일 빨간 잡을 보게 되고, 그러면 진짜 전체 장애가 났을 때도 아무도 안 본다.
     * 부분 실패는 요약의 failures로 이미 보인다.
     */
    @Test
    fun `일부만 실패하면 200으로 요약을 돌려준다`() {
        stubSummary(
            summary.copy(
                requested = 3,
                collected = 2,
                inserted = 2,
                updated = 0,
                failed = 1,
                failures = listOf("KOSDAQ: timeout"),
            )
        )

        val response = controller.collect(IndexSlot.CLOSE)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.failures).containsExactly("KOSDAQ: timeout")
    }

    /**
     * requested == 0은 "아무도 아무것도 요청하지 않았다"(설정이 빔)이지 상류 장애가 아니다.
     * 그래서 502는 아니지만 **200도 아니다** — 200으로 두면 `market-index:` 키를 바꾸거나
     * `@ConfigurationProperties` prefix가 어긋나는 순간 수집이 멈춘 채 잡이 영원히 초록으로 끝난다.
     * 502가 막으려던 그 조용한 중단이다. 우리 설정 실수이므로 500이고, 문구는 운영자를
     * KIS가 아니라 설정 키로 보내야 한다.
     */
    @Test
    fun `수집 대상이 없으면 500을 낸다`() {
        stubSummary(
            summary.copy(
                requested = 0,
                collected = 0,
                inserted = 0,
                updated = 0,
                failed = 0,
                failures = emptyList(),
            )
        )

        val thrown = assertThrows(ResponseStatusException::class.java) {
            controller.collect(IndexSlot.CLOSE)
        }

        assertThat(thrown.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        assertThat(thrown.reason).contains("market-index.domestic")
    }
}
