package com.allfolio.market.rate.fred

import com.allfolio.market.rate.MarketRateProperties
import com.allfolio.market.rate.RateFetch
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

class FredRateSourceTest {

    private val from = LocalDate.of(2026, 8, 1)
    private val to = LocalDate.of(2026, 8, 14)

    @Test
    fun `설정의 코드를 담당한다`() {
        assertThat(source().codes).containsExactly("UST_10Y")
        assertThat(source().sourceName).isEqualTo("FRED")
    }

    @Test
    fun `설정의 시리즈 ID로 조회한다`() {
        val client = FakeClient()

        source(client).fetch("UST_10Y", from, to)

        assertThat(client.requested).containsExactly(Triple("DGS10", from, to))
    }

    /** 설정에 없는 코드가 오면 조용히 빈 결과를 주지 않는다 — 설정과 코드가 어긋난 것이다 */
    @Test
    fun `설정에 없는 코드는 예외다`() {
        assertThatThrownBy { source().fetch("UST_2Y", from, to) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("UST_2Y")
    }

    private fun source(client: FakeClient = FakeClient()): FredRateSource {
        val properties = MarketRateProperties().apply {
            fred = listOf(
                MarketRateProperties.FredSeries().apply { code = "UST_10Y"; seriesId = "DGS10" },
            )
        }
        return FredRateSource(client, properties)
    }

    private class FakeClient : FredApiClient(FredProperties(), FredObservationParser(ObjectMapper())) {
        val requested = mutableListOf<Triple<String, LocalDate, LocalDate>>()

        override fun fetch(seriesId: String, from: LocalDate, to: LocalDate): RateFetch {
            requested += Triple(seriesId, from, to)
            return RateFetch(emptyList(), 0)
        }
    }
}
