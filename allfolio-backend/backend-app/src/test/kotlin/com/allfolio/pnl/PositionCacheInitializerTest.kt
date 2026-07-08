package com.allfolio.pnl

import com.allfolio.broker.BrokerSyncStateRepository
import com.allfolio.trade.infrastructure.repository.TradeRawJpaRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.autoconfigure.data.redis.RedisProperties
import org.springframework.data.redis.RedisConnectionFailureException

class PositionCacheInitializerTest {

    private val tradeRepository = mock(TradeRawJpaRepository::class.java)
    private val syncStateRepository = mock(BrokerSyncStateRepository::class.java)
    private val positionCacheService = mock(PositionCacheService::class.java)

    private fun initializer(maxAttempts: Int = 3) = PositionCacheInitializer(
        tradeRepository = tradeRepository,
        syncStateRepository = syncStateRepository,
        positionCacheService = positionCacheService,
        redisProperties = RedisProperties(),
        redisMaxAttempts = maxAttempts,
        redisRetryInitialDelayMs = 1,
    )

    @Test
    fun `proceeds immediately when redis is available`() {
        `when`(syncStateRepository.findAll()).thenReturn(emptyList())

        initializer().run(DefaultApplicationArguments())

        verify(positionCacheService, times(1)).ping()
        verify(syncStateRepository).findAll()
    }

    @Test
    fun `retries with backoff until redis becomes available`() {
        doThrow(RedisConnectionFailureException("Unable to connect to Redis"))
            .doThrow(RedisConnectionFailureException("Unable to connect to Redis"))
            .doNothing()
            .`when`(positionCacheService).ping()
        `when`(syncStateRepository.findAll()).thenReturn(emptyList())

        initializer().run(DefaultApplicationArguments())

        verify(positionCacheService, times(3)).ping()
        verify(syncStateRepository).findAll()
    }

    @Test
    fun `gives up after max attempts and skips initialization`() {
        doThrow(RedisConnectionFailureException("Unable to connect to Redis"))
            .`when`(positionCacheService).ping()

        initializer(maxAttempts = 3).run(DefaultApplicationArguments())

        verify(positionCacheService, times(3)).ping()
        verify(syncStateRepository, never()).findAll()
    }
}
