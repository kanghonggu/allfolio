package com.allfolio.fx.exchange

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class ExchangeFxApiClientTest {

    /** 지정한 맵을 돌려주거나 예외를 던지는 가짜 소스. 네트워크 없이 체인만 검증한다. */
    private class FakeSource(
        override val sourceName: String,
        private val result: Result<Map<String, BigDecimal>>,
    ) : FxQuoteSource {
        var callCount = 0
            private set

        override fun fetchKrwRates(): Map<String, BigDecimal> {
            callCount++
            return result.getOrThrow()
        }
    }

    private fun ok(name: String, vararg pairs: Pair<String, String>) =
        FakeSource(name, Result.success(pairs.associate { it.first to BigDecimal(it.second) }))

    private fun fail(name: String) =
        FakeSource(name, Result.failure(FxQuoteException("$name 실패")))

    private val allThree = arrayOf("USDT" to "1409", "BTC" to "89825000", "ETH" to "2663000")

    @Test
    fun `첫 소스가 전부 채우면 두 번째는 부르지 않는다`() {
        val first = ok("UPBIT", *allThree)
        val second = ok("BITHUMB", *allThree)

        val rates = ExchangeFxApiClient(listOf(first, second)).fetchKrwRates()

        assertThat(rates).containsOnlyKeys("USDT", "BTC", "ETH")
        assertThat(rates["BTC"]).isEqualByComparingTo("89825000")
        assertThat(second.callCount).isZero()
    }

    @Test
    fun `첫 소스가 실패하면 두 번째로 넘어간다`() {
        val client = ExchangeFxApiClient(listOf(fail("UPBIT"), ok("BITHUMB", *allThree)))

        assertThat(client.fetchKrwRates()).containsOnlyKeys("USDT", "BTC", "ETH")
    }

    @Test
    fun `부족한 심볼만 다음 소스에서 채운다 - 이미 채운 것은 덮지 않는다`() {
        // 설계의 핵심. ETH 하나 때문에 멀쩡한 USDT·BTC 갱신을 막으면
        // 한 심볼의 장애가 나머지 둘을 낡게 만든다.
        val upbit = ok("UPBIT", "USDT" to "1409", "BTC" to "89825000")
        val bithumb = ok("BITHUMB", "USDT" to "9999", "BTC" to "9999", "ETH" to "2664000")

        val rates = ExchangeFxApiClient(listOf(upbit, bithumb)).fetchKrwRates()

        assertThat(rates["USDT"]).isEqualByComparingTo("1409")       // Upbit 것을 지킨다
        assertThat(rates["BTC"]).isEqualByComparingTo("89825000")    // Upbit 것을 지킨다
        assertThat(rates["ETH"]).isEqualByComparingTo("2664000")     // Bithumb이 채운다
    }

    @Test
    fun `일부 심볼을 끝내 못 채워도 채운 것은 돌려준다`() {
        val client = ExchangeFxApiClient(listOf(ok("UPBIT", "USDT" to "1409")))

        assertThat(client.fetchKrwRates()).containsOnlyKeys("USDT")
    }

    @Test
    fun `모든 소스가 실패하면 예외 - 스케줄러가 잡아 기존 캐시를 지킨다`() {
        val client = ExchangeFxApiClient(listOf(fail("UPBIT"), fail("BITHUMB")))

        assertThatThrownBy { client.fetchKrwRates() }
            .isInstanceOf(FxQuoteException::class.java)
            .hasMessageContaining("모든 소스")
    }

    @Test
    fun `USDT가 범위를 벗어나면 그 심볼만 다음 소스에서 받는다`() {
        val upbit = ok("UPBIT", "USDT" to "0", "BTC" to "89825000", "ETH" to "2663000")
        val bithumb = ok("BITHUMB", "USDT" to "1410", "BTC" to "9", "ETH" to "9")

        val rates = ExchangeFxApiClient(listOf(upbit, bithumb)).fetchKrwRates()

        assertThat(rates["USDT"]).isEqualByComparingTo("1410")
        assertThat(rates["BTC"]).isEqualByComparingTo("89825000")
    }

    @Test
    fun `BTC 범위는 USDT와 다르다 - 8900만은 정상이다`() {
        // 옛 가드(500~5000)를 그대로 뒀다면 BTC가 전부 걸러진다
        val client = ExchangeFxApiClient(listOf(ok("UPBIT", "BTC" to "89825000")))

        assertThat(client.fetchKrwRates()["BTC"]).isEqualByComparingTo("89825000")
    }

    @Test
    fun `ETH 범위도 따로다 - 266만은 정상이다`() {
        val client = ExchangeFxApiClient(listOf(ok("UPBIT", "ETH" to "2663000")))

        assertThat(client.fetchKrwRates()["ETH"]).isEqualByComparingTo("2663000")
    }

    @Test
    fun `BTC 자리에 USDT 값이 오면 범위 밖으로 걸러진다`() {
        // 파싱이 뒤바뀐 상황. 1409원짜리 BTC를 그대로 쓰면 자산이 6만분의 1이 된다.
        val client = ExchangeFxApiClient(listOf(ok("UPBIT", "BTC" to "1409")))

        assertThatThrownBy { client.fetchKrwRates() }
            .isInstanceOf(FxQuoteException::class.java)
    }

    @Test
    fun `모르는 심볼은 무시한다`() {
        val client = ExchangeFxApiClient(listOf(ok("UPBIT", "USDT" to "1409", "DOGE" to "300")))

        assertThat(client.fetchKrwRates()).containsOnlyKeys("USDT")
    }

    @Test
    fun `소스가 하나도 없으면 예외`() {
        assertThatThrownBy { ExchangeFxApiClient(emptyList()).fetchKrwRates() }
            .isInstanceOf(FxQuoteException::class.java)
    }

    @Test
    fun `FxQuoteException이 아닌 예외는 전파한다 - 진짜 버그를 폴백으로 삼키면 안 된다`() {
        val broken = FakeSource("UPBIT", Result.failure(IllegalStateException("파서 버그")))
        val healthy = ok("BITHUMB", *allThree)

        assertThatThrownBy { ExchangeFxApiClient(listOf(broken, healthy)).fetchKrwRates() }
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(healthy.callCount).isZero()
    }
}
