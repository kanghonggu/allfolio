package com.allfolio.api.admin

import com.allfolio.config.GlobalExceptionHandler
import com.allfolio.market.index.DomesticIndexCollectSummary
import com.allfolio.market.index.IndexCollectService
import com.allfolio.market.index.IndexSlot
import com.allfolio.market.index.KisIndexClient
import com.allfolio.market.index.OverseasIndexCollectService
import com.allfolio.market.index.OverseasIndexCollectSummary
import com.allfolio.market.index.OverseasSchedule
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.abs

class MarketIndexAdminControllerTest {

    private val collectService: IndexCollectService = mock(IndexCollectService::class.java)
    private val overseasCollectService: OverseasIndexCollectService =
        mock(OverseasIndexCollectService::class.java)
    private val controller = MarketIndexAdminController(
        mock(KisIndexClient::class.java),
        collectService,
        overseasCollectService,
    )

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

    // ── 해외 지수 (AF-110) ─────────────────────────────────────────────────────
    //
    // 국내 쪽 다섯 케이스(시각 주입 / 슬롯 전달 / 502 전멸 / 200 부분실패 / 500 대상없음)를
    // 그대로 짝지어 둔다. 이 저장소에서 "코드는 복사되고 그 코드를 지키던 테스트는 안 따라온다"가
    // 이미 세 번 있었다. 해외에만 있는 것은 맨 아래 400(스케줄 오타)이다.

    private val overseasSummary = OverseasIndexCollectSummary(
        schedule = "US",
        requested = 6,
        collected = 6,
        inserted = 6,
        updated = 0,
        failed = 0,
        failures = emptyList(),
        names = mapOf("SPX" to "S&P500 지수(SPX)"),
    )

    private fun stubOverseas(result: OverseasIndexCollectSummary) {
        `when`(
            overseasCollectService.collect(
                any(String::class.java) ?: "",
                any(Instant::class.java) ?: Instant.EPOCH,
            )
        ).thenReturn(result)
    }

    /**
     * 국내의 "UTC 현재 시각을 넘긴다"에 대응한다. 해외는 [Instant]라 타임존 해석의 모호함이
     * 애초에 없지만, **컨트롤러가 시각을 넣는다는 것 자체**는 여기서만 고정된다.
     * 서비스가 `Instant.now()`를 스스로 부르게 "정리"하면 테스트에서 시각을 고정할 수 없게 되고,
     * 그 순간 시장상태 판정을 검증하던 서비스 테스트들이 시계에 의존하게 된다.
     */
    @Test
    fun `해외 수집 서비스에 현재 시각을 넘긴다`() {
        stubOverseas(overseasSummary)

        val before = Instant.now()
        controller.collectOverseas(OverseasSchedule.US)

        val captor = ArgumentCaptor.forClass(Instant::class.java)
        verify(overseasCollectService).collect(
            any(String::class.java) ?: "",
            captor.capture() ?: Instant.EPOCH,
        )
        assertThat(abs(Duration.between(before, captor.value).seconds)).isLessThan(60)
    }

    /**
     * `schedule.name`이 그대로 내려가야 한다. `"US"`를 하드코딩하면 아시아 cron이 미국 지수를
     * 수집하는데, 그 시각(08:30 UTC) 미국은 장중이라 진행 중인 봉이 종가로 저장되고
     * **아시아 3종은 영영 비어 있는다.** 저장된 값이 그럴듯해서 눈으로는 못 잡는다.
     * 그래서 US가 아니라 ASIA로 못 박는다 — US로 검증하면 하드코딩이 그대로 통과한다.
     */
    @Test
    fun `요청한 스케줄 이름을 그대로 넘긴다`() {
        stubOverseas(overseasSummary.copy(schedule = "ASIA", requested = 3, collected = 3, inserted = 3))

        controller.collectOverseas(OverseasSchedule.ASIA)

        val scheduleCaptor = ArgumentCaptor.forClass(String::class.java)
        verify(overseasCollectService).collect(
            scheduleCaptor.capture() ?: "",
            any(Instant::class.java) ?: Instant.EPOCH,
        )
        assertThat(scheduleCaptor.value).isEqualTo("ASIA")
    }

    @Test
    fun `해외 지수가 전부 실패하면 502를 낸다`() {
        stubOverseas(
            overseasSummary.copy(
                requested = 3,
                collected = 0,
                inserted = 0,
                updated = 0,
                failed = 3,
                schedule = "ASIA",
                failures = listOf(
                    "NIKKEI225: KIS 응답에 output2가 없습니다",
                    "HANGSENG: timeout",
                    "SHANGHAI: timeout",
                ),
            )
        )

        val thrown = assertThrows(ResponseStatusException::class.java) {
            controller.collectOverseas(OverseasSchedule.ASIA)
        }

        assertThat(thrown.statusCode).isEqualTo(HttpStatus.BAD_GATEWAY)
        // 잡 요약에 남는 문구다. 스케줄과 사유가 없으면 "해외 지수 수집 실패"만 보고
        // 어느 시장군이 왜 비었는지 로그를 다시 뒤져야 한다.
        assertThat(thrown.reason).contains("ASIA").contains("KIS 응답에 output2가 없습니다")
    }

    /**
     * 부분 실패는 200이다(국내와 같은 이유). 해외에서 더 중요한 건 **`names`가 그대로 실려야
     * 한다**는 것이다 — 9종 중 여섯은 KIS 마스터에서 읽었을 뿐이라, 배포 후 코드가 맞는지
     * 확인하는 유일한 수단이 요약의 이 맵이다. 실패한 지수의 이름도 실린다.
     */
    @Test
    fun `해외 지수 일부만 실패하면 200으로 요약을 돌려준다`() {
        stubOverseas(
            overseasSummary.copy(
                requested = 3,
                collected = 2,
                inserted = 2,
                updated = 0,
                failed = 1,
                schedule = "ASIA",
                failures = listOf("SHANGHAI: KIS가 돌려준 이름이 설정과 다릅니다"),
                names = mapOf(
                    "NIKKEI225" to "니케이225(JP#NI225)",
                    "HANGSENG" to "항셍지수(HK#HS)",
                    "SHANGHAI" to "상해A지수(CH#SHA)",
                ),
            )
        )

        val response = controller.collectOverseas(OverseasSchedule.ASIA)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.failures)
            .containsExactly("SHANGHAI: KIS가 돌려준 이름이 설정과 다릅니다")
        assertThat(response.body?.names)
            .describedAs("names가 응답에서 사라지면 배포 후 9종 코드 대조 수단이 없어진다")
            .containsEntry("HANGSENG", "항셍지수(HK#HS)")
            .containsEntry("SHANGHAI", "상해A지수(CH#SHA)")
    }

    // 문구가 domestic을 가리키면(국내 코드를 복사한 흔한 결과) 운영자가 아무 관계 없는
    // 설정 블록을 들여다본다. 문자열까지 못 박는 이유다.
    @Test
    fun `해외 수집 대상이 없으면 500을 낸다`() {
        stubOverseas(
            overseasSummary.copy(
                requested = 0,
                collected = 0,
                inserted = 0,
                updated = 0,
                failed = 0,
                failures = emptyList(),
                names = emptyMap(),
            )
        )

        val thrown = assertThrows(ResponseStatusException::class.java) {
            controller.collectOverseas(OverseasSchedule.US)
        }

        assertThat(thrown.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        assertThat(thrown.reason).contains("market-index.overseas")
        assertThat(thrown.reason).doesNotContain("market-index.domestic")
    }

    /**
     * **이 테스트가 `schedule`을 enum으로 받은 이유를 고정한다.**
     *
     * `String`으로 받으면 `Us`는 설정 대조에서 0건이 되어 `requested == 0` → 500이 나가는데,
     * 그 문구는 운영자를 `application.yml`로 보낸다. yml은 멀쩡하고 진짜 원인은 URL이다.
     * enum이면 Spring이 변환 단계에서 400을 내고 수집은 시작조차 하지 않는다.
     * 파라미터 타입을 String으로 "단순화"하면 여기서 깨진다.
     */
    @Test
    fun `스케줄 값에 오타가 있으면 400이고 수집을 부르지 않는다`() {
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
            .perform(post("/api/admin/market-index/collect-overseas").param("schedule", "Us"))
            .andExpect(status().isBadRequest)

        verify(overseasCollectService, never()).collect(
            any(String::class.java) ?: "",
            any(Instant::class.java) ?: Instant.EPOCH,
        )
    }

    // 기본값을 붙이면 이 테스트가 무너진다. 워크플로가 schedule을 못 실어 보낸 실행에서
    // 기본값은 "한쪽 시장군을 조용히 통째로 건너뛰기"이고 400은 "빨간 잡"이다.
    @Test
    fun `스케줄이 없으면 400이고 수집을 부르지 않는다`() {
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
            .perform(post("/api/admin/market-index/collect-overseas"))
            .andExpect(status().isBadRequest)

        verify(overseasCollectService, never()).collect(
            any(String::class.java) ?: "",
            any(Instant::class.java) ?: Instant.EPOCH,
        )
    }
}
