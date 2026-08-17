package com.allfolio.market.commodity.fsc

import com.allfolio.market.commodity.CommodityObservation
import com.allfolio.market.commodity.CommodityProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class FscCommoditySourceTest {

    private val from = LocalDate.of(2026, 8, 5)
    private val to = LocalDate.of(2026, 8, 13)

    private companion object {
        const val GOLD_1KG = "04020000"
        const val MINI_GOLD = "04020100"

        val AUG13: LocalDate = LocalDate.of(2026, 8, 13)
        val AUG12: LocalDate = LocalDate.of(2026, 8, 12)
    }

    @Test
    fun `설정에 실린 코드를 담당한다`() {
        assertThat(source().codes).containsExactly("GOLD_KRX")
        assertThat(source().sourceName).isEqualTo("FSC")
    }

    /**
     * **이 테스트가 이 태스크의 핵심이다.** KRX 금시장에는 상품이 둘 상장돼 있고
     * (`04020000` 금 1kg · `04020100` 미니금 100g) 한 응답에 **같은 날짜로 둘 다** 온다.
     * 안 거르면 `CommodityCollectService`의 `deduped[date] = value`가 뒤에 온 행으로 앞을
     * 덮어써 **미니금 값이 조용히 금으로 저장된다.**
     *
     * 그리고 그 오류는 어느 층에서도 안 드러난다 — 둘 다 원/g이라 자릿수가 같고
     * (실측 200,570 vs 200,240) 값 정책(PRICE)은 상한이 없다. 거르는 자리가 여기뿐이다.
     */
    @Test
    fun `설정한 종목의 행만 고른다 - 미니금이 섞여 와도`() {
        val client = FakeClient(
            FscGoldFetch(
                rows = listOf(
                    row(GOLD_1KG, AUG13, "200570"),
                    // 같은 날짜의 남의 종목. 뒤에 오므로 안 거르면 이 값이 남는다
                    row(MINI_GOLD, AUG13, "200240"),
                    row(GOLD_1KG, AUG12, "200470"),
                    row(MINI_GOLD, AUG12, "199900"),
                ),
                skipped = 0,
            ),
        )

        val fetched = source(client).fetch("GOLD_KRX", from, to)

        assertThat(fetched.rows).containsExactly(
            CommodityObservation(AUG13, BigDecimal("200570")),
            CommodityObservation(AUG12, BigDecimal("200470")),
        )
    }

    /**
     * 걸러낸 남의 종목은 `skipped`가 아니다. `skipped`는 "형식이 이상해 버린 행"이고,
     * 0이 아니면 요약을 보는 사람이 응답 형식이 바뀐 신호로 읽는다 — 미니금은 정상적으로
     * 온 남의 종목이라 그 축에 실으면 매 실행 가짜 경보가 뜬다.
     */
    @Test
    fun `걸러낸 남의 종목을 skipped로 세지 않는다`() {
        val client = FakeClient(
            FscGoldFetch(
                rows = listOf(row(GOLD_1KG, AUG13, "200570"), row(MINI_GOLD, AUG13, "200240")),
                skipped = 2,
            ),
        )

        val fetched = source(client).fetch("GOLD_KRX", from, to)

        assertThat(fetched.rows).hasSize(1)
        assertThat(fetched.skipped).isEqualTo(2)
    }

    /**
     * **종목코드는 설정에서 온다.** 코드에 박으면 종목이 바뀌는 날 배포를 해야 하고,
     * 앞의 0이 날아간 설정(`4020000`)이 들어와도 이 필터가 전부 걸러 조용히 0건이 된다 —
     * 그래서 YAML의 따옴표를 `CommodityPropertiesYamlTest`가 따로 지킨다.
     */
    @Test
    fun `종목코드는 설정의 series-id다`() {
        val properties = properties().apply {
            fsc = listOf(item("GOLD_KRX", MINI_GOLD, "KRW/g", "D"))
        }
        val client = FakeClient(
            FscGoldFetch(listOf(row(GOLD_1KG, AUG13, "200570"), row(MINI_GOLD, AUG13, "200240")), 0),
        )

        val fetched = FscCommoditySource(client, properties).fetch("GOLD_KRX", from, to)

        assertThat(fetched.rows).singleElement()
            .satisfies({ assertThat(it.value).isEqualByComparingTo("200240") })
    }

    /** 요청 구간을 클라이언트에 그대로 넘긴다 — 구간 밖 필터링은 서비스 몫이다(포트 계약) */
    @Test
    fun `요청 구간을 그대로 넘긴다`() {
        val client = FakeClient()

        source(client).fetch("GOLD_KRX", from, to)

        assertThat(client.requested).containsExactly(from to to)
    }

    /** 설정에 없는 코드가 오면 조용히 빈 결과를 주지 않는다 — 설정과 코드가 어긋난 것이다 */
    @Test
    fun `설정에 없는 코드는 예외다`() {
        assertThatThrownBy { source().fetch("WTI", from, to) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("WTI")
    }

    /** FRED 항목은 이 소스가 담당하지 않는다 — 담당하면 유가를 공공데이터포털에 물어보게 된다 */
    @Test
    fun `FRED 항목은 담당하지 않는다`() {
        val properties = properties().apply {
            fredDaily = listOf(item("WTI", "DCOILWTICO", "USD/bbl", "D"))
        }

        val source = FscCommoditySource(FakeClient(), properties)

        assertThat(source.codes).doesNotContain("WTI")
        assertThatThrownBy { source.fetch("WTI", from, to) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun source(client: FakeClient = FakeClient()) = FscCommoditySource(client, properties())

    private fun properties() = CommodityProperties().apply {
        fsc = listOf(item("GOLD_KRX", GOLD_1KG, "KRW/g", "D"))
    }

    private fun item(code: String, seriesId: String, unit: String, frequency: String) =
        CommodityProperties.CommodityItem().also {
            it.code = code
            it.seriesId = seriesId
            it.unit = unit
            it.frequency = frequency
        }

    private fun row(srtnCd: String, date: LocalDate, price: String) =
        FscGoldRow(srtnCd, date, BigDecimal(price), changeValue = null, changeRate = null)

    private class FakeClient(private val response: FscGoldFetch = FscGoldFetch(emptyList(), 0)) :
        FscCommodityClient(apiKey = "test-key", baseUrl = "http://localhost", objectMapper = ObjectMapper()) {

        val requested = mutableListOf<Pair<LocalDate, LocalDate>>()

        override fun fetchGoldPrices(from: LocalDate, to: LocalDate): FscGoldFetch {
            requested += from to to
            return response
        }
    }
}
