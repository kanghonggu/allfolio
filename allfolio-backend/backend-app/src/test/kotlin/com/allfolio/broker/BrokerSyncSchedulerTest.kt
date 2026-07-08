package com.allfolio.broker

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class BrokerSyncSchedulerTest {

    private val executor: ExecutorService = Executors.newFixedThreadPool(BrokerType.entries.size)

    @AfterEach
    fun tearDown() {
        executor.shutdownNow()
    }

    private fun state(brokerType: String, accountId: String, lastSyncedAt: LocalDateTime? = null) =
        BrokerSyncStateEntity(
            id = BrokerSyncStateId(UUID.randomUUID(), brokerType, accountId),
            lastSyncedAt = lastSyncedAt,
        )

    private fun scheduler(syncer: BrokerAccountSyncer, states: List<BrokerSyncStateEntity>): BrokerSyncScheduler {
        val repo = Mockito.mock(BrokerSyncStateRepository::class.java)
        Mockito.`when`(repo.findAll()).thenReturn(states)
        return BrokerSyncScheduler(syncer, repo, executor)
    }

    @Test
    fun `같은 브로커의 계좌들은 순차 실행된다`() {
        val concurrent    = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val syncer = object : BrokerAccountSyncer {
            override fun syncAccount(brokerType: BrokerType, portfolioId: UUID, accountId: String): Int {
                val now = concurrent.incrementAndGet()
                maxConcurrent.updateAndGet { maxOf(it, now) }
                Thread.sleep(50)
                concurrent.decrementAndGet()
                return 0
            }
        }

        scheduler(syncer, listOf(state("KIS", "a1"), state("KIS", "a2"), state("KIS", "a3"))).syncAll()

        assertEquals(1, maxConcurrent.get(), "같은 브로커 계좌가 동시에 실행되면 안 된다")
    }

    @Test
    fun `한 계좌의 실패가 같은 그룹의 다음 계좌와 다른 브로커를 막지 않는다`() {
        val calls = ConcurrentHashMap.newKeySet<String>()
        val syncer = object : BrokerAccountSyncer {
            override fun syncAccount(brokerType: BrokerType, portfolioId: UUID, accountId: String): Int {
                calls.add("${brokerType.name}:$accountId")
                if (brokerType == BrokerType.KIS && accountId == "a1") throw IllegalStateException("boom")
                return 0
            }
        }

        scheduler(syncer, listOf(state("KIS", "a1"), state("KIS", "a2"), state("TOSS", "b1"))).syncAll()

        assertTrue(
            calls.containsAll(setOf("KIS:a1", "KIS:a2", "TOSS:b1")),
            "실패 이후 계좌들도 전부 호출되어야 한다. 실제 호출: $calls",
        )
    }

    @Test
    fun `BINANCE와 unknown type과 최근 동기화 계좌는 제외된다`() {
        val calls = ConcurrentHashMap.newKeySet<String>()
        val syncer = object : BrokerAccountSyncer {
            override fun syncAccount(brokerType: BrokerType, portfolioId: UUID, accountId: String): Int {
                calls.add("${brokerType.name}:$accountId")
                return 0
            }
        }

        scheduler(
            syncer,
            listOf(
                state("BINANCE", "legacy"),
                state("NOT_A_BROKER", "x1"),
                state("KIS", "recent", lastSyncedAt = LocalDateTime.now()),
                state("KIS", "due", lastSyncedAt = LocalDateTime.now().minusMinutes(5)),
                state("TOSS", "never"),
            ),
        ).syncAll()

        assertEquals(setOf("KIS:due", "TOSS:never"), calls)
    }
}
