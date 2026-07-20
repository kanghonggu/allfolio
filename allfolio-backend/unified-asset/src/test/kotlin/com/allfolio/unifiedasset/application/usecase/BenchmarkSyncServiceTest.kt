package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.BenchmarkDailyStore
import com.allfolio.unifiedasset.application.port.BenchmarkHistoryClient
import com.allfolio.unifiedasset.domain.benchmark.BenchmarkType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class BenchmarkSyncServiceTest {

    private class FakeClient(private val rows: List<Pair<LocalDate, BigDecimal>>) : BenchmarkHistoryClient {
        val requestedRanges = mutableListOf<Pair<BenchmarkType, String>>()
        override fun dailyHistory(type: BenchmarkType, range: String): List<Pair<LocalDate, BigDecimal>> {
            requestedRanges.add(type to range)
            return rows
        }
    }

    private class FakeStore(private val latest: LocalDate?) : BenchmarkDailyStore {
        val upserted = mutableListOf<Pair<BenchmarkType, Int>>()
        override fun latestDate(type: BenchmarkType) = latest
        override fun upsert(type: BenchmarkType, rows: List<Pair<LocalDate, BigDecimal>>) {
            upserted.add(type to rows.size)
        }
        override fun series(type: BenchmarkType, from: LocalDate, to: LocalDate) = emptyList<Pair<LocalDate, BigDecimal>>()
    }

    private val sampleRows = listOf(
        LocalDate.of(2026, 7, 17) to BigDecimal("6300.5"),
        LocalDate.of(2026, 7, 18) to BigDecimal("6310.1"),
    )

    @Test
    fun `empty store backfills one year`() {
        val client = FakeClient(sampleRows)
        val store = FakeStore(latest = null)
        BenchmarkSyncService(client, store).syncAll()

        assertTrue(client.requestedRanges.all { it.second == "1y" })
        assertEquals(BenchmarkType.entries.size, store.upserted.size)
    }

    @Test
    fun `recent store fetches one month`() {
        val client = FakeClient(sampleRows)
        val store = FakeStore(latest = LocalDate.now().minusDays(1))
        BenchmarkSyncService(client, store).syncAll()

        assertTrue(client.requestedRanges.all { it.second == "1mo" })
    }

    @Test
    fun `empty response skips upsert`() {
        val client = FakeClient(emptyList())
        val store = FakeStore(latest = null)
        BenchmarkSyncService(client, store).syncAll()

        assertTrue(store.upserted.isEmpty())
    }
}
