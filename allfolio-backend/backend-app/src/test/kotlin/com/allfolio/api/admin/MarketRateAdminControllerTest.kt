package com.allfolio.api.admin

import com.allfolio.fx.EcosApiException
import com.allfolio.fx.EcosStatListClient
import com.allfolio.market.rate.RateCollectService
import com.allfolio.market.rate.RateCollectSummary
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class MarketRateAdminControllerTest {

    private val service: RateCollectService = mock(RateCollectService::class.java)
    private val statList: EcosStatListClient = mock(EcosStatListClient::class.java)
    private val controller = MarketRateAdminController(service, statList)

    /**
     * `requested == 0`은 설정이 빈 것이지 상류 장애가 아니다. 502로 내면 운영자가 멀쩡한
     * 한국은행을 확인하러 가는데 진짜 문제는 `application.yml`이다 — 문구가 그리로 보내야 한다.
     */
    @Test
    fun `수집 대상이 없으면 500이다`() {
        stub(summary(requested = 0, collected = 0))

        val thrown = assertThrows(ResponseStatusException::class.java) { controller.collect(null, null) }

        assertThat(thrown.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        assertThat(thrown.reason).contains("market-rate.series")
    }

    /**
     * 요청은 있었는데 한 건도 못 건진 경우다. 200으로 내보내면 크론 잡이 초록으로 끝나고
     * 금리가 끊긴 걸 아무도 모른다. 502인 이유는 상류(ECOS) 문제이기 때문이다.
     */
    @Test
    fun `전멸은 502다`() {
        stub(summary(requested = 6, collected = 0, failed = 6, failures = listOf("KTB_3Y: HTTP 500")))

        val thrown = assertThrows(ResponseStatusException::class.java) { controller.collect(null, null) }

        assertThat(thrown.statusCode).isEqualTo(HttpStatus.BAD_GATEWAY)
        // 사유가 없으면 "금리 수집 실패"만 보고 다시 로그를 뒤져야 한다
        assertThat(thrown.reason).contains("한 건도").contains("KTB_3Y: HTTP 500")
    }

    /**
     * 저장이 0건이어도 **실패가 없으면 장애가 아니다.** 모든 종목이 정상적으로 빈 응답을 준
     * 날에도 collected는 0이 된다 — 그걸 502로 부르면 멀쩡한 한국은행을 확인하러 가게 된다.
     * 이 상태는 emptySeries가 설명하고 워크플로가 경고로 띄운다.
     */
    @Test
    fun `실패 없이 0건이면 200이다`() {
        stub(summary(requested = 6, collected = 0, failed = 0).copy(emptySeries = listOf("BASE_RATE")))

        val response = controller.collect(null, null)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.emptySeries).containsExactly("BASE_RATE")
    }

    /** 부분 실패까지 빨갛게 칠하면 매일 빨간 잡을 보게 되고, 그러면 진짜 전멸도 안 보인다 */
    @Test
    fun `부분 실패는 200이다`() {
        stub(summary(requested = 6, collected = 5, failed = 1, failures = listOf("CD_91D: HTTP 500")))

        val response = controller.collect(null, null)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.failed).isEqualTo(1)
    }

    /**
     * 기본 창은 KST 오늘 기준 최근 2주다.
     *
     * `to`를 KST로 못 박는 이유: Render 컨테이너는 UTC라 `LocalDate.now()`를 쓰면 KST 새벽에
     * 도는 실행이 하루 전을 끝점으로 잡고, 그 날 공표된 금리가 통째로 빠진 채 잡은 초록으로 끝난다.
     * (UTC 머신에서는 하루 중 9시간 동안만 두 값이 갈리므로, 이 단언이 잡히는 자리는 KST 로컬이다.)
     *
     * 간격을 함께 보는 이유: 끝점만 맞고 창이 하루로 줄면 ECOS 정정과 밀린 공표를 놓치는데,
     * 그건 값이 멀쩡해서 사후에 눈으로 못 잡는다.
     */
    @Test
    fun `날짜를 안 주면 KST 오늘 기준 최근 2주를 조회한다`() {
        stub(summary(requested = 6, collected = 6))

        controller.collect(null, null)

        val from = ArgumentCaptor.forClass(LocalDate::class.java)
        val to = ArgumentCaptor.forClass(LocalDate::class.java)
        verify(service).collect(
            from.capture() ?: LocalDate.EPOCH,
            to.capture() ?: LocalDate.EPOCH,
            any(LocalDateTime::class.java) ?: LocalDateTime.MIN,
        )

        assertThat(to.value)
            .describedAs("끝점은 KST 오늘이어야 한다. LocalDate.now()를 쓰면 UTC 컨테이너에서 하루 밀린다")
            .isEqualTo(LocalDate.now(ZoneId.of("Asia/Seoul")))
        assertThat(ChronoUnit.DAYS.between(from.value, to.value)).isEqualTo(14)
    }

    /** 주어진 구간은 그대로 내려가야 한다 — 초기 백필이 이 경로를 쓴다 */
    @Test
    fun `날짜를 주면 그대로 넘긴다`() {
        stub(summary(requested = 6, collected = 6))

        controller.collect(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31))

        val from = ArgumentCaptor.forClass(LocalDate::class.java)
        val to = ArgumentCaptor.forClass(LocalDate::class.java)
        verify(service).collect(
            from.capture() ?: LocalDate.EPOCH,
            to.capture() ?: LocalDate.EPOCH,
            any(LocalDateTime::class.java) ?: LocalDateTime.MIN,
        )

        assertThat(from.value).isEqualTo(LocalDate.of(2020, 1, 1))
        assertThat(to.value).isEqualTo(LocalDate.of(2020, 12, 31))
    }

    /**
     * 상류가 이상한 것이지 우리 요청이 틀린 게 아니다. 전역 폴백의 500 + "서버 오류"로 뭉개지면
     * 코드를 확인하러 온 사람이 우리 버그를 찾으러 간다.
     */
    @Test
    fun `통계표 목록의 ECOS 오류는 502로 나간다`() {
        `when`(statList.tables(any())).thenThrow(EcosApiException("HTTP-500", "ECOS가 HTTP 500 을 반환했습니다"))

        val thrown = assertThrows(ResponseStatusException::class.java) { controller.tables("721Y001") }

        assertThat(thrown.statusCode).isEqualTo(HttpStatus.BAD_GATEWAY)
    }

    /** 항목 목록도 같은 대우를 받아야 한다 — catch를 하나만 달면 이쪽이 500으로 샌다 */
    @Test
    fun `항목 목록의 ECOS 오류는 502로 나간다`() {
        `when`(statList.items(any() ?: "")).thenThrow(EcosApiException("HTTP-404", "ECOS가 HTTP 404 를 반환했습니다"))

        val thrown = assertThrows(ResponseStatusException::class.java) { controller.items("721Y001") }

        assertThat(thrown.statusCode).isEqualTo(HttpStatus.BAD_GATEWAY)
    }

    /** 탐색 엔드포인트는 응답을 파싱하지 않는다 — 오류 응답(RESULT)도 그대로 보여야 한다 */
    @Test
    fun `통계표 목록은 상류 본문을 그대로 돌려준다`() {
        val body = """{"RESULT":{"CODE":"INFO-200","MESSAGE":"해당하는 데이터가 없습니다."}}"""
        `when`(statList.tables(any())).thenReturn(body)

        val response = controller.tables(null)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).isEqualTo(body)
    }

    private fun stub(summary: RateCollectSummary) {
        `when`(
            service.collect(
                any(LocalDate::class.java) ?: LocalDate.EPOCH,
                any(LocalDate::class.java) ?: LocalDate.EPOCH,
                any(LocalDateTime::class.java) ?: LocalDateTime.MIN,
            ),
        ).thenReturn(summary)
    }

    private fun summary(
        requested: Int,
        collected: Int,
        failed: Int = 0,
        failures: List<String> = emptyList(),
    ) = RateCollectSummary(
        from = LocalDate.of(2026, 7, 30),
        to = LocalDate.of(2026, 8, 13),
        requested = requested,
        collected = collected,
        inserted = collected,
        updated = 0,
        unchanged = 0,
        skippedRows = 0,
        outOfRange = 0,
        emptySeries = emptyList(),
        failed = failed,
        failures = failures,
    )
}
