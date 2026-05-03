package com.allfolio.sse

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * SSE Emitter 레지스트리
 *
 * portfolioId → Emitter 목록 관리
 * 한 portfolioId에 복수 탭/클라이언트 연결 허용 (CopyOnWriteArrayList)
 *
 * Emitter TTL: 30분 (클라이언트가 재연결하는 방식)
 * 완료/타임아웃/에러 시 자동 제거
 */
@Component
class SseEmitterRegistry {

    private val log = LoggerFactory.getLogger(javaClass)

    private val emitters = ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>>()

    fun register(portfolioId: UUID): SseEmitter {
        val emitter = SseEmitter(TTL_MS)

        val list = emitters.computeIfAbsent(portfolioId) { CopyOnWriteArrayList() }
        list.add(emitter)

        val cleanup = Runnable {
            list.remove(emitter)
            if (list.isEmpty()) emitters.remove(portfolioId)
            log.debug("[SSE] emitter removed portfolioId={} remaining={}", portfolioId, list.size)
        }
        emitter.onCompletion(cleanup)
        emitter.onTimeout(cleanup)
        emitter.onError { cleanup.run() }

        log.info("[SSE] registered portfolioId={} total={}", portfolioId, list.size)
        return emitter
    }

    /**
     * portfolioId 구독 중인 모든 클라이언트에 이벤트 전송
     * 전송 실패한 emitter는 자동 제거
     */
    fun send(portfolioId: UUID, eventName: String, data: Any) {
        val list = emitters[portfolioId] ?: return
        if (list.isEmpty()) return

        val dead = mutableListOf<SseEmitter>()
        list.forEach { emitter ->
            runCatching {
                emitter.send(
                    SseEmitter.event()
                        .name(eventName)
                        .data(data)
                )
            }.onFailure {
                dead.add(emitter)
            }
        }
        if (dead.isNotEmpty()) {
            list.removeAll(dead.toSet())
            log.debug("[SSE] removed {} dead emitters portfolioId={}", dead.size, portfolioId)
        }
    }

    fun activeCount(): Int = emitters.values.sumOf { it.size }

    companion object {
        private const val TTL_MS = 30 * 60 * 1000L  // 30분
    }
}
