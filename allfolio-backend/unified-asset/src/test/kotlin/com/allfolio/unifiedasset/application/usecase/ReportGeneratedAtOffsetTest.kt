package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.EsgReport
import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.BenchmarkDailyStore
import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.application.port.GoalRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.TimeZone
import java.util.UUID
import kotlin.reflect.full.memberProperties

/**
 * `generatedAt`은 **오프셋을 달고 나가야 한다.**
 *
 * 이 필드는 `LocalDateTime`이었다. Jackson은 `LocalDateTime`을 오프셋 없이 적고
 * (`"2026-08-15T11:49:00"`), 브라우저의 `new Date(...)`는 오프셋 없는 날짜·시각을
 * **읽는 쪽 로컬 시각**으로 해석한다. Render 컨테이너에 TZ 설정이 없어 벽시계가 UTC이므로
 * 한국 사용자는 20:49에 11:49를 봤다 — 새벽만이 아니라 하루 종일, 매 시각 9시간씩.
 *
 * 그래서 고친 건 값이 아니라 **전선 위의 타입**이다. `LocalDateTime.now(KST)`로 값만 옮기면
 * 한국 사용자에겐 맞지만 다른 시간대 사용자에겐 여전히 조용히 틀린다 — 전선이 오프셋을
 * 안 실으니 읽는 쪽이 여전히 추측한다. `OffsetDateTime`은 추측할 여지를 없앤다.
 *
 * **단언은 전부 직렬화된 문자열을 본다.** 브라우저가 실제로 손에 쥐는 게 그것이고,
 * DTO 필드를 직접 만지면 타입 교체 전에는 컴파일조차 안 돼 "실패를 지켜보는" 절차가 무너진다.
 * 구조 검사 하나가 11개 선언부를 못 박고, 타입이 바뀌면 `LocalDateTime.now()` 호출부는
 * 컴파일이 안 되므로 값을 만드는 쪽은 컴파일러가 지킨다.
 */
class ReportGeneratedAtOffsetTest {

    private val userId: UUID = UUID.randomUUID()

    /**
     * 운영 웹 계층이 쓰는 매퍼를 그대로 재현한다. `application.yml`에 `spring.jackson` 설정이
     * 없으므로 운영은 Boot 기본값 — 잘 알려진 모듈 자동 등록 + `WRITE_DATES_AS_TIMESTAMPS` 비활성이다.
     * **비활성을 여기서 명시해야 한다.** 그건 Boot의 `JacksonAutoConfiguration`이 넣는 것이라
     * [Jackson2ObjectMapperBuilder] 단독으로는 안 꺼지고, 그러면 날짜가 `[2026,8,15,11,49]`
     * 배열로 나가 운영과 다른 것을 검사하게 된다(실측).
     */
    private val webMapper: ObjectMapper = Jackson2ObjectMapperBuilder.json()
        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build()

    /** Render 컨테이너와 같은 조건 — 기본 타임존이 KST가 아니다. */
    private fun <T> inUtcContainer(block: () -> T): T {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            return block()
        } finally {
            TimeZone.setDefault(original)
        }
    }

    private fun generatedAtOnTheWire(report: Any): String =
        webMapper.readTree(webMapper.writeValueAsString(report)).get("generatedAt").asText()

    /**
     * 브라우저가 보는 문자열이 (1) 오프셋을 싣고 (2) 지금 이 순간을 가리키는지.
     * 오프셋이 없으면 [OffsetDateTime.parse]가 던진다 — 브라우저는 던지는 대신 조용히 추측한다.
     */
    private fun assertCarriesOffsetAndPointsAtNow(wireValue: String) {
        assertThat(wireValue)
            .describedAs("오프셋이 없으면 읽는 쪽이 자기 로컬 시각으로 추측한다 (UTC 컨테이너 → 한국 사용자에게 9시간 어긋남)")
            .matches("\\d{4}-\\d{2}-\\d{2}T[\\d:.]+\\+09:00")
        assertThat(Duration.between(OffsetDateTime.parse(wireValue).toInstant(), Instant.now()).abs())
            .describedAs("가리키는 절대 시각은 지금이어야 한다. 컨테이너 벽시계를 KST라고 우기면 9시간 미래가 된다")
            .isLessThan(Duration.ofMinutes(1))
    }

    private fun reportService() = ReportService(
        assetRepository = mock(AssetRepository::class.java),
        accountRepository = mock(AccountRepository::class.java),
        jdbc = mock(JdbcTemplate::class.java),
        fx = mock(FxConverter::class.java),
        benchmarkStore = mock(BenchmarkDailyStore::class.java),
        cashFlowRepository = mock(CashFlowRepository::class.java),
        dustThresholdKrw = BigDecimal(1000),
    )

    @Test
    fun `generatedAt을 선언한 응답 DTO는 전부 OffsetDateTime이다`() {
        val dtos = listOf(
            SummaryReport::class, AllocationReport::class, PerformanceReport::class,
            RiskReport::class, PositionsReport::class, BenchmarkReport::class,
            NetWorthReport::class, MonthlyPnlReport::class,
            DividendReport::class, EsgReport::class, GoalsResponse::class,
        )

        val offending = dtos.mapNotNull { dto ->
            val declared = dto.memberProperties.first { it.name == "generatedAt" }.returnType
            if (declared.classifier == OffsetDateTime::class) null else "${dto.simpleName}: $declared"
        }

        assertThat(offending)
            .describedAs("오프셋 없는 타입은 Jackson이 오프셋 없이 적고, 브라우저가 읽는 쪽 로컬 시각으로 추측한다")
            .isEmpty()
    }

    @Test
    fun `UTC 컨테이너에서 만든 요약 리포트의 generatedAt`() {
        val report = inUtcContainer { reportService().summary(userId) }
        assertCarriesOffsetAndPointsAtNow(generatedAtOnTheWire(report))
    }

    @Test
    fun `UTC 컨테이너에서 만든 배당 리포트의 generatedAt`() {
        val report = inUtcContainer {
            DividendReportService(mock(JdbcTemplate::class.java)).report(userId, "YTD")
        }
        assertCarriesOffsetAndPointsAtNow(generatedAtOnTheWire(report))
    }

    @Test
    fun `UTC 컨테이너에서 만든 목표 목록의 generatedAt`() {
        val response = inUtcContainer {
            GoalService(
                goalRepository = mock(GoalRepository::class.java),
                assetRepository = mock(AssetRepository::class.java),
                fx = mock(FxConverter::class.java),
            ).list(userId)
        }
        assertCarriesOffsetAndPointsAtNow(generatedAtOnTheWire(response))
    }
}
