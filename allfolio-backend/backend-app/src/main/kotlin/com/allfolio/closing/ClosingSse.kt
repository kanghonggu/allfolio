package com.allfolio.closing

import com.allfolio.sse.SseEmitterRegistry
import com.allfolio.workflow.application.WfEventPublisher
import com.allfolio.workflow.application.WfStepEvent
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID

/** 마감 이벤트 SSE 채널 — 고정 채널 UUID로 기존 SseEmitterRegistry 재사용 (P3 #31). */
object ClosingSseChannel {
    /** 고정 브로드캐스트 채널 (portfolioId 네임스페이스와 충돌하지 않는 예약 UUID) */
    val CHANNEL: UUID = UUID.fromString("00000000-0000-0000-0000-00000000c105")
    const val EVENT_NAME = "closing.step"
}

/** WfEventPublisher SSE 구현 — 완료·오류를 관제 화면에 실시간 push (FR-DASH-001/002). */
@Component
class ClosingSseEventPublisher(
    private val registry: SseEmitterRegistry,
) : WfEventPublisher {
    override fun publish(event: WfStepEvent) {
        registry.send(
            ClosingSseChannel.CHANNEL,
            ClosingSseChannel.EVENT_NAME,
            mapOf(
                "ymd" to event.ymd.toString(),
                "stepCd" to event.stepCd,
                "subStepCd" to event.subStepCd,
                "execSeq" to event.execSeq,
                "status" to event.status.name,
                "level" to if (event.status.name == "ERROR") "error" else "info",
                "remark" to event.remark,
            ),
        )
    }
}

/**
 * 관제 화면 SSE 구독 (ADMIN).
 * EventSource는 헤더를 못 보내므로 SseTokenFilter가 적용되는 /api/sse/ 경로 사용
 * (?token= 쿼리 → JWT 주입, SecurityConfig에서 hasRole(ADMIN) 매칭).
 */
@RestController
class ClosingSseController(private val registry: SseEmitterRegistry) {

    @GetMapping("/api/sse/closing")
    fun events(): SseEmitter = registry.register(ClosingSseChannel.CHANNEL)
}
