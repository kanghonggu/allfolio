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
        @RequestParam(defaultValue = "50") limit: Int,
    ): List<OutboxEventSummary> = outboxRepository.findForAdmin(
        status = status.name,
        eventType = eventType?.takeIf { it.isNotBlank() },
        from = from?.atStartOfDay(),
        to = to?.plusDays(1)?.atStartOfDay()?.minusNanos(1),
        pageable = PageRequest.of(0, limit.coerceIn(1, 200)),
    ).map { e ->
        OutboxEventSummary(
            e.id, e.aggregateType, e.aggregateId, e.eventType, e.status.name,
            e.retryCount, e.errorMessage, e.createdAt.toString(), e.processedAt?.toString(),
        )
    }

    /** outbox 단건 상세 (payload 포함). */
    @GetMapping("/outbox/{id}")
    fun outboxDetail(@PathVariable id: UUID): OutboxEventDetail {
        val e = outboxRepository.findById(id)
            .orElseThrow { NoSuchElementException("outbox event not found: $id") }
        return OutboxEventDetail(
            e.id, e.aggregateType, e.aggregateId, e.eventType, e.status.name,
            e.retryCount, e.errorMessage, e.createdAt.toString(), e.processedAt?.toString(), e.payload,
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
    fun dlqDead(@RequestParam broker: BrokerType): List<FailedTradeEvent> =
        dlqService.peekDead(broker)

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
    val createdAt: String,
    val processedAt: String?,
)

data class OutboxEventDetail(
    val id: UUID,
    val aggregateType: String,
    val aggregateId: UUID,
    val eventType: String,
    val status: String,
    val retryCount: Int,
    val errorMessage: String?,
    val createdAt: String,
    val processedAt: String?,
    val payload: String,
)

data class ReprocessRequest(val ids: List<UUID>)
data class DlqRequeueRequest(val broker: BrokerType)
data class DlqRequeueResponse(val requeued: Int)
