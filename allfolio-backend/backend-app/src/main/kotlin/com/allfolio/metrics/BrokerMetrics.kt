package com.allfolio.metrics

import com.allfolio.broker.BrokerType
import com.allfolio.dlq.DlqService
import com.allfolio.trade.infrastructure.outbox.OutboxRepository
import com.allfolio.trade.infrastructure.outbox.OutboxStatus
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component

/**
 * 통합 Micrometer 메트릭 컴포넌트
 *
 * 설계 원칙:
 * - Counter.increment() / Timer.record(): 완전 in-memory O(1) — 블로킹 없음
 * - Gauge: @PostConstruct 1회 등록 (DLQ/Outbox 크기 실시간 노출)
 * - /actuator/prometheus 엔드포인트로 Grafana가 스크레이핑
 *
 * 메트릭 카탈로그:
 *   trade.process.count{broker, result}     — broker sync 처리 결과
 *   trade.process.latency{broker}           — broker sync 레이턴시
 *   snapshot.process.latency                — 스냅샷 생성 레이턴시
 *   dlq.size{broker, queue}                 — Redis DLQ 크기 Gauge
 *   dlq.push.count{broker, type}            — DLQ 적재 횟수
 *   dlq.retry.count{broker, type}           — DLQ 재시도 횟수
 *   dlq.dead.count{broker}                  — DLQ dead letter 횟수
 *   outbox.event.count{result}              — Outbox 처리 결과 (processed/failed/dead)
 *   outbox.process.latency                  — Outbox 처리 레이턴시
 *   outbox.pending.size                     — PENDING 이벤트 수 Gauge
 *   outbox.failed.size                      — FAILED 이벤트 수 Gauge
 *   outbox.dead.size                        — DEAD 이벤트 수 Gauge
 *   kafka.publish.count{result}             — Kafka 발행 성공/실패
 *   outbox.kafka.consumed                   — Kafka Consumer 처리 수
 *   broker.api.latency{broker, operation}   — Broker API 레이턴시
 *   broker.api.error.count{broker, op}      — Broker API 에러
 *   token.refresh.count{broker, result}     — 토큰 갱신 횟수
 *   market.price.update.count{exchange}     — WebSocket 시세 수신
 *   pnl.calculation.count{portfolio}        — PnL 계산 횟수
 */
@Component
class BrokerMetrics(
    private val meterRegistry: MeterRegistry,
    private val dlqService: DlqService,
    private val outboxRepository: OutboxRepository,
) {
    // ── Gauge 등록 (DLQ + Outbox) ─────────────────────────────────
    @PostConstruct
    fun registerGauges() {
        // DLQ 크기 Gauge (브로커별)
        BrokerType.entries.forEach { broker ->
            io.micrometer.core.instrument.Gauge
                .builder("dlq.size") { dlqService.size(broker).toDouble() }
                .tag("broker", broker.name).tag("queue", "active")
                .description("DLQ active queue size")
                .register(meterRegistry)

            io.micrometer.core.instrument.Gauge
                .builder("dlq.size") { dlqService.deadSize(broker).toDouble() }
                .tag("broker", broker.name).tag("queue", "dead")
                .description("DLQ dead-letter queue size")
                .register(meterRegistry)
        }

        // Outbox 상태별 Gauge
        registerOutboxGauge("pending", OutboxStatus.PENDING)
        registerOutboxGauge("failed",  OutboxStatus.FAILED)
        registerOutboxGauge("dead",    OutboxStatus.DEAD)
    }

    private fun registerOutboxGauge(statusLabel: String, status: OutboxStatus) {
        io.micrometer.core.instrument.Gauge
            .builder("outbox.${statusLabel}.size") {
                runCatching {
                    outboxRepository.findTop100ByStatusOrderByCreatedAtAsc(status).size.toDouble()
                }.getOrDefault(0.0)
            }
            .description("Outbox $statusLabel events (sampled top-100)")
            .register(meterRegistry)
    }

    // ── Trade 처리 ────────────────────────────────────────────────

    fun tradeRecorded(brokerType: String) =
        meterRegistry.counter("trade.process.count", "broker", brokerType, "result", "success").increment()

    fun tradeDuplicateSkipped(brokerType: String) =
        meterRegistry.counter("trade.process.count", "broker", brokerType, "result", "dedup").increment()

    fun tradeFailedToDlq(brokerType: String) =
        meterRegistry.counter("trade.process.count", "broker", brokerType, "result", "dlq").increment()

    fun <T> recordTradeLatency(brokerType: String, block: () -> T): T =
        Timer.builder("trade.process.latency")
            .tag("broker", brokerType)
            .register(meterRegistry)
            .recordCallable { block() }!!

    // ── Snapshot ──────────────────────────────────────────────────

    fun <T> recordSnapshotLatency(block: () -> T): T =
        Timer.builder("snapshot.process.latency")
            .description("Daily snapshot generation latency")
            .register(meterRegistry)
            .recordCallable { block() }!!

    // ── DLQ ───────────────────────────────────────────────────────

    fun dlqPushed(brokerType: String, payloadType: String) =
        meterRegistry.counter("dlq.push.count", "broker", brokerType, "type", payloadType).increment()

    fun dlqRetried(brokerType: String, payloadType: String) =
        meterRegistry.counter("dlq.retry.count", "broker", brokerType, "type", payloadType).increment()

    fun dlqDeadLettered(brokerType: String) =
        meterRegistry.counter("dlq.dead.count", "broker", brokerType).increment()

    // ── Outbox ────────────────────────────────────────────────────

    fun outboxProcessed(count: Int = 1) =
        meterRegistry.counter("outbox.event.count", "result", "processed").increment(count.toDouble())

    fun outboxFailed() =
        meterRegistry.counter("outbox.event.count", "result", "failed").increment()

    fun outboxDead() =
        meterRegistry.counter("outbox.event.count", "result", "dead").increment()

    fun <T> recordOutboxLatency(block: () -> T): T =
        Timer.builder("outbox.process.latency")
            .description("Outbox event processing latency (snapshot + status update)")
            .register(meterRegistry)
            .recordCallable { block() }!!

    // ── Kafka Outbox Publisher ────────────────────────────────────

    fun kafkaPublishSuccess() =
        meterRegistry.counter("kafka.publish.count", "result", "success", "topic", "outbox.trade").increment()

    fun kafkaPublishFailed() =
        meterRegistry.counter("kafka.publish.count", "result", "fail", "topic", "outbox.trade").increment()

    fun outboxKafkaConsumed() =
        meterRegistry.counter("kafka.consume.count", "topic", "outbox.trade", "result", "success").increment()

    fun outboxKafkaConsumeFailed() =
        meterRegistry.counter("kafka.consume.count", "topic", "outbox.trade", "result", "fail").increment()

    /** INSERT 기반 중복 감지 — race condition 없는 원자적 중복 처리 */
    fun kafkaConsumerDuplicate() =
        meterRegistry.counter("kafka.consume.count", "topic", "outbox.trade", "result", "duplicate").increment()

    /** 역직렬화 실패 — 메시지 스키마 불일치 감지 */
    fun kafkaConsumerDeserializeFailed() =
        meterRegistry.counter("kafka.consume.count", "topic", "outbox.trade", "result", "deserialize_failed").increment()

    /** Kafka Consumer 처리 레이턴시 — processEvent() 실행 시간 */
    fun <T> recordKafkaConsumerLatency(block: () -> T): T =
        Timer.builder("kafka.consumer.process.latency")
            .tag("topic", "outbox.trade")
            .description("Kafka outbox.trade consumer processing latency")
            .register(meterRegistry)
            .recordCallable { block() }!!

    // ── Broker API ────────────────────────────────────────────────

    fun <T> recordApiLatency(brokerType: String, operation: String, block: () -> T): T =
        Timer.builder("broker.api.latency")
            .tag("broker", brokerType)
            .tag("operation", operation)
            .register(meterRegistry)
            .recordCallable { block() }!!

    fun apiError(brokerType: String, operation: String) =
        meterRegistry.counter("broker.api.error.count", "broker", brokerType, "operation", operation).increment()

    // ── Token ─────────────────────────────────────────────────────

    fun tokenRefreshed(brokerType: String) =
        meterRegistry.counter("token.refresh.count", "broker", brokerType).increment()

    fun tokenRefreshFailed(brokerType: String) =
        meterRegistry.counter("token.refresh.count", "broker", brokerType, "result", "failed").increment()

    // ── WebSocket / PnL ──────────────────────────────────────────

    fun priceUpdateReceived(exchange: String, symbol: String) =
        meterRegistry.counter("market.price.update.count", "exchange", exchange, "symbol", symbol).increment()

    fun pnlCalculated(portfolioId: String) =
        meterRegistry.counter("pnl.calculation.count", "portfolio", portfolioId).increment()
}
