package com.allfolio.lock

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration
import java.util.UUID

class UserReconSyncMutexTest {

    @Suppress("UNCHECKED_CAST")
    private fun mutex(acquired: Boolean?, throwOnAcquire: Boolean = false): UserReconSyncMutex {
        val ops = mock(ValueOperations::class.java) as ValueOperations<String, String>
        val template = mock(StringRedisTemplate::class.java)
        `when`(template.opsForValue()).thenReturn(ops)
        val stub = `when`(ops.setIfAbsent(anyString() ?: "", anyString() ?: "", any(Duration::class.java) ?: Duration.ZERO))
        if (throwOnAcquire) stub.thenThrow(RuntimeException("redis down")) else stub.thenReturn(acquired)
        return UserReconSyncMutex(template)
    }

    @Test
    fun `잠겨있지 않으면 토큰을 반환한다`() {
        assertNotNull(mutex(acquired = true).tryAcquire(UUID.randomUUID()))
    }

    @Test
    fun `이미 잠겨있으면 null`() {
        assertNull(mutex(acquired = false).tryAcquire(UUID.randomUUID()))
    }

    @Test
    fun `Redis 장애 시 안전 우선 - null(실행 거부)`() {
        assertNull(mutex(acquired = null, throwOnAcquire = true).tryAcquire(UUID.randomUUID()))
    }
}
