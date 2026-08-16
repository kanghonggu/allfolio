package com.allfolio.market.rate.fred

import com.allfolio.fx.RateValuePolicy
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

    /**
     * 금리는 [RateValuePolicy.PERCENT]로 읽어야 한다 — 연 3.5%를 350으로 주는 단위 오인을
     * 거기서만 잡는다. [FredRateSource]는 정책을 명시하지 않고 클라이언트 기본값에 기댄다.
     *
     * **그래서 기본값이 이 테스트의 대상이다.** 원자재가 같은 클라이언트를 쓰게 되면서
     * 기본을 상한 없는 [RateValuePolicy.PRICE]로 바꾸고 싶은 유혹이 생기는데, 그러면 금리
     * 방어가 조용히 사라진다. 값이 여전히 흘러서 어떤 기존 테스트도 안 깨진다.
     */
    @Test
    fun `금리는 PERCENT 정책으로 읽는다`() {
        val client = FakeClient()

        source(client).fetch("UST_10Y", from, to)

        assertThat(client.policies).containsExactly(RateValuePolicy.PERCENT)
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

        /** 호출자가 넘긴(또는 기본값이 채운) 값 정책. `금리는 PERCENT 정책으로 읽는다`가 본다 */
        val policies = mutableListOf<RateValuePolicy>()

        // 오버라이드는 기본값을 다시 적을 수 없다(Kotlin). 상위의 PERCENT 기본이 그대로 적용된다 —
        // 이 테스트가 검사하려는 것이 바로 그 기본값이므로 여기서 값을 정해선 안 된다
        override fun fetch(
            seriesId: String,
            from: LocalDate,
            to: LocalDate,
            valuePolicy: RateValuePolicy,
        ): RateFetch {
            requested += Triple(seriesId, from, to)
            policies += valuePolicy
            return RateFetch(emptyList(), 0)
        }
    }
}
