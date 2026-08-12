package com.allfolio.fx.exchange

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ExchangeFxApiClientTest {

    /** 지정한 값을 돌려주거나 예외를 던지는 가짜 소스. 네트워크 없이 체인만 검증한다. */
    private class FakeSource(
        override val sourceName: String,
        private val result: Result<BigDecimal>,
    ) : FxQuoteSource {
        var callCount = 0
            private set

        override fun fetchUsdtKrw(): BigDecimal {
            callCount++
            return result.getOrThrow()
        }
    }

    private fun ok(name: String, value: String) =
        FakeSource(name, Result.success(BigDecimal(value)))

    private fun fail(name: String) =
        FakeSource(name, Result.failure(FxQuoteException("$name 실패")))

    @Test
    fun `첫 소스가 성공하면 그 값을 쓰고 두 번째는 부르지 않는다`() {
        val first = ok("UPBIT", "1408")
        val second = ok("BITHUMB", "1409")

        val rate = ExchangeFxApiClient(listOf(first, second)).getUsdtKrw()

        assertThat(rate).isEqualByComparingTo("1408")
        assertThat(second.callCount).isZero()
    }

    @Test
    fun `첫 소스가 실패하면 두 번째로 넘어간다`() {
        val client = ExchangeFxApiClient(listOf(fail("UPBIT"), ok("BITHUMB", "1409")))

        assertThat(client.getUsdtKrw()).isEqualByComparingTo("1409")
    }

    @Test
    fun `모든 소스가 실패하면 예외 - 스케줄러가 잡아 기존 캐시를 지킨다`() {
        val client = ExchangeFxApiClient(listOf(fail("UPBIT"), fail("BITHUMB")))

        assertThatThrownBy { client.getUsdtKrw() }
            .isInstanceOf(FxQuoteException::class.java)
            .hasMessageContaining("모든 소스")
    }

    @Test
    fun `범위를 벗어난 값은 실패로 보고 다음 소스로 넘어간다`() {
        // 파싱이 깨져 0이 나온 상황. 0을 그대로 쓰면 모든 자산이 0원이 된다.
        val client = ExchangeFxApiClient(listOf(ok("UPBIT", "0"), ok("BITHUMB", "1409")))

        assertThat(client.getUsdtKrw()).isEqualByComparingTo("1409")
    }

    @Test
    fun `비정상적으로 큰 값도 거른다`() {
        // 원 단위와 다른 필드를 잘못 읽은 상황
        val client = ExchangeFxApiClient(listOf(ok("UPBIT", "1786512440253"), ok("BITHUMB", "1409")))

        assertThat(client.getUsdtKrw()).isEqualByComparingTo("1409")
    }

    @Test
    fun `모든 소스가 범위 밖이면 예외 - 그럴듯한 쓰레기를 쓰느니 캐시를 지킨다`() {
        val client = ExchangeFxApiClient(listOf(ok("UPBIT", "0"), ok("BITHUMB", "0")))

        assertThatThrownBy { client.getUsdtKrw() }
            .isInstanceOf(FxQuoteException::class.java)
    }

    @Test
    fun `소스가 하나도 없으면 예외`() {
        assertThatThrownBy { ExchangeFxApiClient(emptyList()).getUsdtKrw() }
            .isInstanceOf(FxQuoteException::class.java)
    }
}
