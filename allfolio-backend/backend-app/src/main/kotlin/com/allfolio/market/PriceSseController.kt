package com.allfolio.market

import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * 실시간 가격 SSE 엔드포인트
 *
 * GET /api/sse/prices
 *   → 연결 즉시 "connected" 이벤트 전송
 *   → 이후 WsAdapter가 수신한 가격을 실시간 브로드캐스트
 *
 * 이벤트 형식:
 *   event: price
 *   data: {"exchange":"BINANCE","symbol":"BTCUSDT","price":"67000.5","timestamp":1234567890}
 *
 * 인증: SseTokenFilter가 ?token= 쿼리 파라미터에서 JWT 추출
 */
@RestController
@RequestMapping("/api/sse")
class PriceSseController(
    private val registry: PriceSseRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/prices", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun subscribe(): SseEmitter {
        val emitter = registry.register()
        runCatching {
            emitter.send(SseEmitter.event().name("connected").data("ok"))
        }
        return emitter
    }
}
