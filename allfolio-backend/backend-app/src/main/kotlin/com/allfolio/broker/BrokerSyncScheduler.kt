package com.allfolio.broker

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService

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
    private val accountSyncer: BrokerAccountSyncer,
    private val syncStateRepository: BrokerSyncStateRepository,
    private val brokerSyncExecutor: ExecutorService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 60_000)
    fun syncAll() {
        val states = syncStateRepository.findAll()
        if (states.isEmpty()) return

        val threshold = LocalDateTime.now().minusSeconds(SKIP_THRESHOLD_SECONDS)
        var skipped = 0

        val eligible: List<Pair<BrokerType, BrokerSyncStateEntity>> = states.mapNotNull { state ->
            val brokerType = runCatching {
                BrokerType.valueOf(state.id.brokerType)
            }.getOrElse {
                log.warn("[BrokerSyncScheduler] unknown brokerType={}", state.id.brokerType)
                return@mapNotNull null
            }

            // BINANCE는 BinanceSyncService(레거시 경로)가 처리
            if (brokerType == BrokerType.BINANCE) return@mapNotNull null

            // lastSyncedAt 30s 이내 skip — 과부하 방지
            val lastSynced = state.lastSyncedAt
            if (lastSynced != null && lastSynced.isAfter(threshold)) {
                skipped++
                return@mapNotNull null
            }

            brokerType to state
        }

        if (skipped > 0) log.debug("[BrokerSyncScheduler] skipped {} recently-synced accounts", skipped)
        if (eligible.isEmpty()) return

        // 브로커 간 병렬, 브로커 내 순차 — invokeAll join으로 fixedDelay 무겹침 보장
        val groups = eligible.groupBy({ it.first }, { it.second }).toList()
        val tasks = groups.map { (brokerType, group) ->
            Callable { group.forEach { state -> syncOne(brokerType, state) } }
        }
        val futures = try {
            brokerSyncExecutor.invokeAll(tasks)
        } catch (e: InterruptedException) {
            // 셧다운 등으로 group 완료 대기 중 인터럽트 — 인터럽트 상태 복원 후 이번 틱 중단
            Thread.currentThread().interrupt()
            log.warn("[BrokerSyncScheduler] interrupted while awaiting broker sync groups", e)
            return
        }
        futures.forEachIndexed { index, future ->
            runCatching { future.get() }.onFailure { e ->
                log.error("[BrokerSyncScheduler] group task failed broker={}", groups[index].first, e)
            }
        }
    }

    private fun syncOne(brokerType: BrokerType, state: BrokerSyncStateEntity) {
        runCatching {
            val recorded = accountSyncer.syncAccount(
                brokerType  = brokerType,
                portfolioId = state.id.portfolioId,
                accountId   = state.id.accountId,
            )
            when {
                recorded > 0   -> log.info("[BrokerSyncScheduler] broker={} account={} recorded={}",
                    brokerType, state.id.accountId, recorded)
                recorded == -1 -> log.debug("[BrokerSyncScheduler] rate limited broker={} account={}",
                    brokerType, state.id.accountId)
            }
        }.onFailure { e ->
            log.error("[BrokerSyncScheduler] sync failed broker={} account={}",
                brokerType, state.id.accountId, e)
        }
    }

    companion object {
        private const val SKIP_THRESHOLD_SECONDS = 30L
    }
}
