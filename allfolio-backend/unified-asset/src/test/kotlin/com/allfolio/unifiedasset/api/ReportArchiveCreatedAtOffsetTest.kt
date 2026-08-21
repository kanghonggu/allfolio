package com.allfolio.unifiedasset.api

import com.allfolio.report.application.GenerateReportUseCase
import com.allfolio.report.application.ReportArchiveRepository
import com.allfolio.report.domain.archive.ReportArchive
import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.report.domain.archive.ReportStatus
import com.allfolio.report.domain.archive.ReportType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.UUID
import kotlin.reflect.full.memberProperties

/**
 * 리포트 보관함의 `createdAt`도 **오프셋을 달고 나가야 한다.**
 *
 * [com.allfolio.unifiedasset.application.usecase.ReportGeneratedAtOffsetTest]가 `generatedAt`에
 * 대해 못 박은 것과 같은 결함이다. `LocalDateTime`은 Jackson이 오프셋 없이 적고
 * (`"2026-08-20T15:37:00"`), 브라우저의 `new Date(...)`는 오프셋 없는 값을 **읽는 쪽 로컬
 * 시각**으로 해석한다. Render 컨테이너에 TZ 설정이 없어 벽시계가 UTC이므로 한국 사용자에게는
 * 9시간 어긋난 시각이 뜬다.
 *
 * 보관함에서 이게 더 아픈 이유는 **밀림이 자정을 넘기면 시각이 아니라 날짜가 하루 틀리기**
 * 때문이다. 목록 화면은 `toLocaleString('ko-KR')`로 날짜까지 보여준다 — 8/21 00:37에 만든
 * 리포트가 8/20자로 뜨면 사용자는 어제 만든 리포트라고 읽는다.
 *
 * 고치는 건 값이 아니라 **전선 위의 타입**이다. 프런트에 KST를 박으면 한국 사용자에게만 맞고
 * 다른 시간대 사용자에게는 반대 방향으로 틀린다. 그래서 프런트는 한 줄도 건드리지 않는다.
 *
 * **단언은 전부 직렬화된 문자열을 본다.** 브라우저가 실제로 손에 쥐는 게 그것이고, DTO 필드를
 * 직접 만지면 타입 교체 전에는 컴파일조차 안 돼 "실패를 지켜보는" 절차가 무너진다.
 */
class ReportArchiveCreatedAtOffsetTest {

    private val userId: UUID = UUID.randomUUID()

    /**
     * 운영 웹 계층이 쓰는 매퍼를 그대로 재현한다. `application.yml`에 `spring.jackson` 설정이
     * 없으므로 운영은 Boot 기본값 — 잘 알려진 모듈 자동 등록 + `WRITE_DATES_AS_TIMESTAMPS` 비활성이다.
     * 비활성을 여기서 명시해야 한다. 그건 Boot의 `JacksonAutoConfiguration`이 넣는 것이라
     * [Jackson2ObjectMapperBuilder] 단독으로는 안 꺼지고, 그러면 날짜가 배열로 나가 운영과
     * 다른 것을 검사하게 된다.
     */
    private val webMapper: ObjectMapper = Jackson2ObjectMapperBuilder.json()
        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build()

    private val KST: ZoneId = ZoneId.of("Asia/Seoul")

    /**
     * 자정을 넘기는 고정 입력. **`now()`로는 이 케이스를 못 잡는다** — 실행 시각이 경계를 안 넘으면
     * 틀린 구현도 그냥 통과한다. 컬럼이 존 없는 `TIMESTAMP`이므로 DB에 앉아 있는 값은 UTC 벽시계다.
     */
    private val storedUtcWallClock: LocalDateTime = LocalDateTime.of(2026, 8, 20, 15, 37, 0)

    /** 같은 순간을 KST 달력으로 읽으면 **하루 뒤**다. 화면이 보여주는 게 이 날짜다. */
    private val sameMomentInKst: OffsetDateTime = OffsetDateTime.of(2026, 8, 21, 0, 37, 0, 0, ZoneOffset.ofHours(9))

    private fun archive(createdAt: LocalDateTime, id: UUID = UUID.randomUUID()) = ReportArchive.reconstruct(
        id = id,
        userId = userId,
        type = ReportType.MONTHLY_REPORT,
        period = ReportPeriod.monthly(2026, 8),
        asOfDate = LocalDate.of(2026, 8, 31),
        status = ReportStatus.FINAL,
        warnings = emptyList(),
        bodyJson = """{"a":1}""",
        createdAt = createdAt,
    )

    private fun controller(repository: ReportArchiveRepository) = ReportArchiveController(
        generateReport = mock(GenerateReportUseCase::class.java),
        archiveRepository = repository,
    )

    private fun wire(payload: Any): JsonNode =
        webMapper.readTree(webMapper.writeValueAsString(payload))

    /**
     * 브라우저가 보는 문자열이 오프셋을 싣는지. 오프셋이 없으면 [OffsetDateTime.parse]가 던진다 —
     * 브라우저는 던지는 대신 조용히 자기 로컬 시각으로 추측한다.
     */
    private fun assertCarriesOffset(wireValue: String) {
        assertThatCode { OffsetDateTime.parse(wireValue) }
            .describedAs("오프셋이 없으면 읽는 쪽이 자기 로컬 시각으로 추측한다 (UTC 컨테이너 → 한국 사용자에게 9시간 어긋남): %s", wireValue)
            .doesNotThrowAnyException()
    }

    /**
     * 가리키는 절대 시각이 맞는지 — 그리고 KST 달력으로 읽었을 때 **날짜가 하루 안 밀리는지**.
     * 오프셋을 `+09:00`으로 박으면(= 프런트에 KST 하드코딩과 같은 오류) 여기서 갈린다.
     */
    private fun assertPointsAtKstMidnightCrossing(wireValue: String) {
        val parsed = OffsetDateTime.parse(wireValue)
        assertThat(parsed.toInstant())
            .describedAs("저장값은 UTC 벽시계다. 이걸 KST라고 우기면 9시간 과거를 가리킨다")
            .isEqualTo(sameMomentInKst.toInstant())
        assertThat(parsed.atZoneSameInstant(KST).toLocalDate())
            .describedAs("화면은 ko-KR 달력으로 날짜까지 보여준다. 자정을 넘긴 리포트가 어제자로 뜨면 안 된다")
            .isEqualTo(LocalDate.of(2026, 8, 21))
    }

    @Test
    fun `ArchiveMetaResponse가 선언한 createdAt은 OffsetDateTime이다`() {
        val declared = ReportArchiveController.ArchiveMetaResponse::class
            .memberProperties.first { it.name == "createdAt" }.returnType

        assertThat(declared.classifier)
            .describedAs("오프셋 없는 타입은 Jackson이 오프셋 없이 적고, 브라우저가 읽는 쪽 로컬 시각으로 추측한다")
            .isEqualTo(OffsetDateTime::class)
    }

    @Test
    fun `목록 응답의 createdAt은 오프셋을 싣는다`() {
        val repository = mock(ReportArchiveRepository::class.java)
        `when`(repository.findAll(userId, null)).thenReturn(listOf(archive(storedUtcWallClock)))

        val body = wire(controller(repository).list(userId, null))

        assertCarriesOffset(body[0].get("createdAt").asText())
    }

    @Test
    fun `목록 응답의 createdAt은 자정을 넘겨도 KST 달력에서 8월 21일이다`() {
        val repository = mock(ReportArchiveRepository::class.java)
        `when`(repository.findAll(userId, null)).thenReturn(listOf(archive(storedUtcWallClock)))

        val body = wire(controller(repository).list(userId, null))

        assertPointsAtKstMidnightCrossing(body[0].get("createdAt").asText())
    }

    @Test
    fun `상세 응답의 meta createdAt도 오프셋을 싣고 같은 순간을 가리킨다`() {
        val id = UUID.randomUUID()
        val repository = mock(ReportArchiveRepository::class.java)
        `when`(repository.findById(id)).thenReturn(archive(storedUtcWallClock, id = id))

        val body = wire(controller(repository).detail(userId, id).body!!)
        val createdAt = body.get("meta").get("createdAt").asText()

        assertCarriesOffset(createdAt)
        assertPointsAtKstMidnightCrossing(createdAt)
    }

    @Test
    fun `저장 시각은 호스트 타임존에 흔들리지 않는다`() {
        // 읽는 쪽이 "저장값은 UTC 벽시계"라고 전제하는데, 지금은 그게 컨테이너 TZ에 기댄 우연이다.
        // 호스트가 KST면 9시간 미래를 UTC인 척 저장한다 — 존을 명시해야 우연이 규칙이 된다.
        val created = inTimeZone("Asia/Seoul") {
            ReportArchive.create(
                userId = userId,
                type = ReportType.MONTHLY_REPORT,
                period = ReportPeriod.monthly(2026, 8),
                asOfDate = LocalDate.of(2026, 8, 31),
                warnings = emptyList(),
                bodyJson = """{"a":1}""",
            )
        }

        assertThat(Duration.between(created.createdAt.toInstant(ZoneOffset.UTC), Instant.now()).abs())
            .describedAs("호스트 벽시계를 그대로 적으면 KST 호스트에서 9시간 미래가 저장된다")
            .isLessThan(Duration.ofMinutes(1))
    }

    private fun <T> inTimeZone(zone: String, block: () -> T): T {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        try {
            return block()
        } finally {
            TimeZone.setDefault(original)
        }
    }
}
