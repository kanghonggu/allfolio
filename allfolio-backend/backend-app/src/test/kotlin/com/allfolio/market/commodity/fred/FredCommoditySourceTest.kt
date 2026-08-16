package com.allfolio.market.commodity.fred

import com.allfolio.fx.RateValuePolicy
import com.allfolio.market.commodity.CommodityProperties
import com.allfolio.market.rate.RateFetch
import com.allfolio.market.rate.RateObservation
import com.allfolio.market.rate.fred.FredApiClient
import com.allfolio.market.rate.fred.FredObservationParser
import com.allfolio.market.rate.fred.FredProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class FredCommoditySourceTest {

    private val from = LocalDate.of(2026, 8, 1)
    private val to = LocalDate.of(2026, 8, 14)

    @Test
    fun `일간과 월간을 한 소스가 담당한다`() {
        assertThat(source().codes).containsExactly("WTI", "COPPER")
        assertThat(source().sourceName).isEqualTo("FRED")
    }

    @Test
    fun `설정의 시리즈 ID로 조회한다`() {
        val client = FakeClient()

        source(client).fetch("COPPER", from, to)

        assertThat(client.requested).containsExactly(Triple("PCOPPUSDM", from, to))
    }

    /**
     * **원자재는 [RateValuePolicy.PRICE]를 명시해야 한다.** 클라이언트 기본값은 PERCENT이고,
     * 그건 연 3.5%를 350으로 주는 계열을 막으려고 상한을 거는 정책이다 — 원자재에 그대로 쓰면
     * 구리(9,000 USD/MT)·금·지수가 파싱 단계에서 통째로 버려진다. WTI(60쯤)만 우연히 통과해서
     * "일부 종목만 0건"이라는 가장 알아채기 어려운 모양이 된다.
     */
    @Test
    fun `원자재는 PRICE 정책으로 읽는다`() {
        val client = FakeClient()

        source(client).fetch("WTI", from, to)

        assertThat(client.policies).containsExactly(RateValuePolicy.PRICE)
    }

    /** 금리 타입이 원자재 코드로 새지 않게 경계에서 옮겨 담는다 — 값과 skipped는 그대로다 */
    @Test
    fun `관측과 skipped를 그대로 옮겨 담는다`() {
        val client = FakeClient(
            RateFetch(
                rows = listOf(RateObservation(LocalDate.of(2026, 8, 3), BigDecimal("9123.45"))),
                skipped = 2,
            ),
        )

        val fetched = source(client).fetch("COPPER", from, to)

        assertThat(fetched.rows).singleElement().satisfies({
            assertThat(it.quoteDate).isEqualTo(LocalDate.of(2026, 8, 3))
            assertThat(it.value).isEqualByComparingTo("9123.45")
        })
        assertThat(fetched.skipped).isEqualTo(2)
    }

    /** 설정에 없는 코드가 오면 조용히 빈 결과를 주지 않는다 — 설정과 코드가 어긋난 것이다 */
    @Test
    fun `설정에 없는 코드는 예외다`() {
        assertThatThrownBy { source().fetch("GOLD", from, to) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("GOLD")
    }

    /** FSC 항목은 이 소스가 담당하지 않는다 — 담당하면 금을 FRED에 물어보게 된다 */
    @Test
    fun `FSC 항목은 담당하지 않는다`() {
        val properties = properties().apply {
            fsc = listOf(item("GOLD_KRX", "getGoldPriceInfo", "KRW/g", "D"))
        }

        val source = FredCommoditySource(FakeClient(), properties)

        assertThat(source.codes).doesNotContain("GOLD_KRX")
        assertThatThrownBy { source.fetch("GOLD_KRX", from, to) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun source(client: FakeClient = FakeClient()): FredCommoditySource =
        FredCommoditySource(client, properties())

    private fun properties() = CommodityProperties().apply {
        fredDaily = listOf(item("WTI", "DCOILWTICO", "USD/bbl", "D"))
        fredMonthly = listOf(item("COPPER", "PCOPPUSDM", "USD/MT", "M"))
    }

    private fun item(code: String, seriesId: String, unit: String, frequency: String) =
        CommodityProperties.CommodityItem().also {
            it.code = code
            it.seriesId = seriesId
            it.unit = unit
            it.frequency = frequency
        }

    private class FakeClient(private val response: RateFetch = RateFetch(emptyList(), 0)) :
        FredApiClient(FredProperties(), FredObservationParser(ObjectMapper())) {

        val requested = mutableListOf<Triple<String, LocalDate, LocalDate>>()

        /** 호출자가 넘긴(또는 기본값이 채운) 값 정책. `원자재는 PRICE 정책으로 읽는다`가 본다 */
        val policies = mutableListOf<RateValuePolicy>()

        // 오버라이드는 기본값을 다시 적을 수 없다(Kotlin). 상위의 PERCENT 기본이 그대로 적용되므로
        // 호출자가 PRICE를 안 넘기면 여기 PERCENT가 기록된다 — 그게 위 테스트의 대상이다
        override fun fetch(
            seriesId: String,
            from: LocalDate,
            to: LocalDate,
            valuePolicy: RateValuePolicy,
        ): RateFetch {
            requested += Triple(seriesId, from, to)
            policies += valuePolicy
            return response
        }
    }
}
