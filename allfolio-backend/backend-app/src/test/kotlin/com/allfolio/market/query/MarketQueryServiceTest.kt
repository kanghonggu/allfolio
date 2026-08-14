package com.allfolio.market.query

import com.allfolio.market.index.MarketIndexProperties
import com.allfolio.unifiedasset.infrastructure.entity.MarketIndexQuoteEntity
import com.allfolio.unifiedasset.infrastructure.jpa.MarketIndexQuoteJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyCollection
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class MarketQueryServiceTest {

    private val indexRepo: MarketIndexQuoteJpaRepository = mock(MarketIndexQuoteJpaRepository::class.java)

    /** 묶음 조회에 넘어간 코드들 — 호출 한 번당 한 줄 */
    private val requestedCodes = mutableListOf<List<String>>()

    @Test
    fun `설정에 있는 지수를 국내와 해외로 나눠 싣는다`() {
        stubLatest(indexQuote("KOSPI", "2500.00"), indexQuote("SPX", "5600.00"))

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
        stubLatest(indexQuote("KOSPI", "2500.00"))

        val snapshot = service().snapshot()

        assertThat(snapshot.domestic).hasSize(1)
        assertThat(snapshot.overseas).isEmpty()
    }

    /** 등락은 KIS가 준 값을 그대로 쓴다 — 우리가 다시 계산하지 않는다 (MarketQueryService.toView KDoc) */
    @Test
    fun `등락값과 등락률과 장상태를 그대로 싣는다`() {
        stubLatest(
            indexQuote("KOSPI", "2500.00", change = "12.40", changeRate = "0.44", status = "장마감"),
        )

        val view = service().snapshot().domestic!!.single()

        assertThat(view.change).isEqualByComparingTo("12.40")
        assertThat(view.changeRate).isEqualByComparingTo("0.44")
        assertThat(view.marketStatus).isEqualTo("장마감")
        assertThat(view.tradeDate).isEqualTo(LocalDate.of(2026, 8, 13))
        assertThat(view.slot).isEqualTo("CLOSE")
    }

    /**
     * 지수가 몇 종이든 쿼리는 한 번이다. 종목마다 부르면 원격 Postgres 왕복이 지수 수만큼 난다
     * (운영 설정은 국내 5 + 해외 9 = 14종).
     */
    @Test
    fun `국내와 해외 코드를 한 번의 쿼리로 함께 조회한다`() {
        stubLatest(indexQuote("KOSPI", "2500.00"), indexQuote("SPX", "5600.00"))

        service().snapshot()

        assertThat(requestedCodes).hasSize(1)
        assertThat(requestedCodes.single()).containsExactlyInAnyOrder("KOSPI", "SPX")
    }

    /**
     * 리포지터리가 준 것만 매핑한다 — 스텁에 없는 코드는 결과에서 그냥 빠진다.
     *
     * 넘어온 코드는 [requestedCodes]에 직접 받아 둔다. `ArgumentCaptor.capture()`는 null을
     * 돌려주는데 인자가 코틀린 non-null이라 검증 지점에서 NPE가 나고, 그러면 무엇이 틀렸는지가
     * 아니라 테스트가 깨졌다는 사실만 남는다.
     */
    private fun stubLatest(vararg quotes: MarketIndexQuoteEntity) {
        `when`(indexRepo.findLatestByCodes(anyCollection())).thenAnswer { invocation ->
            requestedCodes += invocation.getArgument<Collection<String>>(0).toList()
            quotes.toList()
        }
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
        // price와 **다른 값**이어야 한다. 같게 두면 toView가 price를 prevClose에서 가져오도록
        // 잘못 바뀌어도 테스트가 전부 통과한다 — 실제로 그 변이가 안 잡혔다.
        prevClose = BigDecimal("2480.00"),
        changeValue = BigDecimal(change),
        changeRate = BigDecimal(changeRate),
        prevCloseDate = null,
        marketStatus = status,
        source = "KIS",
        collectedAt = LocalDateTime.of(2026, 8, 13, 15, 50),
    )
}
