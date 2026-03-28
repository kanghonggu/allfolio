package com.allfolio.dlq

import com.allfolio.broker.BrokerType
import com.allfolio.metrics.BrokerMetrics
import com.allfolio.trade.application.RecordTradeCommand
import com.allfolio.trade.application.RecordTradeUseCase
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Redis DLQ 재처리 Worker (5초 간격, 브로커별 최대 50건)
 *
 * Kafka DLQ와 독립적으로 동작 — Redis DLQ만 처리
 * KafkaDlqConsumer는 Kafka DLQ를 별도 처리
 *
 * 무한루프 방지:
 *   retryCount >= MAX_RETRIES(5) → pushDead() → dlq:dead:{broker}
 */
@Component
class DlqWorker(
    private val dlqService: DlqService,
    private val recordTradeUseCase: RecordTradeUseCase,
    private val metrics: BrokerMetrics,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 5_000)
    fun retryDlq() {
        BrokerType.entries.forEach { brokerType ->
            var processed = 0
            repeat(BATCH_SIZE) {
                val event = dlqService.pop(brokerType) ?: return@repeat

                when (event.payloadType) {
                    FailedTradeEvent.TYPE_TRADE_COMMAND -> retryTradeCommand(event)
                    FailedTradeEvent.TYPE_FETCH_PARAMS  -> retryFetchParams(event)
                    else -> {
                        log.warn("[DLQ] unknown payloadType={} id={}", event.payloadType, event.id)
                        dlqService.pushDead(event)
                    }
                }
                processed++
            }
            if (processed > 0) log.info("[DLQ] worker processed={} broker={}", processed, brokerType)
        }
    }

    private fun retryTradeCommand(event: FailedTradeEvent) {
        val command = runCatching {
            objectMapper.readValue(event.payload, RecordTradeCommand::class.java)
        }.getOrElse { e ->
            log.error("[DLQ] deserialize failed id={}", event.id, e)
            dlqService.pushDead(event)
            metrics.dlqDeadLettered(event.brokerType)
            return
        }

        metrics.dlqRetried(event.brokerType, event.payloadType)

        runCatching {
            recordTradeUseCase.record(command)
            log.info("[DLQ] TRADE_COMMAND retry success id={} extId={}", event.id, command.externalTradeId)
        }.onFailure { e ->
            when (e) {
                is DataIntegrityViolationException ->
                    log.debug("[DLQ] TRADE_COMMAND dedup discard id={} extId={}", event.id, command.externalTradeId)
                else -> {
                    val retried = event.copy(retryCount = event.retryCount + 1, errorMessage = e.message ?: "retry failed")
                    if (retried.retryCount >= DlqService.MAX_RETRIES) {
                        log.error("[DLQ] TRADE_COMMAND max retries exceeded id={}", event.id)
                        dlqService.pushDead(retried)
                        metrics.dlqDeadLettered(event.brokerType)
                    } else {
                        log.warn("[DLQ] TRADE_COMMAND re-enqueue retry={} id={}", retried.retryCount, event.id)
                        dlqService.push(retried)
                    }
                }
            }
        }
    }

    private fun retryFetchParams(event: FailedTradeEvent) {
        metrics.dlqRetried(event.brokerType, event.payloadType)
        val retried = event.copy(retryCount = event.retryCount + 1)
        if (retried.retryCount >= DlqService.MAX_RETRIES) {
            log.error("[DLQ] FETCH_PARAMS max retries exceeded broker={} account={}", event.brokerType, event.accountNo)
            dlqService.pushDead(retried)
            metrics.dlqDeadLettered(event.brokerType)
        } else {
            log.warn("[DLQ] FETCH_PARAMS audit retry={} broker={}", retried.retryCount, event.brokerType)
            dlqService.push(retried)
        }
    }

    companion object {
        private const val BATCH_SIZE = 50
    }
}
