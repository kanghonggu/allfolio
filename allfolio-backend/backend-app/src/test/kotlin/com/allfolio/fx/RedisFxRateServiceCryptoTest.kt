package com.allfolio.fx

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.math.BigDecimal

/**
 * 코인 KRW 시세에는 폴백 상수가 없다.
 *
 * 상수를 두면 갱신 주체가 사라진 순간 조용히 틀린 값이 평가에 들어간다 —
 * 실제로 ETH가 4,500,000으로 박혀 있어 실제 2,663,000 대비 69% 과대평가였다.
 * 없는 값은 없다고 말하게 한다.
 *
 * mockito-kotlin은 이 프로젝트에 없다(FxRateAdminHanaControllerTest:101 참조).
 * 순수 Mockito를 쓰고 ValueOperations는 제네릭이라 캐스팅한다.
 */
class RedisFxRateServiceCryptoTest {

    private lateinit var ops: ValueOperations<String, String>
    private lateinit var service: RedisFxRateService

    @Suppress("UNCHECKED_CAST")
    @BeforeEach
    fun setUp() {
        ops = Mockito.mock(ValueOperations::class.java) as ValueOperations<String, String>
        val redis = Mockito.mock(StringRedisTemplate::class.java)
        Mockito.`when`(redis.opsForValue()).thenReturn(ops)
        service = RedisFxRateService(redis, BigDecimal("1400"))
    }

    @Test
    fun `Redis에 값이 있으면 그 값을 쓴다`() {
        Mockito.`when`(ops.get("fx:btckrw")).thenReturn("89825000")

        assertThat(service.getCryptoToKrw("BTC")).isEqualByComparingTo("89825000")
    }

    @Test
    fun `Redis가 비어 있으면 예외 - 상수를 지어내지 않는다`() {
        Mockito.`when`(ops.get("fx:ethkrw")).thenReturn(null)

        assertThatThrownBy { service.getCryptoToKrw("ETH") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("ETH")
    }

    @Test
    fun `Redis가 죽어도 예외 - 옛 구현은 여기서 상수를 돌려줬다`() {
        Mockito.`when`(ops.get("fx:btckrw")).thenThrow(RuntimeException("connection refused"))

        assertThatThrownBy { service.getCryptoToKrw("BTC") }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `지원하지 않는 심볼은 IllegalArgumentException`() {
        assertThatThrownBy { service.getCryptoToKrw("DOGE") }
            .isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy { service.setCryptoToKrw("DOGE", BigDecimal.ONE) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `대소문자를 가리지 않는다`() {
        Mockito.`when`(ops.get("fx:btckrw")).thenReturn("89825000")

        assertThat(service.getCryptoToKrw("btc")).isEqualByComparingTo("89825000")
    }

    @Test
    fun `기록은 코인용 24시간 TTL을 쓴다 - USDT의 180초가 아니다`() {
        // 코인은 폴백 상수가 없으므로 만료가 곧 "데이터 없음"이다.
        // USDT의 180초를 그대로 쓰면 수집이 3주기만 밀려도 평가가 예외로 죽는다.
        service.setCryptoToKrw("BTC", BigDecimal("89825000"))

        Mockito.verify(ops).set("fx:btckrw", "89825000", java.time.Duration.ofHours(24))
    }

    @Test
    fun `USDT는 여전히 폴백 상수를 쓴다 - 코인만 달라진다`() {
        Mockito.`when`(ops.get("fx:usdtkrw")).thenReturn(null)

        assertThat(service.getUsdtToKrw()).isEqualByComparingTo("1400")
    }
}
