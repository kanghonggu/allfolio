package com.allfolio.dlq

import com.allfolio.broker.BrokerType
import com.allfolio.kafka.KafkaDlqProducer
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.util.Optional

/**
 * DLQ Service — Redis List (primary) + Kafka (optional extension)
 *
 * Redis key:
 *   dlq:trade:{brokerType}  — 재처리 대기 (RPUSH/LPOP)
 *   dlq:dead:{brokerType}   — MAX_RETRIES 초과, TTL 7일
 *
 * Kafka (ConditionalOnProperty spring.kafka.bootstrap-servers):
 *   dlq.trade, dlq.fetch, dlq.dead
 *   → 항상 Redis에 먼저 적재, Kafka는 추가 durability
 *
 * 성능: push() = Redis RPUSH O(1) + Kafka async send() → 비동기, 블로킹 없음
 */
@Service
class DlqService(
    private val stringRedisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
    private val kafkaDlqProducer: Optional<KafkaDlqProducer>,  // Kafka 미설정 시 empty
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * DLQ 적재 — Redis RPUSH + Kafka async send
     * 두 저장소 모두 try-catch — 어느 쪽 장애도 메인 흐름 block 금지
     */
    fun push(event: FailedTradeEvent) {
        // Redis (primary)
        runCatching {
            val json = objectMapper.writeValueAsString(event)
            stringRedisTemplate.opsForList().rightPush(dlqKey(event.brokerType), json)
        }.onFailure { e ->
            log.error("[DLQ] Redis push failed — data may be lost! broker={} type={}: {}", event.brokerType, event.payloadType, e.message)
        }

        // Kafka (optional, async)
        kafkaDlqProducer.ifPresent { it.send(event) }

        // Metric: non-blocking counter
        runCatching {
            meterRegistry.counter("dlq.push.count",
                "broker", event.brokerType,
                "type", event.payloadType,
            ).increment()
        }

        log.debug("[DLQ] push type={} broker={} id={} retry={}", event.payloadType, event.brokerType, event.id, event.retryCount)
    }

    /** DLQ 디큐 — LPOP (Redis DlqWorker용) */
    fun pop(brokerType: BrokerType): FailedTradeEvent? =
        runCatching {
            stringRedisTemplate.opsForList().leftPop(dlqKey(brokerType.name))
                ?.let { objectMapper.readValue(it, FailedTradeEvent::class.java) }
        }.getOrElse { e ->
            log.warn("[DLQ] pop failed broker={}", brokerType, e)
            null
        }

    /** Dead-letter 이동 — MAX_RETRIES 초과, TTL 7일 */
    fun pushDead(event: FailedTradeEvent) {
        runCatching {
            val json = objectMapper.writeValueAsString(event)
            stringRedisTemplate.opsForList().rightPush(deadKey(event.brokerType), json)
            stringRedisTemplate.expire(deadKey(event.brokerType), java.time.Duration.ofDays(7))
            log.warn("[DLQ] dead-letter broker={} type={} id={} retries={}", event.brokerType, event.payloadType, event.id, event.retryCount)
        }.onFailure { e ->
            log.error("[DLQ] dead push failed broker={}", event.brokerType, e)
        }

        kafkaDlqProducer.ifPresent { it.sendDead(event) }

        runCatching {
            meterRegistry.counter("dlq.dead.count", "broker", event.brokerType).increment()
        }
    }

    /** DLQ 크기 (Gauge 등록 + 모니터링 API용) */
    fun size(brokerType: BrokerType): Long =
        runCatching { stringRedisTemplate.opsForList().size(dlqKey(brokerType.name)) ?: 0L }.getOrDefault(0L)

    fun deadSize(brokerType: BrokerType): Long =
        runCatching { stringRedisTemplate.opsForList().size(deadKey(brokerType.name)) ?: 0L }.getOrDefault(0L)

    // ── 어드민 모니터링 (AF-7) ──

    /** dead 리스트 비파괴 조회 (어드민 화면용). 역직렬화 실패 항목은 건너뜀. */
    fun peekDead(brokerType: BrokerType, limit: Long = 50): List<FailedTradeEvent> =
        runCatching {
            stringRedisTemplate.opsForList().range(deadKey(brokerType.name), 0, limit - 1)
                .orEmpty()
                .mapNotNull { json ->
                    runCatching { objectMapper.readValue(json, FailedTradeEvent::class.java) }.getOrNull()
                }
        }.getOrElse { e ->
            log.warn("[DLQ] peekDead failed broker={}", brokerType, e)
            emptyList()
        }

    /**
     * dead → main 재큐 (어드민 수동). retryCount 보존 — Worker가 실패 시 retryCount 증가로
     * 즉시 dead 복귀하는 1회성 수동 기회. 이동 건수 반환.
     */
    fun requeueDead(brokerType: BrokerType): Int {
        var moved = 0
        while (moved < MAX_REQUEUE) {
            val json = runCatching {
                stringRedisTemplate.opsForList().leftPop(deadKey(brokerType.name))
            }.getOrElse { e ->
                log.error("[DLQ] requeueDead pop failed broker={}", brokerType, e)
                null
            } ?: break
            stringRedisTemplate.opsForList().rightPush(dlqKey(brokerType.name), json)
            moved++
        }
        if (moved > 0) log.info("[DLQ] requeued {} dead events broker={}", moved, brokerType)
        return moved
    }

    private fun dlqKey(brokerType: String)  = "dlq:trade:$brokerType"
    private fun deadKey(brokerType: String) = "dlq:dead:$brokerType"

    companion object {
        const val MAX_RETRIES = 5

        /** 1회 requeue 상한 — 무한 루프·과부하 방어 */
        const val MAX_REQUEUE = 500
    }
}
