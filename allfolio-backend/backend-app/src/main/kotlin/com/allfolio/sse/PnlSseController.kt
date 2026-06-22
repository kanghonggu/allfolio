package com.allfolio.sse

import com.allfolio.pnl.PositionCacheService
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID

/**
 * 실시간 PnL SSE 엔드포인트
 *
 * GET /api/sse/pnl/{portfolioId}
 *   → SseEmitter 등록 → 연결 유지
 *   → PnlCalculationService가 PriceUpdateEvent 처리 후 SseEmitterRegistry.send() 호출
 *   → 클라이언트: EventSource("...") onmessage 로 수신
 *
 * 이벤트 타입:
 *   connected     초기 연결 확인 + 현재 포지션 스냅샷
 *   pnl_update    자산별 실시간 PnL 업데이트
 *   heartbeat     30초 keepalive (프록시/nginx SSE timeout 방지)
 *
 * 인증: Allfolio JWT (SecurityConfig + JwtUserIdFilter 적용)
 * CORS: CorsConfig 적용 (기존 설정)
 */
@RestController
@RequestMapping("/api/sse")
class PnlSseController(
    private val emitterRegistry: SseEmitterRegistry,
    private val positionCacheService: PositionCacheService,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping(value = ["/pnl/{portfolioId}"], produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun subscribe(
        @PathVariable portfolioId: UUID,
        @RequestHeader("X-User-Id") userId: UUID,
    ): SseEmitter {
        val emitter = emitterRegistry.register(portfolioId)
        log.info("[SSE] client connected portfolioId={} userId={}", portfolioId, userId)

        // 초기 연결 시 현재 포지션 스냅샷 전송
        runCatching {
            val positions = positionCacheService.getPositions(portfolioId)
            val snapshot  = mapOf(
                "type"        to "connected",
                "portfolioId" to portfolioId,
                "positions"   to positions.values,
            )
            emitter.send(
                SseEmitter.event()
                    .name("connected")
                    .data(objectMapper.writeValueAsString(snapshot))
            )
        }.onFailure { e ->
            log.warn("[SSE] initial snapshot failed portfolioId={}: {}", portfolioId, e.message)
        }

        return emitter
    }
}
