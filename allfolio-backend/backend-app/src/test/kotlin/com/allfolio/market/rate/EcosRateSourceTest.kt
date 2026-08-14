package com.allfolio.market.rate

import com.allfolio.fx.EcosApiClient
import com.allfolio.fx.EcosObservation
import com.allfolio.fx.EcosParseResult
import com.allfolio.fx.EcosQuery
import com.allfolio.fx.RateValuePolicy
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * ECOS 조회 조립은 `RateCollectService`에 인라인으로 있다가 [EcosRateSource]로 이사했다.
 * 그때 서비스 테스트에 있던 단언들이 여기로 같이 옮겨 온 것이다 — 옮긴 코드를 지키던 테스트를
 * 두고 오면 그 방어는 다음 리팩터링에 조용히 사라진다.
 */
class EcosRateSourceTest {

    private val from = LocalDate.of(2026, 8, 10)
    private val to = LocalDate.of(2026, 8, 12)

    @Test
    fun `금리 정책으로 조회한다`() {
        val client = FakeClient()

        source(client, series("KTB_3Y", "S1")).fetch("KTB_3Y", from, to)

        // 환율 정책으로 부르면 0.00% 공표일이 조용히 사라진다
        assertThat(client.queries.single().valuePolicy).isEqualTo(RateValuePolicy.PERCENT)
        assertThat(client.queries.single().cycle).isEqualTo("D")
    }

    /** 좌표는 그 코드의 설정 행에서 온다. 종목을 섞으면 다른 만기 값이 그 코드로 저장된다 */
    @Test
    fun `코드에 맞는 통계표와 항목으로 조회한다`() {
        val client = FakeClient()
        val properties = arrayOf(series("KTB_3Y", "817Y002", "010200000"), series("KTB_10Y", "817Y002", "010210000"))

        source(client, *properties).fetch("KTB_10Y", from, to)

        assertThat(client.queries.single().statCode).isEqualTo("817Y002")
        assertThat(client.queries.single().itemCode).isEqualTo("010210000")
    }

    /** 파서가 버린 행 수는 그대로 올려 보낸다 — 요약의 skippedRows가 "형식이 바뀌었다"는 신호다 */
    @Test
    fun `관측값과 버린 행 수를 그대로 옮긴다`() {
        val client = FakeClient(
            rates = listOf(EcosObservation(LocalDate.of(2026, 8, 11), BigDecimal("3.10"))),
            skipped = 2,
        )

        val fetch = source(client, series("KTB_3Y", "S1")).fetch("KTB_3Y", from, to)

        assertThat(fetch.rows).containsExactly(RateObservation(LocalDate.of(2026, 8, 11), BigDecimal("3.10")))
        assertThat(fetch.skipped).isEqualTo(2)
    }

    /**
     * 설정에 없는 코드는 예외다. 조용히 0건을 주면 요약이 초록인 채 그 종목만 영원히 빈다 —
     * 서비스가 실패로 잡아 종목 이름과 함께 보고하게 한다.
     */
    @Test
    fun `설정에 없는 코드는 예외로 알린다`() {
        val client = FakeClient()

        assertThatThrownBy { source(client, series("KTB_3Y", "S1")).fetch("US_DGS10", from, to) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("US_DGS10")
        assertThat(client.queries).isEmpty()
    }

    /** 담당 코드는 설정 순서 그대로다 — 수집 순서와 화면 순서가 여기서 나온다 */
    @Test
    fun `설정에 있는 코드를 순서대로 내놓는다`() {
        val source = source(FakeClient(), series("BASE_RATE", "S1"), series("KTB_3Y", "S2"))

        assertThat(source.codes).containsExactly("BASE_RATE", "KTB_3Y")
        assertThat(source.sourceName).isEqualTo("ECOS")
    }

    private fun source(client: EcosApiClient, vararg series: MarketRateProperties.EcosSeries) =
        EcosRateSource(client, MarketRateProperties().apply { ecos = series.toList() })

    private fun series(code: String, statCode: String, itemCode: String = "ITEM") =
        MarketRateProperties.EcosSeries().apply {
            this.code = code
            this.statCode = statCode
            this.itemCode = itemCode
            this.cycle = "D"
        }

    private class FakeClient(
        private val rates: List<EcosObservation> = emptyList(),
        private val skipped: Int = 0,
    ) : EcosApiClient {
        val queries = mutableListOf<EcosQuery>()

        override fun fetch(query: EcosQuery, from: LocalDate, to: LocalDate): EcosParseResult {
            queries += query
            return EcosParseResult(rates, skipped)
        }
    }
}
