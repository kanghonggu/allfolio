package com.allfolio.api.admin

import com.allfolio.market.commodity.CommodityCollectService
import com.allfolio.market.commodity.CommodityCollectSummary
import com.allfolio.market.commodity.CommodityProperties
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import java.time.temporal.ChronoUnit

class CommodityAdminControllerTest {

    private val service: CommodityCollectService = mock(CommodityCollectService::class.java)
    private val properties = CommodityProperties().apply {
        fredDaily = listOf(item("WTI", "DCOILWTICO", "USD/bbl", "D"))
        fredMonthly = listOf(item("COPPER", "PCOPPUSDM", "USD/MT", "M"))
    }
    private val controller = CommodityAdminController(service, properties)

    /**
     * `requested == 0`은 설정이 빈 것이지 상류 장애가 아니다. 502로 내면 운영자가 멀쩡한
     * FRED를 확인하러 가는데 진짜 문제는 `application.yml`이다 — 문구가 그리로 보내야 한다.
     */
    @Test
    fun `수집 대상이 없으면 500이다`() {
        stub(summary(requested = 0, collected = 0))

        val thrown = assertThrows(ResponseStatusException::class.java) { controller.collect(null, null) }

        assertThat(thrown.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        // **목록 셋 다 이름을 대는지까지 본다.** 하나만 대면 다른 목록이 빈 경우에
        // 운영자가 멀쩡한 목록만 들여다본다 — 환경변수는 리스트를 통째로 교체하므로
        // "한 목록만 사라진" 상태가 실제로 생긴다
        assertThat(thrown.reason).contains(
            "market-commodity.fred-daily",
            "market-commodity.fred-monthly",
            "market-commodity.fsc",
        )
    }

    /**
     * 요청은 있었는데 한 건도 못 건진 경우다. 200으로 내보내면 크론 잡이 초록으로 끝나고
     * 원자재가 끊긴 걸 아무도 모른다. 502인 이유는 상류(또는 DB) 문제이기 때문이다 —
     * 배포 직후 가장 흔한 원인은 마이그레이션 미적용이다.
     */
    @Test
    fun `전멸은 502다`() {
        stub(
            summary(
                requested = 16,
                collected = 0,
                failed = 16,
                failures = listOf("WTI: relation \"market_commodity_quote\" does not exist"),
            ),
        )

        val thrown = assertThrows(ResponseStatusException::class.java) { controller.collect(null, null) }

        assertThat(thrown.statusCode).isEqualTo(HttpStatus.BAD_GATEWAY)
        // 사유가 없으면 "원자재 수집 실패"만 보고 다시 로그를 뒤져야 한다
        assertThat(thrown.reason).contains("한 건도").contains("market_commodity_quote")
        // 전량 실패와 전 종목 0건은 운영자를 다른 곳으로 보낸다 — 문구가 갈려야 한다
        assertThat(thrown.reason).contains("전량 실패")
    }

    /**
     * **실패가 하나도 없이 전 종목이 0건인 실행이 정확히 "시리즈 ID가 전부 틀렸다"의 모양이다.**
     * FRED는 없는 시리즈에 오류를 주지만 파싱 단계에서 0건이 되는 경로도 있고, 그걸 200으로
     * 내보내면 잡이 영원히 초록으로 끝난다.
     *
     * **502가 아니라 500이다.** 상류는 정상 응답을 줬고 틀린 건 우리가 넣은 시리즈 ID다.
     * 창이 주기에 맞춰져 있어(일간 14일 · 월간 400일) "전부 정상적으로 비었다"는 상태는
     * 존재하지 않는다 — 살아 있는 계열이라면 어느 층이든 값을 준다.
     */
    @Test
    fun `실패가 없어도 전 종목이 0건이면 500이다`() {
        stub(summary(requested = 2, collected = 0).copy(emptySeries = listOf("WTI", "COPPER")))

        val thrown = assertThrows(ResponseStatusException::class.java) { controller.collect(null, null) }

        assertThat(thrown.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        // 상류를 확인하러 보내면 안 된다 — 할 일은 설정 확인이다
        assertThat(thrown.reason).contains("전 종목 0건").contains("시리즈 ID를 확인")
        assertThat(thrown.reason).doesNotContain("전량 실패")
    }

    /**
     * 저장이 0건이어도 **일부만 비었으면 장애가 아니다** — 계열이 중단됐거나 새로 편입돼 아직
     * 값이 없을 수 있다. 이 상태는 emptySeries가 설명한다.
     *
     * **"월간은 새 관측이 없는 달이 있어서 빈다"는 이유를 여기 적지 말 것.** 월간 창이 400일이라
     * 살아 있는 계열은 관측 13건을 준다 — 그 문장은 창을 짧게 잡았을 때만 참이고, 지금 적어 두면
     * 아래 `전 종목이 0건이면 500` 판정과 정면으로 어긋난다.
     */
    @Test
    fun `일부만 비었으면 0건이어도 200이다`() {
        stub(summary(requested = 16, collected = 0).copy(emptySeries = listOf("COPPER")))

        val response = controller.collect(null, null)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.emptySeries).containsExactly("COPPER")
    }

    /** 부분 실패까지 빨갛게 칠하면 매일 빨간 잡을 보게 되고, 그러면 진짜 전멸도 안 보인다 */
    @Test
    fun `부분 실패는 200이다`() {
        stub(summary(requested = 16, collected = 200, failed = 1, failures = listOf("SUGAR: HTTP 500")))

        val response = controller.collect(null, null)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.failed).isEqualTo(1)
    }

    /**
     * 끝점은 **KST 오늘**이고, 시작점은 여기서 정하지 않는다.
     *
     * **`LocalDate.now()`(시스템 기본 시간대)로 바꾸는 변이를 잡으려면 기본 시간대를 옮겨야 한다** —
     * 개발 머신이 KST라 그냥 두면 두 값이 같아서 변이가 초록으로 통과한다. 운영 컨테이너는 UTC라
     * 거기서만 새벽에 하루가 밀리고, 그 날 공표된 값이 통째로 빠진 채 잡은 초록으로 끝난다.
     * (`SchedulerTriggerControllerTest`의 마감 트리거 테스트가 같은 함정을 같은 방식으로 다룬다.)
     *
     * **`from`이 null 그대로 내려가는지도 함께 못 박는다.** 여기서 시작일을 계산해 넣으면 창이
     * 하나로 뭉개져 일간과 월간 중 한쪽이 반드시 틀린다 — 주기별 창은 서비스가 정한다.
     * 그 길이는 `CommodityCollectServiceTest`가 잰다.
     */
    @Test
    fun `날짜를 안 주면 끝점은 KST 오늘이고 시작점은 서비스에 맡긴다`() {
        stub(summary(requested = 16, collected = 200))
        val kstToday = LocalDate.now(KST)

        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zoneWhereTodayDiffersFromKst()))
        try {
            // 옮긴 시간대가 정말 다른 날짜인지 먼저 확인한다. 이 가드가 없으면 아래 헬퍼가
            // 틀렸을 때 테스트가 아무것도 안 보면서 초록으로 남는다.
            assertThat(LocalDate.now()).isNotEqualTo(kstToday)

            controller.collect(null, null)
        } finally {
            TimeZone.setDefault(original)
        }

        val from = ArgumentCaptor.forClass(LocalDate::class.java)
        val to = ArgumentCaptor.forClass(LocalDate::class.java)
        verify(service).collect(
            from.capture(),
            to.capture() ?: LocalDate.EPOCH,
            any(LocalDateTime::class.java) ?: LocalDateTime.MIN,
        )

        assertThat(to.value)
            .describedAs("끝점은 KST 오늘이어야 한다. LocalDate.now()를 쓰면 UTC 컨테이너에서 하루 밀린다")
            .isEqualTo(kstToday)
        assertThat(from.value)
            .describedAs("시작일을 여기서 계산하면 주기별 창이 하나로 뭉개진다")
            .isNull()
    }

    /** 주어진 구간은 그대로 내려가야 한다 — 초기 백필이 이 경로를 쓴다 */
    @Test
    fun `날짜를 주면 그대로 넘긴다`() {
        stub(summary(requested = 16, collected = 200))

        controller.collect(LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31))

        val from = ArgumentCaptor.forClass(LocalDate::class.java)
        val to = ArgumentCaptor.forClass(LocalDate::class.java)
        verify(service).collect(
            from.capture(),
            to.capture() ?: LocalDate.EPOCH,
            any(LocalDateTime::class.java) ?: LocalDateTime.MIN,
        )

        assertThat(from.value).isEqualTo(LocalDate.of(2020, 1, 1))
        assertThat(to.value).isEqualTo(LocalDate.of(2020, 12, 31))
    }

    /**
     * 긴 구간을 한 번에 받으면 안 된다 — 기존 행마다 `em.merge`가 SELECT를 하나씩 내므로
     * 다년 구간 한 방이면 무료 플랜 Neon 커넥션을 오래 쥔다. 서비스에 닿기 전에 거절한다.
     */
    @Test
    fun `2년을 넘는 구간은 400으로 막고 수집하지 않는다`() {
        val thrown = assertThrows(ResponseStatusException::class.java) {
            controller.collect(LocalDate.of(2020, 1, 1), LocalDate.of(2026, 8, 16))
        }

        assertThat(thrown.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(thrown.reason).contains("끊어 호출")
        verify(service, never()).collect(
            any(),
            any(LocalDate::class.java) ?: LocalDate.EPOCH,
            any(LocalDateTime::class.java) ?: LocalDateTime.MIN,
        )
    }

    /** 안내한 2년 단위는 통과해야 한다 — 막으면 백필 자체가 불가능해진다 */
    @Test
    fun `2년 구간은 통과한다`() {
        stub(summary(requested = 16, collected = 200))

        val response = controller.collect(LocalDate.of(2020, 1, 1), LocalDate.of(2021, 12, 31))

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    }

    /**
     * 진단 조회는 상류를 부르지 않고 설정만 보여준다. **어느 목록에서 왔는지(group)까지 실어야**
     * 환경변수가 목록 하나를 통째로 교체한 상태를 알아볼 수 있다 — 스프링은 리스트를 병합하지 않는다.
     */
    @Test
    fun `설정 조회는 목록 이름과 함께 전 종목을 돌려준다`() {
        val body = controller.config().body!!

        // **하드코딩하지 않는다.** 목록이 하나 늘었을 때 config()가 그걸 빠뜨리면
        // 여기서 잡혀야 한다 — total을 2로 박아 두면 정확히 그 실패를 못 본다
        assertThat(body.total).isEqualTo(properties.allItems.size)
        // 항목 집합은 allItems가 기준이다 — 이름표 표는 group 라벨만 붙인다
        assertThat(body.items.map { it.code })
            .containsExactlyElementsOf(properties.allItems.map { it.code })
        assertThat(body.items.map { it.group to it.code })
            .containsExactly("fredDaily" to "WTI", "fredMonthly" to "COPPER")
        assertThat(body.items.map { it.group }).doesNotContain(CommodityAdminController.UNKNOWN_GROUP)
        // 단위가 빠지면 화면이 USc/lb와 USD/lb(100배 차이)를 대조할 방법이 없다
        assertThat(body.items.map { it.unit }).containsExactly("USD/bbl", "USD/MT")
        assertThat(body.items.map { it.frequency }).containsExactly("D", "M")
        verifyNoInteractionsWithCollector()
    }

    // ── 도우미 ────────────────────────────────────────────────────────────────

    private fun verifyNoInteractionsWithCollector() = verify(service, never()).collect(
        any(),
        any(LocalDate::class.java) ?: LocalDate.EPOCH,
        any(LocalDateTime::class.java) ?: LocalDateTime.MIN,
    )

    private fun stub(summary: CommodityCollectSummary) {
        // **첫 인자는 맨 any()다.** `from`은 null일 수 있고, any(LocalDate::class.java)는
        // Mockito 2.1부터 null을 매칭하지 않아 기본 창 경로가 스텁에 안 걸린다
        `when`(
            service.collect(
                any(),
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
    ) = CommodityCollectSummary(
        from = LocalDate.of(2026, 5, 18),
        to = LocalDate.of(2026, 8, 16),
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

    private fun item(code: String, seriesId: String, unit: String, frequency: String) =
        CommodityProperties.CommodityItem().apply {
            this.code = code
            this.seriesId = seriesId
            this.unit = unit
            this.frequency = frequency
        }

    /**
     * 지금 이 순간 KST와 날짜가 다른 지역. 하루 중 어느 시각에 돌려도 성립해야 한다.
     *
     * Niue(UTC-11)는 KST보다 20시간 늦어 KST 20시 전에는 항상 어제다.
     * Kiritimati(UTC+14)는 5시간 빨라 KST 19시 이후에는 항상 내일이다.
     * 20시를 경계로 갈라 쓰면 두 구간이 하루를 빈틈없이 덮는다.
     * (`SchedulerTriggerControllerTest`가 같은 헬퍼를 갖는다 — 두 파일이 각자 독립으로
     *  기본 시간대를 옮기므로 공용화하지 않는다.)
     */
    private fun zoneWhereTodayDiffersFromKst(): ZoneId =
        if (Instant.now().atZone(KST).hour >= 20) {
            ZoneId.of("Pacific/Kiritimati")
        } else {
            ZoneId.of("Pacific/Niue")
        }

    private companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
