package com.allfolio.pnl

import com.allfolio.broker.BrokerSyncStateEntity
import com.allfolio.broker.BrokerSyncStateId
import com.allfolio.broker.BrokerSyncStateRepository
import com.allfolio.trade.domain.TradeType
import com.allfolio.trade.infrastructure.entity.TradeRawEntity
import com.allfolio.trade.infrastructure.repository.TradeRawJpaRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.autoconfigure.data.redis.RedisProperties
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * 재부팅 회귀 가드 — 초기화기를 실제로 구동한다.
 * initPortfolio가 lots를 채운 PositionData를 만들어야 재부팅 후 costMethod=FIFO가 정확하다.
 * 옛 lot-less 초기화기로 되돌리면 이 테스트는 실패한다.
 */
class PositionCacheRebuildRegressionTest {

    private val tradeRepository = mock(TradeRawJpaRepository::class.java)
    private val syncStateRepository = mock(BrokerSyncStateRepository::class.java)
    private val positionCacheService = mock(PositionCacheService::class.java)

    private val portfolioId = UUID.randomUUID()
    private val assetA = UUID.randomUUID()
    private val assetB = UUID.randomUUID()

    private var seq = 0L
    private fun entity(assetId: UUID, type: TradeType, qty: String, price: String) = TradeRawEntity(
        id = UUID.randomUUID(),
        portfolioId = portfolioId,
        assetId = assetId,
        tradeType = type,
        quantity = BigDecimal(qty),
        price = BigDecimal(price),
        fee = BigDecimal.ZERO,
        tradeCurrency = "KRW",
        executedAt = LocalDateTime.now().plusSeconds(seq++),
        createdAt = LocalDateTime.now(),
    )

    @Test
    fun `initializer rebuilds cache with non-empty FIFO lots`() {
        `when`(syncStateRepository.findAll())
            .thenReturn(listOf(BrokerSyncStateEntity(id = BrokerSyncStateId(portfolioId, "KIS", "acct-1"))))

        // assetA: BUY 10@100, BUY 10@200, SELL 5@300 → 잔여 lots 5@100 + 10@200
        // assetB: BUY 3@50                            → lot 3@50
        val trades = listOf(
            entity(assetA, TradeType.BUY, "10", "100"),
            entity(assetA, TradeType.BUY, "10", "200"),
            entity(assetA, TradeType.SELL, "5", "300"),
            entity(assetB, TradeType.BUY, "3", "50"),
        )
        `when`(
            tradeRepository.findByPortfolioIdAndExecutedAtLessThanEqualOrderByExecutedAtAsc(
                eq(portfolioId),
                anyDateTime(),
            ),
        ).thenReturn(trades)

        var captured: Map<UUID, PositionData> = emptyMap()
        doAnswer { inv ->
            @Suppress("UNCHECKED_CAST")
            captured = inv.getArgument(1) as Map<UUID, PositionData>
            null
        }.`when`(positionCacheService).initPositions(eq(portfolioId), anyPositionMap())

        PositionCacheInitializer(
            tradeRepository = tradeRepository,
            syncStateRepository = syncStateRepository,
            positionCacheService = positionCacheService,
            redisProperties = RedisProperties(),
            redisMaxAttempts = 3,
            redisRetryInitialDelayMs = 1,
        ).run(DefaultApplicationArguments())

        assertEquals(setOf(assetA, assetB), captured.keys)

        val a = captured.getValue(assetA)
        assertTrue(a.lots.isNotEmpty(), "assetA 포지션에 lots가 없다 — 재부팅 시 FIFO 원가가 깨진다")
        assertEquals(2, a.lots.size)
        assertEquals(0, BigDecimal("15").compareTo(a.quantity))
        assertEquals(0, BigDecimal("100").compareTo(a.lots[0].price))
        assertEquals(0, BigDecimal("5").compareTo(a.lots[0].quantity))
        assertEquals(0, BigDecimal("200").compareTo(a.lots[1].price))
        assertEquals(0, BigDecimal("10").compareTo(a.lots[1].quantity))

        val b = captured.getValue(assetB)
        assertTrue(b.lots.isNotEmpty())
        assertEquals(0, BigDecimal("3").compareTo(b.quantity))
        assertEquals(0, BigDecimal("50").compareTo(b.lots[0].price))
    }

    // Mockito any()/eq() 헬퍼 (Kotlin null safety 우회).
    // Kotlin은 non-null 파라미터 위치에서 제네릭 호출 결과를 사용하는 콜사이트에 암묵적
    // null 체크를 삽입하므로, ArgumentMatchers가 내부적으로 반환하는 null이 그대로 노출되면
    // NPE("eq(...) must not be null")가 터진다. eq()는 실제 값으로 폴백해 null 노출을 막고,
    // any()는 실제 실행 시점 값(LocalDateTime.now())으로 폴백해 동일 문제를 피한다.
    private fun <T> eq(value: T): T {
        org.mockito.ArgumentMatchers.eq(value)
        return value
    }

    private fun anyDateTime(): LocalDateTime {
        org.mockito.ArgumentMatchers.any(LocalDateTime::class.java)
        return LocalDateTime.now()
    }

    private fun anyPositionMap(): Map<UUID, PositionData> {
        org.mockito.ArgumentMatchers.anyMap<UUID, PositionData>()
        return emptyMap()
    }
}
