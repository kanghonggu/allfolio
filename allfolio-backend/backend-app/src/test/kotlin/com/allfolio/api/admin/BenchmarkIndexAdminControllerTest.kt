package com.allfolio.api.admin

import com.allfolio.market.benchmark.BenchmarkCollectSummary
import com.allfolio.market.benchmark.BenchmarkIndexProperties
import com.allfolio.market.benchmark.FscIndexCollectService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.ArgumentMatchers.any
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

class BenchmarkIndexAdminControllerTest {

    private val service: FscIndexCollectService = mock(FscIndexCollectService::class.java)
    private val properties = BenchmarkIndexProperties().apply {
        fsc = listOf(item("KOSPI", "코스피", "KOSPI시리즈"))
    }
    private val controller = BenchmarkIndexAdminController(service, properties)

    /**
     * `requested == 0`은 설정이 빈 것이지 상류 장애가 아니다. 502로 내면 운영자가 멀쩡한
     * 공공데이터포털을 확인하러 가는데 진짜 문제는 `application.yml`이다.
     */
    @Test
    fun `수집 대상이 없으면 500이다`() {
        stub(summary(requested = 0, saved = 0))

        val thrown = assertThrows(ResponseStatusException::class.java) { controller.collect(null, null) }

        assertThat(thrown.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        // 고칠 자리를 정확히 대야 한다 — 목록 이름이 없으면 yml 전체를 뒤지게 된다
        assertThat(thrown.reason).contains("benchmark-index.fsc")
    }

    /**
     * 요청은 있었는데 한 건도 못 건진 경우다. 200으로 내보내면 크론 잡이 초록으로 끝나고
     * KOSPI가 끊긴 걸 아무도 모른다 — Yahoo가 더는 안 채우므로 그때 채우는 주체가 없다.
     */
    @Test
    fun `전멸은 502다`() {
        stub(
            summary(
                requested = 1,
                saved = 0,
                failed = 1,
                failures = listOf("KOSPI: FSC 응답 오류 (resultCode=22)"),
            ),
        )

        val thrown = assertThrows(ResponseStatusException::class.java) { controller.collect(null, null) }

        assertThat(thrown.statusCode).isEqualTo(HttpStatus.BAD_GATEWAY)
        assertThat(thrown.reason).contains("전량 실패").contains("resultCode=22")
    }

    /**
     * **실패가 하나도 없이 전 지수가 0건인 실행이 정확히 "(idxNm, idxCsf) 쌍이 틀렸다"의 모양이다.**
     * 포털은 없는 지수에 오류가 아니라 빈 items를 주므로, 200으로 내보내면 잡이 영원히 초록이다.
     *
     * **502가 아니라 500이다.** 상류는 정상 응답을 줬고 틀린 건 우리가 넣은 쌍이다.
     * 창이 14일이라 살아 있는 지수는 반드시 값을 준다 — "전부 정상적으로 비었다"는 상태는 없다.
     */
    @Test
    fun `실패가 없어도 전 지수가 0건이면 500이다`() {
        stub(summary(requested = 1, saved = 0).copy(emptySeries = listOf("KOSPI")))

        val thrown = assertThrows(ResponseStatusException::class.java) { controller.collect(null, null) }

        assertThat(thrown.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        // 상류를 확인하러 보내면 안 된다 — 할 일은 설정 확인이다
        assertThat(thrown.reason).contains("전 지수 0건").contains("idxCsf")
        assertThat(thrown.reason).doesNotContain("전량 실패")
    }

    /**
     * **원자재와 갈리는 유일한 판정이다.** `type`에 오타가 나면 `BenchmarkType.valueOf`가 터져
     * 전량 실패의 모양이 되는데, 지수가 KOSPI 하나뿐이라 그게 곧 502가 된다 —
     * 그러면 운영자가 멀쩡한 공공데이터포털 상태 페이지를 확인하러 간다.
     * 할 일은 `application.yml`을 고치는 것이므로 500이어야 한다.
     *
     * 마이그레이션을 언급하지 않는 것도 원자재와 다른 점이다 — `benchmark_daily`는 이미 있는 표다.
     */
    @Test
    fun `설정 type이 BenchmarkType에 없으면 502가 아니라 500이다`() {
        val controller = BenchmarkIndexAdminController(
            service,
            BenchmarkIndexProperties().apply { fsc = listOf(item("KOSPPI", "코스피", "KOSPI시리즈")) },
        )
        stub(
            summary(
                requested = 1,
                saved = 0,
                failed = 1,
                failures = listOf("KOSPPI: No enum constant com.allfolio.unifiedasset.domain.benchmark.BenchmarkType.KOSPPI"),
            ),
        )

        val thrown = assertThrows(ResponseStatusException::class.java) { controller.collect(null, null) }

        assertThat(thrown.statusCode).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        assertThat(thrown.reason).contains("KOSPPI").contains("benchmark-index.fsc")
        // 가능한 값을 대야 운영자가 무엇으로 고칠지 안다
        assertThat(thrown.reason).contains("KOSPI")
        assertThat(thrown.reason).doesNotContain("전량 실패")
    }

    /**
     * **위 500 판정의 경계다 — 오타가 *일부*일 때는 여전히 502여야 한다.**
     *
     * KOSPI는 상류 장애로, KOSPPI는 `type` 오타로 실패한 상황이다. 상류가 **실제로** 죽었으므로
     * 운영자는 포털을 봐야 하고(502), 오타는 `failures`에 같이 실려 나간다.
     * 500으로 뭉개면 진짜 상류 장애가 "설정 오타"로 보고되어 아무도 포털을 안 본다.
     *
     * **이 테스트가 없으면 `unknown.size == requested`를 `unknown.isNotEmpty()`로 바꾸는 변이가
     * 통과한다** — 다른 테스트들은 `unknown`이 전부(1/1)거나 없음(0/N)뿐이라 부분집합인 경우를
     * 한 번도 안 만든다. 이 500/502 갈림이 원자재 패턴에서 벗어난 유일한 지점이라 경계를 못 박는다.
     */
    @Test
    fun `일부만 type 오타이고 상류도 죽었으면 500이 아니라 502다`() {
        val controller = BenchmarkIndexAdminController(
            service,
            BenchmarkIndexProperties().apply {
                fsc = listOf(item("KOSPI", "코스피", "KOSPI시리즈"), item("KOSPPI", "코스피", "KOSPI시리즈"))
            },
        )
        stub(
            summary(
                requested = 2,
                saved = 0,
                failed = 2,
                failures = listOf(
                    "KOSPI: FSC 응답 오류 (resultCode=22)",
                    "KOSPPI: No enum constant com.allfolio.unifiedasset.domain.benchmark.BenchmarkType.KOSPPI",
                ),
            ),
        )

        val thrown = assertThrows(ResponseStatusException::class.java) { controller.collect(null, null) }

        assertThat(thrown.statusCode).isEqualTo(HttpStatus.BAD_GATEWAY)
        assertThat(thrown.reason).contains("전량 실패")
        // 오타도 같이 보여야 한다 — 502라고 해서 설정 문제를 숨기지는 않는다
        assertThat(thrown.reason).contains("KOSPPI").contains("resultCode=22")
    }

    /**
     * 저장이 0건이어도 **일부만 비었으면 장애가 아니다.** 지수가 늘었을 때 새로 넣은 쪽만
     * 비는 경우가 있고, 그건 `emptySeries`가 설명한다.
     */
    @Test
    fun `일부만 비었으면 0건이어도 200이다`() {
        stub(summary(requested = 2, saved = 0).copy(emptySeries = listOf("KOSPI")))

        val response = controller.collect(null, null)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.emptySeries).containsExactly("KOSPI")
    }

    /** 부분 실패까지 빨갛게 칠하면 매일 빨간 잡을 보게 되고, 그러면 진짜 전멸도 안 보인다 */
    @Test
    fun `부분 실패는 200이다`() {
        stub(summary(requested = 2, saved = 10, failed = 1, failures = listOf("KOSDAQ: HTTP 500")))

        val response = controller.collect(null, null)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.failed).isEqualTo(1)
    }

    /**
     * 날짜를 안 주면 **KST 오늘 기준 최근 14일**이다.
     *
     * **`LocalDate.now()`(시스템 기본 시간대)로 바꾸는 변이를 잡으려면 기본 시간대를 옮겨야 한다** —
     * 개발 머신이 KST라 그냥 두면 두 값이 같아서 변이가 초록으로 통과한다. 운영 컨테이너는 UTC라
     * 거기서만 새벽에 하루가 밀리고, 그 날 공표된 종가가 빠진 채 잡은 초록으로 끝난다.
     * (`CommodityAdminControllerTest`·`SchedulerTriggerControllerTest`가 같은 함정을 같은 방식으로 다룬다.)
     */
    @Test
    fun `날짜를 안 주면 KST 오늘 기준 최근 14일이다`() {
        stub(summary(requested = 1, saved = 10))
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
        verify(service).collect(from.capture() ?: LocalDate.EPOCH, to.capture() ?: LocalDate.EPOCH)

        assertThat(to.value)
            .describedAs("끝점은 KST 오늘이어야 한다. LocalDate.now()를 쓰면 UTC 컨테이너에서 하루 밀린다")
            .isEqualTo(kstToday)
        assertThat(from.value)
            .describedAs("시작점은 KST 오늘에서 14일 전이다 — 연휴가 껴도 영업일 5~6일이 들어온다")
            .isEqualTo(kstToday.minusDays(14))
    }

    /** 주어진 구간은 그대로 내려가야 한다 — 초기 백필이 이 경로를 쓴다 */
    @Test
    fun `날짜를 주면 그대로 넘긴다`() {
        stub(summary(requested = 1, saved = 240))

        controller.collect(LocalDate.of(2025, 8, 17), LocalDate.of(2026, 8, 17))

        val from = ArgumentCaptor.forClass(LocalDate::class.java)
        val to = ArgumentCaptor.forClass(LocalDate::class.java)
        verify(service).collect(from.capture() ?: LocalDate.EPOCH, to.capture() ?: LocalDate.EPOCH)

        assertThat(from.value).isEqualTo(LocalDate.of(2025, 8, 17))
        assertThat(to.value).isEqualTo(LocalDate.of(2026, 8, 17))
    }

    /**
     * **AF-107의 1회 백필이 1년이다.** 상한을 원자재보다 좁게 잡으면 그 백필 자체가 불가능해지고,
     * 그 사실은 배포 뒤 운영 호출에서야 400으로 드러난다.
     */
    @Test
    fun `1년 백필은 통과한다`() {
        stub(summary(requested = 1, saved = 240))

        val response = controller.collect(LocalDate.of(2025, 8, 17), LocalDate.of(2026, 8, 17))

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body?.saved).isEqualTo(240)
    }

    /** 안내한 2년 단위도 통과해야 한다 — 원자재·금리와 같은 상한을 쓰기로 한 결정이다 */
    @Test
    fun `2년 구간은 통과한다`() {
        stub(summary(requested = 1, saved = 480))

        val response = controller.collect(LocalDate.of(2020, 1, 1), LocalDate.of(2021, 12, 31))

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    }

    /**
     * 긴 구간을 한 번에 받으면 안 된다. 상한을 소스마다 다르게 두면 "백필은 몇 년씩 끊나"의
     * 답이 엔드포인트마다 갈리므로 원자재·금리와 같은 값을 쓴다.
     */
    @Test
    fun `2년을 넘는 구간은 400으로 막고 수집하지 않는다`() {
        val thrown = assertThrows(ResponseStatusException::class.java) {
            controller.collect(LocalDate.of(2020, 1, 1), LocalDate.of(2026, 8, 17))
        }

        assertThat(thrown.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(thrown.reason).contains("끊어 호출")
        verifyNoCollect()
    }

    /**
     * 진단 조회는 **상류를 부르지 않는다.** `knownType`을 싣는 이유는
     * `BenchmarkIndexProperties.validate()`가 일부러 도메인 enum을 안 보기 때문이다 —
     * `type` 오타는 기동을 막지 못하고 수집 시점에야 터지므로, 배포 직후 여기서 보여야 한다.
     */
    @Test
    fun `설정 조회는 쌍과 type 유효성까지 돌려준다`() {
        val controller = BenchmarkIndexAdminController(
            service,
            BenchmarkIndexProperties().apply {
                fsc = listOf(item("KOSPI", "코스피", "KOSPI시리즈"), item("KOSPPI", "코스피", "KOSPI시리즈"))
            },
        )

        val body = controller.config().body!!

        assertThat(body.total).isEqualTo(2)
        assertThat(body.items.map { it.type }).containsExactly("KOSPI", "KOSPPI")
        // 쌍이 둘 다 나가야 한다 — idxNm만 보면 "IT 서비스"류에서 어느 시리즈인지 알 수 없다
        assertThat(body.items.map { it.idxNm to it.idxCsf })
            .containsExactly("코스피" to "KOSPI시리즈", "코스피" to "KOSPI시리즈")
        assertThat(body.items.map { it.knownType }).containsExactly(true, false)
        verifyNoCollect()
    }

    // ── 도우미 ────────────────────────────────────────────────────────────────

    private fun verifyNoCollect() = verify(service, never()).collect(
        any(LocalDate::class.java) ?: LocalDate.EPOCH,
        any(LocalDate::class.java) ?: LocalDate.EPOCH,
    )

    private fun stub(summary: BenchmarkCollectSummary) {
        // any(...)는 매처를 등록하고 null을 돌려주는데, FscIndexCollectService는 Kotlin 파이널
        // 클래스라 원본 바이트코드의 non-null 파라미터 검사가 남아 NPE가 난다.
        // 엘비스로 아무 값이나 채우면 매처는 그대로 등록된 채 검사만 통과한다.
        `when`(
            service.collect(
                any(LocalDate::class.java) ?: LocalDate.EPOCH,
                any(LocalDate::class.java) ?: LocalDate.EPOCH,
            ),
        ).thenReturn(summary)
    }

    private fun summary(
        requested: Int,
        saved: Int,
        failed: Int = 0,
        failures: List<String> = emptyList(),
    ) = BenchmarkCollectSummary(
        from = LocalDate.of(2026, 8, 3),
        to = LocalDate.of(2026, 8, 17),
        requested = requested,
        saved = saved,
        outOfRange = 0,
        emptySeries = emptyList(),
        failed = failed,
        failures = failures,
    )

    private fun item(type: String, idxNm: String, idxCsf: String) =
        BenchmarkIndexProperties.BenchmarkIndexItem().apply {
            this.type = type
            this.idxNm = idxNm
            this.idxCsf = idxCsf
        }

    /**
     * 지금 이 순간 KST와 날짜가 다른 지역. 하루 중 어느 시각에 돌려도 성립해야 한다.
     *
     * Niue(UTC-11)는 KST보다 20시간 늦어 KST 20시 전에는 항상 어제다.
     * Kiritimati(UTC+14)는 5시간 빨라 KST 19시 이후에는 항상 내일이다.
     * 20시를 경계로 갈라 쓰면 두 구간이 하루를 빈틈없이 덮는다.
     * (`CommodityAdminControllerTest`가 같은 헬퍼를 갖는다 — 두 파일이 각자 독립으로
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
