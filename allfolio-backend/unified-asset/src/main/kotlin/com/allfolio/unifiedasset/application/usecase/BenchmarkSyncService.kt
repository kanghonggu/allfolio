package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.BenchmarkDailyStore
import com.allfolio.unifiedasset.application.port.BenchmarkHistoryClient
import com.allfolio.unifiedasset.domain.benchmark.BenchmarkType
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * 벤치마크 지수 일별 종가 수집 (R1 #35).
 * 기동 시 + 매일 01:10 — 저장분이 30일 이상 비면 1y 백필, 아니면 1mo 증분. UPSERT 멱등.
 *
 * **KOSPI는 여기서 안 받는다** — 공공데이터포털 수집기(AF-107)가 채운다.
 * [BenchmarkType.syncedFromYahoo] 참조.
 */
@Service
class BenchmarkSyncService(
    private val historyClient: BenchmarkHistoryClient,
    private val store: BenchmarkDailyStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun onStartup() {
        runCatching { syncAll() }
            .onFailure { log.warn("benchmark startup sync failed: {}", it.message) }
    }

    @Scheduled(cron = "0 10 1 * * *")
    fun daily() = syncAll()

    fun syncAll() {
        BenchmarkType.entries.filter { it.syncedFromYahoo }.forEach { type ->
            runCatching {
                val latest = store.latestDate(type)
                val range = if (latest == null || latest.isBefore(LocalDate.now().minusDays(30))) "1y" else "1mo"
                val rows = historyClient.dailyHistory(type, range)
                if (rows.isEmpty()) {
                    log.warn("benchmark sync: no data for {}", type)
                    return@forEach
                }
                store.upsert(type, rows)
                log.info("benchmark sync: {} +{} rows (range={})", type, rows.size, range)
            }.onFailure { log.warn("benchmark sync failed for {}: {}", type, it.message) }
        }
    }
}
