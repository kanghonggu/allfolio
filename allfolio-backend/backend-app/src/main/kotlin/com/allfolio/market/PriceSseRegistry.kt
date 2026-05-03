package com.allfolio.market

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.CopyOnWriteArrayList

@Component
class PriceSseRegistry {

    private val log = LoggerFactory.getLogger(javaClass)
    private val emitters = CopyOnWriteArrayList<SseEmitter>()

    fun register(): SseEmitter {
        val emitter = SseEmitter(TTL_MS)
        emitters.add(emitter)

        val cleanup = Runnable {
            emitters.remove(emitter)
            log.debug("[PriceSSE] emitter removed remaining={}", emitters.size)
        }
        emitter.onCompletion(cleanup)
        emitter.onTimeout(cleanup)
        emitter.onError { cleanup.run() }

        log.info("[PriceSSE] client connected total={}", emitters.size)
        return emitter
    }

    fun broadcast(eventName: String, data: String) {
        if (emitters.isEmpty()) return
        val dead = mutableListOf<SseEmitter>()
        emitters.forEach { emitter ->
            runCatching {
                emitter.send(SseEmitter.event().name(eventName).data(data))
            }.onFailure { dead.add(emitter) }
        }
        if (dead.isNotEmpty()) emitters.removeAll(dead.toSet())
    }

    fun activeCount(): Int = emitters.size

    companion object {
        private const val TTL_MS = 30 * 60 * 1000L
    }
}
