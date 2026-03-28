package com.allfolio.broker

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * 멀티 브로커 통합 Scheduler
 *
 * 보호 장치:
 * 1. BINANCE skip — BinanceSyncService(레거시)가 처리
 * 2. lastSyncedAt < 30s skip — 과도한 재실행 방지
 * 3. rate limited skip (-1) — BrokerFacade가 non-blocking 반환
 * 4. 각 계좌 오류 격리 — 한 계좌 실패가 다른 계좌 sync에 영향 없음
 *
 * broker_sync_state에 행이 있어야 동기화 실행.
 * 행 삽입은 OAuth2 callback / TossOAuthController가 처리.
 */
@Component
class BrokerSyncScheduler(
    private val brokerFacade: BrokerFacade,
    private val syncStateRepository: BrokerSyncStateRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 60_000)
    fun syncAll() {
        val states = syncStateRepository.findAll()
        if (states.isEmpty()) return

        val threshold = LocalDateTime.now().minusSeconds(SKIP_THRESHOLD_SECONDS)
        var skipped = 0

        states.forEach { state ->
            val brokerType = runCatching {
                BrokerType.valueOf(state.id.brokerType)
            }.getOrElse {
                log.warn("[BrokerSyncScheduler] unknown brokerType={}", state.id.brokerType)
                return@forEach
            }

            // BINANCE는 BinanceSyncService(레거시 경로)가 처리
            if (brokerType == BrokerType.BINANCE) return@forEach

            // lastSyncedAt 30s 이내 skip — 과부하 방지
            val lastSynced = state.lastSyncedAt
            if (lastSynced != null && lastSynced.isAfter(threshold)) {
                skipped++
                log.debug("[BrokerSyncScheduler] skip (recent sync {}s ago) broker={} account={}",
                    java.time.Duration.between(lastSynced, LocalDateTime.now()).seconds,
                    brokerType, state.id.accountId)
                return@forEach
            }

            runCatching {
                val recorded = brokerFacade.syncAccount(
                    brokerType  = brokerType,
                    portfolioId = state.id.portfolioId,
                    accountId   = state.id.accountId,
                )
                when {
                    recorded > 0  -> log.info("[BrokerSyncScheduler] broker={} account={} recorded={}",
                        brokerType, state.id.accountId, recorded)
                    recorded == -1 -> log.debug("[BrokerSyncScheduler] rate limited broker={} account={}",
                        brokerType, state.id.accountId)
                }
            }.onFailure { e ->
                log.error("[BrokerSyncScheduler] sync failed broker={} account={}",
                    brokerType, state.id.accountId, e)
            }
        }

        if (skipped > 0) log.debug("[BrokerSyncScheduler] skipped {} recently-synced accounts", skipped)
    }

    companion object {
        private const val SKIP_THRESHOLD_SECONDS = 30L
    }
}
