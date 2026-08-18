package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.BenchmarkDailyStore
import com.allfolio.unifiedasset.application.port.BenchmarkHistoryClient
import com.allfolio.unifiedasset.domain.benchmark.BenchmarkType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class BenchmarkSyncServiceTest {

    private class FakeClient(private val rows: List<Pair<LocalDate, BigDecimal>>) : BenchmarkHistoryClient {
        val requestedRanges = mutableListOf<Pair<BenchmarkType, String>>()

        /** 어떤 지수를 Yahoo에 물어봤나. 범위와 무관하게 "물어봤다"만 보는 단언에 쓴다 */
        val requested: List<BenchmarkType> get() = requestedRanges.map { it.first }

        override fun dailyHistory(type: BenchmarkType, range: String): List<Pair<LocalDate, BigDecimal>> {
            requestedRanges.add(type to range)
            return rows
        }
    }

    private class FakeStore(private val latest: LocalDate?) : BenchmarkDailyStore {
        val upserted = mutableListOf<Pair<BenchmarkType, Int>>()

        /** 어떤 지수가 저장됐나. 클라이언트 호출과 별개로 확인해야 한다 — 안 물어봤는데 쓰는 길이 생기면 잡는다 */
        val upsertedTypes: List<BenchmarkType> get() = upserted.map { it.first }

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
        // 셋이 아니라 둘이다 — KOSPI는 FSC가 채운다. entries.size로 세면 아래 두 테스트가
        // 지키는 규칙을 이 단언이 조용히 뒤집는다
        assertThat(store.upsertedTypes).containsExactlyInAnyOrder(BenchmarkType.SPX, BenchmarkType.BTC)
    }

    /**
     * KOSPI는 FSC 수집기(AF-107)가 채운다. Yahoo가 같이 쓰면 두 소스가 같은 (KOSPI, date) 행을
     * 번갈아 덮어써 값이 실행마다 흔들린다 — 오류도 로그도 안 난다. 그래서 여기서 막는다.
     */
    @Test
    fun `KOSPI는 Yahoo에서 받지 않는다`() {
        val client = FakeClient(sampleRows)
        val store = FakeStore(latest = null)
        BenchmarkSyncService(client, store).syncAll()

        assertThat(client.requested).doesNotContain(BenchmarkType.KOSPI)
        assertThat(store.upsertedTypes).doesNotContain(BenchmarkType.KOSPI)
    }

    /** 이관 범위는 KOSPI 하나다. SPX·BTC까지 끊기면 그 둘의 시계열이 오늘부터 멈춘다 */
    @Test
    fun `SPX와 BTC는 그대로 받는다`() {
        val client = FakeClient(sampleRows)
        BenchmarkSyncService(client, FakeStore(latest = null)).syncAll()

        assertThat(client.requested).containsExactlyInAnyOrder(BenchmarkType.SPX, BenchmarkType.BTC)
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
