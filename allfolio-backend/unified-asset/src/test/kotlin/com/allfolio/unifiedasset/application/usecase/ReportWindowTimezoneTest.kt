package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.BenchmarkDailyStore
import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.application.port.GoalRepository
import com.allfolio.unifiedasset.domain.benchmark.BenchmarkType
import com.allfolio.unifiedasset.domain.goal.Goal
import com.allfolio.unifiedasset.domain.goal.GoalCategory
import com.allfolio.unifiedasset.infrastructure.jpa.UserAiConfigJpaRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.UUID
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.isAccessible

/**
 * 조회 구간(window)은 **KST 달력으로 잘라야 한다.**
 *
 * Render 컨테이너에는 TZ 설정이 없어 벽시계가 UTC다. 맨 [LocalDate.now]는 그 벽시계를 읽으므로
 * KST 00:00~09:00 사이에는 **어제**로 해석된다. 그 아홉 시간 동안 "YTD"는 하루 짧아지고,
 * 365일 창은 하루 밀리고, D-day는 하루 더 남았다고 말하고, LLM 프롬프트에는 어제 날짜가 박힌다.
 * 사용자가 자는 시간이 아니다 — 한국의 출근 전 시간대 전체다.
 *
 * [ReportGeneratedAtOffsetTest]가 고친 `generatedAt`과 뿌리가 같은 결함이지만 **고치는 방법이 다르다.**
 * `generatedAt`은 "어느 순간인가"라서 전선에 오프셋을 실어야 했고(타입 교체), 여기는 "어느 날짜 구간인가"라서
 * 자를 달력만 정하면 된다 — 값은 그대로 [LocalDate]로 남고 `now(KST)`로 존만 못박는다.
 *
 * ## 왜 UTC로 고정하지 않는가
 * UTC는 KST와 아홉 시간 차이라 **KST 09:00~24:00에는 날짜가 같다.** 그 시간대에 돌리면 테스트가
 * 버그를 못 잡고도 조용히 초록이 된다. 그래서 기본 타임존을 *지금* KST보다 하루 뒤가 되도록 계산해
 * 박고([oneDayBehindKst]), 재현이 실제로 걸렸는지 하네스가 스스로 확인한다([onTheDayBeforeKst]).
 */
class ReportWindowTimezoneTest {

    private val userId: UUID = UUID.randomUUID()

    /**
     * KST 시각 h시에 대해 현지 날짜가 **항상 하루 전**이 되는 오프셋을 고른다.
     *
     * 실재하는 존을 후보 목록에서 고르는 방법은 여기서 안 통한다. UTC는 KST와 아홉 시간 차이라
     * KST 09:00~24:00에 날짜가 같고, 실재 존의 하한은 -12라 KST 21시 이후엔 **뒤진 존이 전부
     * KST와 같은 날짜가 된다.** 그 구간에서 UTC+14 같은 앞선 존으로 넘어가면 날짜가 갈리긴 하지만
     * 방향이 뒤집혀 실제 결함(하루 뒤로 밀림)과 반대를 재현하게 된다. 합성 오프셋은 그 구멍이 없다.
     *
     * 경계 여유도 같이 챙긴다 — 현지 시각을 전날 12:00~20:00에 놓으므로 테스트가 도는 중에 시(hour)
     * 경계를 넘어도 재현이 안 깨진다. (`ZoneOffset` 하한이 -18이라 KST 16시 이후엔 정오에서 밀린다.)
     */
    private fun oneDayBehindKst(): ZoneOffset =
        ZoneOffset.ofHours(maxOf(-18, -3 - LocalDateTime.now(KST).hour))

    /** Render 컨테이너 재현: 기본 타임존의 날짜가 KST보다 하루 뒤다. */
    private fun <T> onTheDayBeforeKst(block: () -> T): T {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(oneDayBehindKst()))
        try {
            // 하네스가 실제로 컨테이너 조건을 만들었는지 먼저 확인한다. 이게 없으면 재현이 조용히
            // 실패했을 때 "구현이 맞다"로 읽힌다 — 통과했는데 아무것도 검사 안 한 초록이 된다.
            assertThat(LocalDate.now())
                .describedAs("하네스 재현 실패 — 기본 타임존 날짜가 KST 전날이어야 한다")
                .isEqualTo(LocalDate.now(KST).minusDays(1))
            return block()
        } finally {
            TimeZone.setDefault(original)
        }
    }

    /**
     * 쿼리에 실제로 실려 나간 인자를 붙잡는다. Mockito로 vararg를 캡처하는 것보다 읽기 쉽고,
     * 무엇보다 **구간 경계는 SQL 파라미터로만 관찰된다** — 리포트 DTO에는 안 실린다.
     */
    private class RecordingJdbc : JdbcTemplate() {
        val captured = mutableListOf<Any?>()
        override fun <T : Any?> query(sql: String, rowMapper: RowMapper<T>, vararg args: Any?): List<T> {
            captured.addAll(args)
            return emptyList()
        }
    }

    /** 조회 상한(`to`)을 붙잡는다. Mockito 매처는 코틀린 non-null 파라미터에 null을 넘겨 터진다. */
    private class RecordingBenchmarkStore : BenchmarkDailyStore {
        val upperBounds = mutableListOf<LocalDate>()
        override fun latestDate(type: BenchmarkType): LocalDate? = null
        override fun upsert(type: BenchmarkType, rows: List<Pair<LocalDate, BigDecimal>>) = Unit
        override fun series(type: BenchmarkType, from: LocalDate, to: LocalDate): List<Pair<LocalDate, BigDecimal>> {
            upperBounds += to
            return emptyList()
        }
    }

    private class StubGoalRepository(private val goals: List<Goal>) : GoalRepository {
        override fun save(goal: Goal): Goal = goal
        override fun findById(id: UUID): Goal? = goals.firstOrNull { it.id == id }
        override fun findByUserId(userId: UUID): List<Goal> = goals
        override fun delete(id: UUID) = Unit
    }

    private fun reportService(jdbc: JdbcTemplate, benchmarkStore: BenchmarkDailyStore) = ReportService(
        assetRepository = mock(AssetRepository::class.java),
        accountRepository = mock(AccountRepository::class.java),
        jdbc = jdbc,
        fx = mock(FxConverter::class.java),
        benchmarkStore = benchmarkStore,
        cashFlowRepository = mock(CashFlowRepository::class.java),
        dustThresholdKrw = BigDecimal(1000),
    )

    // ── ReportService ────────────────────────────────────────────────────────

    @Test
    fun `벤치마크 조회 상한은 KST 오늘이다`() {
        val store = RecordingBenchmarkStore()
        onTheDayBeforeKst { reportService(RecordingJdbc(), store).benchmark(userId, "1M") }

        assertThat(store.upperBounds).isNotEmpty()
        assertThat(store.upperBounds.distinct())
            .describedAs("컨테이너 벽시계가 아니라 KST 달력으로 잘라야 한다")
            .containsExactly(LocalDate.now(KST))
    }

    @Test
    fun `순자산 추이 365일 창의 시작은 KST 오늘 기준이다`() {
        val jdbc = RecordingJdbc()
        onTheDayBeforeKst { reportService(jdbc, mock(BenchmarkDailyStore::class.java)).networth(userId) }

        assertThat(jdbc.captured.filterIsInstance<LocalDate>())
            .describedAs("365일 창이 컨테이너 날짜를 따라가면 하루씩 밀린다")
            .containsExactly(LocalDate.now(KST).minusDays(365))
    }

    @Test
    fun `1개월 성과 조회 구간의 시작은 KST 오늘 기준 30일 전이다`() {
        val jdbc = RecordingJdbc()
        onTheDayBeforeKst {
            reportService(jdbc, mock(BenchmarkDailyStore::class.java)).performance(userId, "1M")
        }

        assertThat(jdbc.captured.filterIsInstance<LocalDate>())
            .describedAs("고정 일수 구간은 오늘이 밀리면 통째로 밀린다")
            .contains(LocalDate.now(KST).minusDays(30))
    }

    /**
     * **YTD의 시작일은 두 오류가 상쇄돼 멀쩡해 보인다.** 컨테이너가 하루 뒤지면 `dayOfYear`도 하루
     * 작아져서 `오늘 - 경과일수`가 양쪽 다 작년 12/31로 떨어진다(실측). 그래서 시작일을 아무리 단언해도
     * 이 결함은 안 잡힌다 — 틀린 건 **경과일수 그 자체**이고, 그건 구간 길이·일할 계산에 그대로 쓰인다.
     */
    @Test
    fun `YTD 경과일수는 KST 달력으로 센다`() {
        val periodDays = ReportService::class.declaredMemberFunctions
            .first { it.name == "periodDays" }
            .apply { isAccessible = true }
        val service = reportService(RecordingJdbc(), mock(BenchmarkDailyStore::class.java))

        val days = onTheDayBeforeKst { periodDays.call(service, "YTD") as Int }

        assertThat(days)
            .describedAs("컨테이너 달력으로 세면 올해 경과일수가 하루 짧다")
            .isEqualTo(LocalDate.now(KST).dayOfYear)
    }

    // ── DividendReportService ────────────────────────────────────────────────

    @Test
    fun `배당 1Y 구간의 시작은 KST 오늘 기준 1년 전이다`() {
        val jdbc = RecordingJdbc()
        onTheDayBeforeKst { DividendReportService(jdbc).report(userId, "1Y") }

        assertThat(jdbc.captured.filterIsInstance<LocalDate>())
            .describedAs("구간 시작과 연환산 창이 전부 KST 달력이어야 한다")
            .isNotEmpty()
            .allSatisfy { assertThat(it).isEqualTo(LocalDate.now(KST).minusYears(1)) }
    }

    /**
     * **오늘은 이 단언이 실패할 수 없다** — 연도는 KST 1/1 00:00~09:00에만 갈리기 때문이다.
     * 그래서 이건 변경을 이끈 테스트가 아니라 그 아홉 시간짜리 경계를 못박아 두는 회귀 가드다.
     * 그 창에 걸리면 YTD 배당이 통째로 작년 것이 되므로, 관측 불가라고 해서 사소한 결함은 아니다.
     */
    @Test
    fun `배당 YTD 구간의 시작은 KST 기준 올해 1월 1일이다`() {
        val periodStart = DividendReportService::class.declaredMemberFunctions
            .first { it.name == "periodStart" }
            .apply { isAccessible = true }
        val service = DividendReportService(RecordingJdbc())

        val start = onTheDayBeforeKst { periodStart.call(service, "YTD") as LocalDate }

        assertThat(start)
            .describedAs("연말연시에 컨테이너가 작년에 머물면 YTD가 통째로 작년이 된다")
            .isEqualTo(LocalDate.of(LocalDate.now(KST).year, 1, 1))
    }

    // ── AiConsultantService ──────────────────────────────────────────────────

    @Test
    fun `LLM 프롬프트에 박히는 오늘 날짜는 KST다`() {
        val service = AiConsultantService(
            configRepo = mock(UserAiConfigJpaRepository::class.java),
            jdbc = RecordingJdbc(),
            objectMapper = ObjectMapper(),
        )
        val buildSystemPrompt = AiConsultantService::class.declaredMemberFunctions
            .first { it.name == "buildSystemPrompt" }
            .apply { isAccessible = true }

        val prompt = onTheDayBeforeKst { buildSystemPrompt.call(service, userId) as String }

        assertThat(prompt)
            .describedAs("모델은 프롬프트에 적힌 날짜를 그대로 믿는다 — 틀린 날짜를 주면 틀린 근거로 답한다")
            .contains("오늘 날짜: ${LocalDate.now(KST)}")
        assertThat(prompt)
            .describedAs("데이터 기준 시각도 같은 달력이어야 한다")
            .contains("데이터 기준 시각: ${LocalDate.now(KST)}")
    }

    // ── GoalService ──────────────────────────────────────────────────────────

    @Test
    fun `목표 D-day는 KST 오늘부터 센다`() {
        val goal = Goal(
            id = UUID.randomUUID(),
            userId = userId,
            name = "내일이 만기",
            description = null,
            targetAmount = BigDecimal(1000),
            targetDate = LocalDate.now(KST).plusDays(1),
            category = GoalCategory.OTHER,
            createdAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now(),
        )
        val service = GoalService(
            goalRepository = StubGoalRepository(listOf(goal)),
            assetRepository = mock(AssetRepository::class.java),
            fx = mock(FxConverter::class.java),
        )

        val response = onTheDayBeforeKst { service.list(userId) }

        assertThat(response.goals.single().daysRemaining)
            .describedAs("컨테이너가 어제에 머물면 D-1을 D-2로 말한다")
            .isEqualTo(1L)
    }

    private companion object {
        val KST: ZoneId = ZoneId.of("Asia/Seoul")

    }
}
