package com.allfolio.broker.kiwoom

import com.allfolio.broker.BrokerAuthEntity
import com.allfolio.broker.BrokerAuthRepository
import com.allfolio.broker.BrokerType
import com.allfolio.common.crypto.LegacyPlaintextDetectedException
import com.allfolio.common.crypto.SENSITIVE_DATA_RECONNECTION_REQUIRED_MESSAGE
import com.allfolio.metrics.BrokerMetrics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.RedisTemplate
import java.util.UUID

class KiwoomApiClientSensitiveDataTest {

    @Test
    fun `legacy plaintext broker token asks user to reconnect`() {
        val userId = UUID.randomUUID()
        val brokerAuthRepository = mock(BrokerAuthRepository::class.java)
        @Suppress("UNCHECKED_CAST")
        val redisTemplate = mock(RedisTemplate::class.java) as RedisTemplate<String, Any>
        `when`(redisTemplate.opsForValue()).thenThrow(RuntimeException("redis unavailable"))
        `when`(brokerAuthRepository.findByUserIdAndBrokerType(userId, BrokerType.KIWOOM))
            .thenThrow(LegacyPlaintextDetectedException("legacy plaintext"))

        val client = KiwoomApiClient(
            kiwoomProperties = KiwoomProperties(),
            brokerAuthRepository = brokerAuthRepository,
            redisTemplate = redisTemplate,
            metrics = mock(BrokerMetrics::class.java),
        )

        val ex = assertThrows<KiwoomApiException> {
            client.resolveAccessToken(userId)
        }

        assertEquals(SENSITIVE_DATA_RECONNECTION_REQUIRED_MESSAGE, ex.message)
    }

    @Test
    fun `saveAuth replaces existing row without reading old encrypted token`() {
        val userId = UUID.randomUUID()
        val brokerAuthRepository = mock(BrokerAuthRepository::class.java)
        @Suppress("UNCHECKED_CAST")
        val redisTemplate = mock(RedisTemplate::class.java) as RedisTemplate<String, Any>

        val client = KiwoomApiClient(
            kiwoomProperties = KiwoomProperties(),
            brokerAuthRepository = brokerAuthRepository,
            redisTemplate = redisTemplate,
            metrics = mock(BrokerMetrics::class.java),
        )

        client.saveAuth(
            userId,
            KiwoomTokenResponse(
                accessToken = "new-token",
                tokenType = "Bearer",
                expiresIn = 3600,
            ),
        )

        verify(brokerAuthRepository).deleteByUserIdAndBrokerType(userId, BrokerType.KIWOOM)
        verify(brokerAuthRepository, never()).findByUserIdAndBrokerType(userId, BrokerType.KIWOOM)
        verify(brokerAuthRepository).save(any(BrokerAuthEntity::class.java))
    }
}
