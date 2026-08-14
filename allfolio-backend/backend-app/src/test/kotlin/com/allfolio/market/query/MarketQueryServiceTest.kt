package com.allfolio.market.query

import com.allfolio.market.index.MarketIndexProperties
import com.allfolio.unifiedasset.infrastructure.entity.MarketIndexQuoteEntity
import com.allfolio.unifiedasset.infrastructure.jpa.MarketIndexQuoteJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class MarketQueryServiceTest {

    private val indexRepo: MarketIndexQuoteJpaRepository = mock(MarketIndexQuoteJpaRepository::class.java)

    @Test
    fun `설정에 있는 지수를 국내와 해외로 나눠 싣는다`() {
        `when`(indexRepo.findLatest("KOSPI")).thenReturn(indexQuote("KOSPI", "2500.00"))
        `when`(indexRepo.findLatest("SPX")).thenReturn(indexQuote("SPX", "5600.00"))

        val snapshot = service().snapshot()

        assertThat(snapshot.domestic?.map { it.code }).containsExactly("KOSPI")
        assertThat(snapshot.overseas?.map { it.code }).containsExactly("SPX")
        assertThat(snapshot.domestic?.single()?.price).isEqualByComparingTo("2500.00")
    }

    /**
     * 수집이 한 번도 안 된 지수는 행이 없다. 그때 0이나 빈 값을 만들어 내면
     * 화면이 "0.00"을 진짜 값처럼 보여준다 — 아예 빼야 한다.
     */
    @Test
    fun `행이 없는 지수는 응답에서 빠진다`() {
        `when`(indexRepo.findLatest("KOSPI")).thenReturn(indexQuote("KOSPI", "2500.00"))
        `when`(indexRepo.findLatest("SPX")).thenReturn(null)

        val snapshot = service().snapshot()

        assertThat(snapshot.domestic).hasSize(1)
        assertThat(snapshot.overseas).isEmpty()
    }

    /** 등락은 KIS가 준 값을 그대로 쓴다 — 우리가 다시 계산하지 않는다 */
    @Test
    fun `등락값과 등락률과 장상태를 그대로 싣는다`() {
        `when`(indexRepo.findLatest("KOSPI")).thenReturn(
            indexQuote("KOSPI", "2500.00", change = "12.40", changeRate = "0.44", status = "장마감"),
        )
        `when`(indexRepo.findLatest("SPX")).thenReturn(null)

        val view = service().snapshot().domestic!!.single()

        assertThat(view.change).isEqualByComparingTo("12.40")
        assertThat(view.changeRate).isEqualByComparingTo("0.44")
        assertThat(view.marketStatus).isEqualTo("장마감")
        assertThat(view.tradeDate).isEqualTo(LocalDate.of(2026, 8, 13))
        assertThat(view.slot).isEqualTo("CLOSE")
    }

    private fun service(): MarketQueryService {
        val properties = MarketIndexProperties().apply {
            domestic = listOf(MarketIndexProperties.DomesticIndex().apply { code = "KOSPI" })
            overseas = listOf(MarketIndexProperties.OverseasIndex().apply { code = "SPX" })
        }
        return MarketQueryService(indexRepo, properties)
    }

    private fun indexQuote(
        code: String,
        price: String,
        change: String = "0",
        changeRate: String = "0",
        status: String = "장중",
    ) = MarketIndexQuoteEntity(
        id = UUID.randomUUID(),
        indexCode = code,
        tradeDate = LocalDate.of(2026, 8, 13),
        slot = "CLOSE",
        price = BigDecimal(price),
        prevClose = BigDecimal(price),
        changeValue = BigDecimal(change),
        changeRate = BigDecimal(changeRate),
        prevCloseDate = null,
        marketStatus = status,
        source = "KIS",
        collectedAt = LocalDateTime.of(2026, 8, 13, 15, 50),
    )
}
