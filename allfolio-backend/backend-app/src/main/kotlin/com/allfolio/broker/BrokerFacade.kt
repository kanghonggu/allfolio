package com.allfolio.broker

import com.allfolio.dlq.DlqService
import com.allfolio.dlq.FailedTradeEvent
import com.allfolio.metrics.BrokerMetrics
import com.allfolio.pnl.PositionCacheService
import com.allfolio.trade.application.RecordTradeUseCase
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.UUID

/**
 * 브로커 어댑터 통합 Facade
 *
 * @Transactional 없음:
 *   각 record() → 독립 TX → dedup exception이 다른 trade TX에 전파되지 않음
 *
 * DLQ:
 *   비-dedup record() 실패 → TRADE_COMMAND DLQ (Redis + Kafka)
 *
 * Metrics (non-blocking):
 *   trade.process.count, trade.process.latency
 *
 * PositionCache:
 *   record() 성공 후 → Redis Hash 업데이트 (PnL 실시간 계산용)
 *   write path 아님, broker sync 경로에서만 실행
 */
@Component
class BrokerFacade(
    adapters: List<BrokerAdapter>,
    private val syncStateRepository: BrokerSyncStateRepository,
    private val recordTradeUseCase: RecordTradeUseCase,
    private val rateLimiter: BrokerRateLimiter,
    private val dlqService: DlqService,
    private val metrics: BrokerMetrics,
    private val positionCacheService: PositionCacheService,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val adapterMap: Map<BrokerType, BrokerAdapter> =
        adapters.associateBy { it.brokerType }

    init {
        log.info("[BrokerFacade] registered adapters: {}", adapterMap.keys)
    }

    /**
     * 단일 계좌 증분 동기화
     * @return 저장 건수. -1 = rate limited
     */
    fun syncAccount(
        brokerType: BrokerType,
        portfolioId: UUID,
        accountId: String,
    ): Int {
        val adapter = adapterMap[brokerType] ?: throw IllegalStateException("No adapter for $brokerType")

        if (!rateLimiter.tryAcquire(brokerType, "${brokerType}:$portfolioId")) {
            log.debug("[BrokerFacade] rate limited broker={} portfolio={}", brokerType, portfolioId)
            return -1
        }

        val stateId = BrokerSyncStateId(portfolioId, brokerType.name, accountId)
        val state   = syncStateRepository.findById(stateId).orElse(BrokerSyncStateEntity(id = stateId))

        val result = adapter.fetchTrades(portfolioId, accountId, state.cursorValue)
        if (result.commands.isEmpty()) return 0

        var recorded = 0
        result.commands.forEach { command ->
            runCatching {
                metrics.recordTradeLatency(brokerType.name) {
                    recordTradeUseCase.record(command)
                }
                recorded++
                metrics.tradeRecorded(brokerType.name)

                // Position cache 업데이트 (PnL 실시간 계산용) — Redis O(1), non-blocking
                runCatching {
                    positionCacheService.applyTrade(
                        portfolioId = command.portfolioId,
                        assetId     = command.assetId,
                        tradeType   = command.tradeType,
                        quantity    = command.quantity,
                        price       = command.price,
                        currency    = command.tradeCurrency,
                    )
                }.onFailure { e ->
                    log.warn("[BrokerFacade] positionCache update failed extId={}: {}", command.externalTradeId, e.message)
                }
            }.onFailure { e ->
                when (e) {
                    is org.springframework.dao.DataIntegrityViolationException -> {
                        metrics.tradeDuplicateSkipped(brokerType.name)
                        log.debug("[BrokerFacade] dedup skip extId={}", command.externalTradeId)
                    }
                    else -> {
                        metrics.tradeFailedToDlq(brokerType.name)
                        log.error("[BrokerFacade] record failed → DLQ broker={} extId={}", brokerType, command.externalTradeId, e)
                        dlqService.push(
                            FailedTradeEvent(
                                brokerType   = brokerType.name,
                                accountNo    = accountId,
                                payloadType  = FailedTradeEvent.TYPE_TRADE_COMMAND,
                                payload      = runCatching { objectMapper.writeValueAsString(command) }.getOrDefault("{}"),
                                errorMessage = e.message ?: "record failed",
                            )
                        )
                    }
                }
            }
        }

        if (recorded > 0 || result.nextCursor != state.cursorValue) {
            state.cursorValue  = result.nextCursor
            state.syncedCount += recorded
            state.lastSyncedAt = LocalDateTime.now()
            syncStateRepository.save(state)
        }

        log.info("[BrokerFacade] synced broker={} portfolio={} recorded={}/{}", brokerType, portfolioId, recorded, result.commands.size)
        return recorded
    }

    fun getAdapter(brokerType: BrokerType): BrokerAdapter =
        adapterMap[brokerType] ?: throw IllegalStateException("No adapter for $brokerType")

    fun registeredBrokers(): Set<BrokerType> = adapterMap.keys
}
