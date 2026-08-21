package com.allfolio.api.admin

import com.allfolio.broker.BrokerType
import com.allfolio.dlq.DlqService
import com.allfolio.dlq.FailedTradeEvent
import com.allfolio.processor.OutboxEventProcessor
import com.allfolio.processor.OutboxReprocessResult
import com.allfolio.trade.infrastructure.outbox.OutboxRepository
import com.allfolio.trade.infrastructure.outbox.OutboxStatus
import org.springframework.data.domain.PageRequest
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * Outbox·DLQ 운영 모니터링 API (AF-7, MN-700).
 * /api/admin 하위 전체 → hasRole(ADMIN) 게이트(SecurityConfig)로 보호.
 */
@RestController
@RequestMapping("/api/admin/ops")
class OpsAdminController(
    private val outboxRepository: OutboxRepository,
    private val outboxProcessor: OutboxEventProcessor,
    private val dlqService: DlqService,
) {
    /** 상태별 outbox 카운트 + 브로커별 Redis DLQ 크기. */
    @GetMapping("/summary")
    fun summary(): OpsSummaryResponse = OpsSummaryResponse(
        outbox = OutboxStatus.entries.associate { it.name to outboxRepository.countByStatus(it) },
        dlq = BrokerType.entries.map {
            DlqBrokerSummary(it.name, dlqService.size(it), dlqService.deadSize(it))
        },
    )

    /** outbox 목록 — status 필수, eventType/기간 옵셔널, 최신순 최대 200건. */
    @GetMapping("/outbox")
    fun outboxList(
        @RequestParam status: OutboxStatus,
        @RequestParam(required = false) eventType: String?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(required = false) zone: String?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): List<OutboxEventSummary> {
        val calendar = calendarZone(zone)
        return outboxRepository.findForAdmin(
            status = status.name,
            eventType = eventType?.takeIf { it.isNotBlank() },
            from = from?.let { dayStartInUtc(it, calendar) },
            to = to?.let { dayStartInUtc(it.plusDays(1), calendar).minusNanos(1) },
            pageable = PageRequest.of(0, limit.coerceIn(1, 200)),
        ).map { e ->
            OutboxEventSummary(
                e.id, e.aggregateType, e.aggregateId, e.eventType, e.status.name,
                e.retryCount, e.errorMessage, e.createdAt.onTheWire(), e.processedAt?.onTheWire(),
            )
        }
    }

    /**
     * 컬럼이 존 없는 `TIMESTAMP`고 값은 UTC 벽시계다. 그 전제를 전선에서 오프셋으로 명시한다.
     *
     * 이 필드들은 `LocalDateTime.toString()`으로 채운 `String`이었다 — `toString()`은 어떤 경우에도
     * 오프셋을 안 싣고(`"2026-08-20T15:37"`), 브라우저의 `new Date(...)`는 오프셋 없는 값을
     * **읽는 쪽 로컬 시각**으로 해석한다. 운영 컨테이너 벽시계가 UTC라 한국 사용자에게 9시간
     * 어긋났고, 밀림이 자정을 넘기면 목록의 **날짜가 하루 틀렸다.**
     *
     * 프런트에 KST를 박는 건 답이 아니다 — 한국 사용자에게만 맞고 다른 시간대 사용자에게는
     * 반대 방향으로 틀린다.
     */
    private fun LocalDateTime.onTheWire(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    /**
     * 날짜 필터가 어느 달력의 하루인지는 **클라이언트만 안다.**
     *
     * 표시는 브라우저가 렌더 시점에 존을 정하니 서버가 오프셋만 실으면 되지만, 필터는 질의
     * 시점에 존이 필요하다. 그래서 여기서만 클라이언트가 자기 존을 보낸다. 안 보내면 추측하지
     * 않는다 — 컬럼이 UTC 벽시계이므로 UTC 하루가 유일하게 정직한 기본값이다.
     *
     * 모르는 존을 조용히 UTC로 떨어뜨리지 않는다. 그러면 창이 9시간 밀린 채 아무 신호 없이
     * 돌아간다 — 이 결함이 오래 안 보인 이유가 그거다.
     */
    private fun calendarZone(zone: String?): ZoneId =
        zone?.let {
            runCatching { ZoneId.of(it) }
                .getOrElse { _ -> throw IllegalArgumentException("알 수 없는 타임존입니다: $it") }
        } ?: ZoneOffset.UTC

    /** 클라이언트 달력의 하루 시작 → 컬럼과 같은 UTC 벽시계. */
    private fun dayStartInUtc(day: LocalDate, calendar: ZoneId): LocalDateTime =
        day.atStartOfDay(calendar).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime()

    /** outbox 단건 상세 (payload 포함). */
    @GetMapping("/outbox/{id}")
    fun outboxDetail(@PathVariable id: UUID): OutboxEventDetail {
        val e = outboxRepository.findById(id)
            .orElseThrow { NoSuchElementException("outbox event not found: $id") }
        return OutboxEventDetail(
            e.id, e.aggregateType, e.aggregateId, e.eventType, e.status.name,
            e.retryCount, e.errorMessage, e.createdAt.onTheWire(), e.processedAt?.onTheWire(), e.payload,
        )
    }

    /** DEAD 건 수동 재처리 — 폴러 경로 재사용, retryCount 보존. */
    @PostMapping("/outbox/reprocess")
    fun reprocess(@RequestBody req: ReprocessRequest): OutboxReprocessResult {
        require(req.ids.isNotEmpty() && req.ids.size <= 100) { "ids는 1~100건이어야 합니다" }
        return outboxProcessor.reprocessDead(req.ids)
    }

    /** Redis DLQ dead 목록 (비파괴 조회). */
    @GetMapping("/dlq/dead")
    fun dlqDead(@RequestParam broker: BrokerType): List<FailedDlqEventResponse> =
        dlqService.peekDead(broker).map {
            FailedDlqEventResponse(
                it.id, it.brokerType, it.accountNo, it.payloadType,
                it.payload, it.errorMessage, it.retryCount, it.createdAt.onTheWire(),
            )
        }

    /** Redis DLQ dead → main 재큐 (1회성 수동 기회, retryCount 보존). */
    @PostMapping("/dlq/requeue")
    fun dlqRequeue(@RequestBody req: DlqRequeueRequest): DlqRequeueResponse =
        DlqRequeueResponse(dlqService.requeueDead(req.broker))
}

data class OpsSummaryResponse(val outbox: Map<String, Long>, val dlq: List<DlqBrokerSummary>)
data class DlqBrokerSummary(val broker: String, val waiting: Long, val dead: Long)

data class OutboxEventSummary(
    val id: UUID,
    val aggregateType: String,
    val aggregateId: UUID,
    val eventType: String,
    val status: String,
    val retryCount: Int,
    val errorMessage: String?,
    val createdAt: OffsetDateTime,
    val processedAt: OffsetDateTime?,
)

data class OutboxEventDetail(
    val id: UUID,
    val aggregateType: String,
    val aggregateId: UUID,
    val eventType: String,
    val status: String,
    val retryCount: Int,
    val errorMessage: String?,
    val createdAt: OffsetDateTime,
    val processedAt: OffsetDateTime?,
    val payload: String,
)

/**
 * Redis DLQ dead 항목의 전선 표현.
 *
 * [FailedTradeEvent]를 그대로 내보내지 않는 이유: 그 클래스는 DTO가 아니라 **Redis 저장 포맷**이라
 * 필드 타입을 전선 사정에 맞춰 바꾸면 이미 큐에 있는 항목이 역직렬화에 실패한다.
 */
data class FailedDlqEventResponse(
    val id: UUID,
    val brokerType: String,
    val accountNo: String,
    val payloadType: String,
    val payload: String,
    val errorMessage: String,
    val retryCount: Int,
    val createdAt: OffsetDateTime,
)

data class ReprocessRequest(val ids: List<UUID>)
data class DlqRequeueRequest(val broker: BrokerType)
data class DlqRequeueResponse(val requeued: Int)
