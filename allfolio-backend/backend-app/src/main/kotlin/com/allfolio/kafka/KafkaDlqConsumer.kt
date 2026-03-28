package com.allfolio.kafka

import com.allfolio.dlq.DlqService
import com.allfolio.dlq.FailedTradeEvent
import com.allfolio.metrics.BrokerMetrics
import com.allfolio.trade.application.RecordTradeCommand
import com.allfolio.trade.application.RecordTradeUseCase
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component

/**
 * Kafka DLQ Consumer
 *
 * 설계 원칙:
 * - 수동 Acknowledgment (enable-auto-commit: false) → 처리 완료 후 커밋
 * - TRADE_COMMAND: recordTradeUseCase.record() 재시도
 *   - DataIntegrityViolationException → dedup, ack & discard
 *   - MAX_RETRIES 초과 → dlq.dead 이동 후 ack
 * - FETCH_PARAMS: BrokerSyncScheduler 자연 재시도 위임, ack & log
 *
 * 성능 영향:
 * - Trade write path와 완전 분리 (별도 Kafka consumer thread)
 * - concurrency=3: 파티션당 1 스레드
 */
@Component
@ConditionalOnProperty(name = ["spring.kafka.bootstrap-servers"], matchIfMissing = false)
class KafkaDlqConsumer(
    private val recordTradeUseCase: RecordTradeUseCase,
    private val kafkaDlqProducer: KafkaDlqProducer,
    private val metrics: BrokerMetrics,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = [KafkaTopicConfig.DLQ_TRADE, KafkaTopicConfig.DLQ_FETCH],
        groupId = "allfolio-dlq",
        concurrency = "3",
    )
    fun consume(record: ConsumerRecord<String, String>, ack: Acknowledgment) {
        val event = runCatching {
            objectMapper.readValue(record.value(), FailedTradeEvent::class.java)
        }.getOrElse { e ->
            log.error("[KafkaDLQ] deserialize failed topic={} offset={}", record.topic(), record.offset(), e)
            ack.acknowledge()  // poison pill → ack to skip
            return
        }

        when (event.payloadType) {
            FailedTradeEvent.TYPE_TRADE_COMMAND -> processTradeCommand(event, record.topic())
            FailedTradeEvent.TYPE_FETCH_PARAMS  -> processFetchParams(event)
            else -> {
                log.warn("[KafkaDLQ] unknown type={} id={}", event.payloadType, event.id)
                kafkaDlqProducer.sendDead(event)
            }
        }

        ack.acknowledge()
    }

    private fun processTradeCommand(event: FailedTradeEvent, @Suppress("UNUSED_PARAMETER") sourceTopic: String) {
        val command = runCatching {
            objectMapper.readValue(event.payload, RecordTradeCommand::class.java)
        }.getOrElse { e ->
            log.error("[KafkaDLQ] TRADE_COMMAND deserialize failed id={}", event.id, e)
            kafkaDlqProducer.sendDead(event)
            metrics.dlqDeadLettered(event.brokerType)
            return
        }

        metrics.dlqRetried(event.brokerType, event.payloadType)

        runCatching {
            recordTradeUseCase.record(command)
            log.info("[KafkaDLQ] TRADE_COMMAND retry success id={} extId={}", event.id, command.externalTradeId)
        }.onFailure { e ->
            when (e) {
                is DataIntegrityViolationException -> {
                    log.debug("[KafkaDLQ] TRADE_COMMAND dedup discard id={} extId={}", event.id, command.externalTradeId)
                }
                else -> {
                    val retried = event.copy(
                        retryCount   = event.retryCount + 1,
                        errorMessage = e.message ?: "kafka retry failed",
                    )
                    if (retried.retryCount >= DlqService.MAX_RETRIES) {
                        log.error("[KafkaDLQ] TRADE_COMMAND max retries exceeded id={} extId={}",
                            event.id, command.externalTradeId)
                        kafkaDlqProducer.sendDead(retried)
                        metrics.dlqDeadLettered(event.brokerType)
                    } else {
                        log.warn("[KafkaDLQ] TRADE_COMMAND re-enqueue retry={} id={}", retried.retryCount, event.id)
                        kafkaDlqProducer.send(retried)
                    }
                }
            }
        }
    }

    private fun processFetchParams(event: FailedTradeEvent) {
        // BrokerSyncScheduler가 cursor 미진행으로 자연 재시도 — Kafka consumer는 log만
        metrics.dlqRetried(event.brokerType, event.payloadType)
        val retried = event.copy(retryCount = event.retryCount + 1)
        if (retried.retryCount >= DlqService.MAX_RETRIES) {
            kafkaDlqProducer.sendDead(retried)
            metrics.dlqDeadLettered(event.brokerType)
            log.error("[KafkaDLQ] FETCH_PARAMS dead-letter broker={} account={}", event.brokerType, event.accountNo)
        } else {
            log.warn("[KafkaDLQ] FETCH_PARAMS audit retry={} broker={} — scheduler retries naturally", retried.retryCount, event.brokerType)
        }
    }
}
