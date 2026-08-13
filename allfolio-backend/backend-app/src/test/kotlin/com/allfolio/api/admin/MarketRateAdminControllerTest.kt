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
import org.mockito.Mockito.never
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
        // 전량 실패와 전 종목 0건은 운영자를 다른 곳으로 보낸다 — 문구가 갈려야 한다
        assertThat(thrown.reason).contains("전량 실패")
    }

    /**
     * **실패가 하나도 없이 전 종목이 0건인 실행이 정확히 "통계표 코드가 전부 틀렸다"의 모양이다.**
     * ECOS는 없는 코드에 오류가 아니라 0건을 주므로, 이걸 200으로 내보내면 잡이 영원히 초록으로
     * 끝난다 — 이 기능이 막으려던 실패 그 자체가 감시망을 그대로 통과한다.
     *
     * "정상적으로 전부 비었다"는 상태는 존재하지 않는다: 설정에는 매일 공표되는 계열(국고채·CD)이
     * 변경 시 공표 계열과 섞여 있고, 달력 14일에 국내 영업일이 하나도 없는 경우는 없다.
     */
    @Test
    fun `실패가 없어도 전 종목이 0건이면 502다`() {
        val allSix = listOf("BASE_RATE", "CD_91D", "KTB_3Y", "KTB_10Y", "CORP_AA", "COFIX")
        stub(summary(requested = 6, collected = 0, failed = 0).copy(emptySeries = allSix))

        val thrown = assertThrows(ResponseStatusException::class.java) { controller.collect(null, null) }

        assertThat(thrown.statusCode).isEqualTo(HttpStatus.BAD_GATEWAY)
        // 상류를 확인하러 보내면 안 된다 — 할 일은 코드 확인이다
        assertThat(thrown.reason).contains("전 종목 0건").contains("코드를 확인")
        assertThat(thrown.reason).doesNotContain("전량 실패")
    }

    /**
     * 저장이 0건이어도 **일부만 비었으면 장애가 아니다.** 기준금리처럼 변경 시에만 공표되는 계열은
     * 2주 창에 값이 없는 게 정상이다 — 그걸 502로 부르면 멀쩡한 한국은행을 확인하러 가게 된다.
     * 이 상태는 emptySeries가 설명하고 워크플로가 경고로 띄운다.
     */
    @Test
    fun `일부만 비었으면 0건이어도 200이다`() {
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
     * **여기서 잡지 않는 것이 맞다.** `GlobalExceptionHandler`가 [EcosApiException]을 이미 다루고
     * 502(상류 실패)와 500(`NO_KEY` 등 우리 설정 누락)을 code로 가른다 — 컨트롤러에서 전부 502로
     * 갈아끼우면 키를 아직 안 넣은 상태가 "한국은행 장애"로 보고된다. 이 엔드포인트를 부르는 시점이
     * 대개 키를 넣기 **전**이라 하필 가장 흔한 경로고, 덤으로 핸들러가 싣는 `code` 필드도 사라진다.
     * (`MarketIndexAdminController`의 catch는 `KisIndexException` 핸들러가 advice에 없어서 필요한
     *  것이다 — 그 모양만 베끼면 여기서는 손해가 된다.)
     */
    @Test
    fun `통계표 목록의 ECOS 오류는 그대로 전파된다`() {
        `when`(statList.tables(any())).thenThrow(EcosApiException("NO_KEY", "ECOS 인증키가 설정되지 않았습니다"))

        val thrown = assertThrows(EcosApiException::class.java) { controller.tables("721Y001") }

        // code가 살아 있어야 핸들러가 500(설정 누락)과 502(상류)를 가른다
        assertThat(thrown.code).isEqualTo("NO_KEY")
    }

    /** 항목 목록도 같은 대우다 — 한쪽만 잡으면 두 엔드포인트의 상태 코드가 갈린다 */
    @Test
    fun `항목 목록의 ECOS 오류는 그대로 전파된다`() {
        `when`(statList.items(any() ?: "")).thenThrow(EcosApiException("HTTP-404", "ECOS가 HTTP 404 를 반환했습니다"))

        val thrown = assertThrows(EcosApiException::class.java) { controller.items("721Y001") }

        assertThat(thrown.code).isEqualTo("HTTP-404")
    }

    /**
     * **`stat`은 인증키가 첫 세그먼트로 들어간 경로에 그대로 이어 붙는다.**
     * `/`나 `%2F`를 넣으면 요청이 ecos.bok.or.kr의 다른 경로·쿼리로 새면서 우리 인증키를 달고 간다 —
     * 되울리는 오류 본문을 일부러 끌어내는 방법이 그것이다. 형제 클라이언트는 같은 문자열을
     * `application.yml`에서 받지만 이쪽은 살아 있는 요청 파라미터라, 모양은 같아도 신뢰 경계가 다르다.
     */
    @Test
    fun `경로 문자가 섞인 통계표 코드는 400으로 막고 호출하지 않는다`() {
        val thrown =
            assertThrows(ResponseStatusException::class.java) { controller.tables("721Y001/../StatisticSearch") }

        assertThat(thrown.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        verify(statList, never()).tables(any())
    }

    /**
     * 항목 목록도 같은 검사를 받아야 한다. `{`를 막는 것도 여기 포함이다 —
     * Spring의 URI 템플릿 확장에서 터져 나가면 오타가 "IO"(연결 실패)로 보고되는데,
     * 그 둘을 갈라 주려고 만든 도구가 그러면 곤란하다.
     */
    @Test
    fun `템플릿 문자가 섞인 항목 코드는 400으로 막고 호출하지 않는다`() {
        val thrown = assertThrows(ResponseStatusException::class.java) { controller.items("901Y009{x}") }

        assertThat(thrown.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        verify(statList, never()).items(any() ?: "")
    }

    /** 검사가 실제 코드까지 막으면 도구가 죽는다. 실측한 형태(영숫자)는 그대로 통과해야 한다 */
    @Test
    fun `정상 코드는 그대로 클라이언트로 넘어간다`() {
        `when`(statList.items(any() ?: "")).thenReturn("{}")

        controller.items("721Y001")

        verify(statList).items("721Y001")
    }

    /**
     * 긴 구간을 한 번에 받으면 안 된다 — [RateCollectService]의 KDoc이 말하는 대로 기존 행마다
     * `em.merge`가 SELECT를 하나씩 내므로 6.5년치는 순차 왕복 만 회다. 주석으로만 적어 두면
     * 안 지켜진다(이 KDoc 안에서 이미 두 번 어긋났다). 서비스에 닿기 전에 거절한다.
     */
    @Test
    fun `2년을 넘는 구간은 400으로 막고 수집하지 않는다`() {
        val thrown = assertThrows(ResponseStatusException::class.java) {
            controller.collect(LocalDate.of(2020, 1, 1), LocalDate.of(2026, 8, 13))
        }

        assertThat(thrown.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(thrown.reason).contains("끊어 호출")
        verify(service, never()).collect(
            any(LocalDate::class.java) ?: LocalDate.EPOCH,
            any(LocalDate::class.java) ?: LocalDate.EPOCH,
            any(LocalDateTime::class.java) ?: LocalDateTime.MIN,
        )
    }

    /** 안내한 2년 단위(`?from=2020-01-01&to=2021-12-31`)는 통과해야 한다 — 막으면 백필 자체가 불가능해진다 */
    @Test
    fun `2년 구간은 통과한다`() {
        stub(summary(requested = 6, collected = 6))

        val response = controller.collect(LocalDate.of(2020, 1, 1), LocalDate.of(2021, 12, 31))

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
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
