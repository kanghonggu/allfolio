package com.allfolio.pnl

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID

class PositionCacheServiceEvictTest {

    @Test
    fun `evictPortfolio deletes the whole position hash key`() {
        val redis = mock(StringRedisTemplate::class.java)
        val service = PositionCacheService(redis, mock(ObjectMapper::class.java))
        val portfolioId = UUID.randomUUID()

        service.evictPortfolio(portfolioId)

        verify(redis).delete("pnl:positions:$portfolioId")
    }
}
