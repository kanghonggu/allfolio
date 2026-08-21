package com.allfolio.api.admin

import com.allfolio.broker.BrokerType
import com.allfolio.dlq.DlqService
import com.allfolio.dlq.FailedTradeEvent
import com.allfolio.processor.OutboxEventProcessor
import com.allfolio.trade.infrastructure.outbox.OutboxEventEntity
import com.allfolio.trade.infrastructure.outbox.OutboxRepository
import com.allfolio.trade.infrastructure.outbox.OutboxStatus
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Optional
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties

/**
 * 운영 화면(`/unified/admin/ops`)의 시각도 **오프셋을 달고 나가야 한다.**
 *
 * 리포트 보관함·계좌 동기화와 같은 결함이다 — 오프셋 없는 값을 전선에 실으면 브라우저의
 * `new Date(...)`가 **읽는 쪽 로컬 시각**으로 해석한다. 운영 컨테이너 벽시계가 UTC라 한국
 * 사용자에게 9시간 어긋나고, 밀림이 자정을 넘기면 **날짜가 하루 틀린다.**
 *
 * 다만 이 화면은 경로가 둘이고 모양이 서로 다르다.
 *
 * - **Outbox**: DTO 필드가 `String`이었고 `LocalDateTime.toString()`으로 채웠다. 타입 교체만으로는
 *   안 되고 — `toString()`은 어떤 경우에도 오프셋을 안 싣는다 — 오프셋을 태워야 한다.
 * - **Redis DLQ**: DTO 필드가 `LocalDateTime`이라 보관함과 같은 모양이었다. 하지만
 *   [FailedTradeEvent]는 DTO가 아니라 **Redis 저장 포맷**이라 타입을 바꾸면 이미 큐에 있는
 *   항목이 역직렬화에 실패한다([com.allfolio.dlq.FailedTradeEventClockTest] 참조). 그래서
 *   저장 모델은 그대로 두고 전선 DTO를 따로 뒀다.
 *
 * 그리고 **표시만 틀린 게 아니었다** — 날짜 필터도 UTC 하루를 잡고 있었다. 표시는 브라우저가
 * 렌더 시점에 존을 정하니 서버가 오프셋만 실으면 되지만, 필터는 **질의 시점에 존을 알아야**
 * 하고 그걸 아는 건 클라이언트뿐이다. 그래서 여기서만 클라이언트가 자기 존을 보낸다.
 */
class OpsTimeOffsetTest {

    /** 운영 웹 계층이 쓰는 매퍼 재현 — Boot 기본값(`WRITE_DATES_AS_TIMESTAMPS` 비활성). */
    private val webMapper: ObjectMapper = Jackson2ObjectMapperBuilder.json()
        .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build<ObjectMapper>()

    private val KST: ZoneId = ZoneId.of("Asia/Seoul")

    /**
     * 자정을 넘기는 고정 입력. **`now()`로는 이 케이스를 못 잡는다** — 실행 시각이 경계를 안 넘으면
     * 틀린 구현도 그냥 통과한다. 컬럼이 존 없는 `TIMESTAMP`라 DB에 앉아 있는 값은 UTC 벽시계다.
     */
    private val storedUtcWallClock: LocalDateTime = LocalDateTime.of(2026, 8, 20, 15, 37, 0)

    /** 같은 순간을 KST 달력으로 읽으면 **하루 뒤**다. 화면이 보여주는 게 이 날짜다. */
    private val sameMomentInKst: OffsetDateTime =
        storedUtcWallClock.atOffset(java.time.ZoneOffset.UTC).atZoneSameInstant(KST).toOffsetDateTime()

    private val outboxRepository: OutboxRepository = mock(OutboxRepository::class.java)
    private val dlqService: DlqService = mock(DlqService::class.java)

    private fun controller() = OpsAdminController(
        outboxRepository = outboxRepository,
        outboxProcessor = mock(OutboxEventProcessor::class.java),
        dlqService = dlqService,
    )

    private fun outboxEvent(id: UUID = UUID.randomUUID()) = OutboxEventEntity(
        id            = id,
        aggregateType = "TRADE",
        aggregateId   = UUID.randomUUID(),
        eventType     = "TRADE_RECORDED",
        payload       = """{"a":1}""",
        status        = OutboxStatus.PROCESSED,
        createdAt     = storedUtcWallClock,
        retryCount    = 0,
        processedAt   = storedUtcWallClock.plusSeconds(3),
    )

    private fun wire(payload: Any): JsonNode = webMapper.readTree(webMapper.writeValueAsString(payload))

    private fun assertCarriesOffset(wireValue: String) {
        assertThatCode { OffsetDateTime.parse(wireValue) }
            .describedAs("오프셋이 없으면 읽는 쪽이 자기 로컬 시각으로 추측한다 (UTC 컨테이너 → 한국 사용자에게 9시간 어긋남): %s", wireValue)
            .doesNotThrowAnyException()
    }

    private fun assertPointsAtKstMidnightCrossing(wireValue: String) {
        val parsed = OffsetDateTime.parse(wireValue)
        assertThat(parsed.toInstant())
            .describedAs("저장값은 UTC 벽시계다. 이걸 KST라고 우기면 9시간 과거를 가리킨다")
            .isEqualTo(sameMomentInKst.toInstant())
        assertThat(parsed.atZoneSameInstant(KST).toLocalDate())
            .describedAs("화면은 ko-KR 달력으로 날짜까지 보여준다. 자정을 넘긴 이벤트가 어제자로 뜨면 안 된다")
            .isEqualTo(LocalDate.of(2026, 8, 21))
    }

    /**
     * Mockito의 `any()`는 null을 돌려주는데 코틀린은 non-null 파라미터에 인트린식 null 검사를 넣는다.
     * 제네릭으로 한 겹 감싸면 플랫폼 타입이 아니게 되어 그 검사가 안 붙는다.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyArg(): T = ArgumentMatchers.any<T>() ?: null as T

    private fun declaredType(dto: KClass<*>, property: String) =
        dto.memberProperties.first { it.name == property }.returnType.classifier

    @Test
    fun `운영 응답 DTO가 선언한 시각 필드는 전부 OffsetDateTime이다`() {
        val offending = listOf(
            OutboxEventSummary::class to "createdAt",
            OutboxEventSummary::class to "processedAt",
            OutboxEventDetail::class to "createdAt",
            OutboxEventDetail::class to "processedAt",
            FailedDlqEventResponse::class to "createdAt",
        ).mapNotNull { (dto, property) ->
            val declared = declaredType(dto, property)
            if (declared == OffsetDateTime::class) null else "${dto.simpleName}.$property: $declared"
        }

        assertThat(offending)
            .describedAs("String이든 LocalDateTime이든, 오프셋을 안 싣는 타입은 브라우저가 읽는 쪽 로컬 시각으로 추측한다")
            .isEmpty()
    }

    @Test
    fun `outbox 목록의 createdAt은 오프셋을 싣고 자정을 넘겨도 KST 8월 21일이다`() {
        `when`(outboxRepository.findForAdmin(anyArg(), anyArg(), anyArg(), anyArg(), anyArg()))
            .thenReturn(listOf(outboxEvent()))

        val body = wire(controller().outboxList(OutboxStatus.PROCESSED, null, null, null, null, 50))

        assertCarriesOffset(body[0].get("createdAt").asText())
        assertPointsAtKstMidnightCrossing(body[0].get("createdAt").asText())
    }

    @Test
    fun `outbox 상세의 createdAt과 processedAt도 오프셋을 싣는다`() {
        val id = UUID.randomUUID()
        `when`(outboxRepository.findById(id)).thenReturn(Optional.of(outboxEvent(id)))

        val body = wire(controller().outboxDetail(id))

        assertCarriesOffset(body.get("createdAt").asText())
        assertPointsAtKstMidnightCrossing(body.get("createdAt").asText())
        assertCarriesOffset(body.get("processedAt").asText())
    }

    @Test
    fun `DLQ dead 목록의 createdAt도 오프셋을 싣고 같은 순간을 가리킨다`() {
        `when`(dlqService.peekDead(BrokerType.KIS)).thenReturn(
            listOf(
                FailedTradeEvent(
                    brokerType = "KIS", accountNo = "1234", payloadType = FailedTradeEvent.TYPE_TRADE_COMMAND,
                    payload = """{"a":1}""", errorMessage = "boom", retryCount = 5,
                    createdAt = storedUtcWallClock,
                )
            )
        )

        val body = wire(controller().dlqDead(BrokerType.KIS))

        assertCarriesOffset(body[0].get("createdAt").asText())
        assertPointsAtKstMidnightCrossing(body[0].get("createdAt").asText())
    }

    @Test
    fun `날짜 필터는 클라이언트 달력의 하루를 UTC 창으로 번역한다`() {
        var capturedFrom: LocalDateTime? = null
        var capturedTo: LocalDateTime? = null
        `when`(outboxRepository.findForAdmin(anyArg(), anyArg(), anyArg(), anyArg(), anyArg())).thenAnswer { inv ->
            capturedFrom = inv.getArgument(2)
            capturedTo = inv.getArgument(3)
            emptyList<OutboxEventEntity>()
        }

        controller().outboxList(
            status = OutboxStatus.PROCESSED, eventType = null,
            from = LocalDate.of(2026, 8, 21), to = LocalDate.of(2026, 8, 21),
            zone = "Asia/Seoul", limit = 50,
        )

        // 컬럼은 UTC 벽시계다. KST 8/21 하루는 UTC로 8/20 15:00 ~ 8/21 14:59:59.999999999.
        // 존을 안 쓰면 UTC 8/21 하루를 잡아 창 전체가 9시간 밀린다 — 표시가 아니라 조회 범위가 틀린다.
        assertThat(capturedFrom)
            .describedAs("관리자가 고른 8/21은 자기 달력의 하루다. 그 하루의 시작은 UTC로 전날 15:00이다")
            .isEqualTo(LocalDateTime.of(2026, 8, 20, 15, 0, 0))
        assertThat(capturedTo)
            .describedAs("끝은 다음 날 00:00 직전 — UTC로 8/21 14:59:59.999999999")
            .isEqualTo(LocalDateTime.of(2026, 8, 21, 14, 59, 59, 999_999_999))
    }

    @Test
    fun `zone을 안 보내면 UTC 하루로 해석한다`() {
        var capturedFrom: LocalDateTime? = null
        `when`(outboxRepository.findForAdmin(anyArg(), anyArg(), anyArg(), anyArg(), anyArg())).thenAnswer { inv ->
            capturedFrom = inv.getArgument(2)
            emptyList<OutboxEventEntity>()
        }

        controller().outboxList(
            status = OutboxStatus.PROCESSED, eventType = null,
            from = LocalDate.of(2026, 8, 21), to = null, zone = null, limit = 50,
        )

        // 존을 모르면 추측하지 않는다. 컬럼이 UTC 벽시계이므로 UTC 하루가 유일하게 정직한 기본값이다.
        assertThat(capturedFrom).isEqualTo(LocalDateTime.of(2026, 8, 21, 0, 0, 0))
    }

    @Test
    fun `알 수 없는 zone은 조용히 UTC로 떨어지지 않고 거절한다`() {
        // 조용히 폴백하면 창이 9시간 밀린 채로 아무 신호 없이 돌아간다 — 이 결함이 오래 안 보인 이유다.
        assertThatThrownBy {
            controller().outboxList(
                status = OutboxStatus.PROCESSED, eventType = null,
                from = LocalDate.of(2026, 8, 21), to = null, zone = "Mars/Olympus", limit = 50,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
