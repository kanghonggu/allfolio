package com.allfolio.market.query

import com.allfolio.market.index.MarketIndexProperties
import com.allfolio.market.rate.MarketRateProperties
import com.allfolio.unifiedasset.infrastructure.entity.HanaFxQuoteEntity
import com.allfolio.unifiedasset.infrastructure.entity.MarketIndexQuoteEntity
import com.allfolio.unifiedasset.infrastructure.entity.MarketRateEntity
import com.allfolio.unifiedasset.infrastructure.jpa.HanaFxQuoteJpaRepository
import com.allfolio.unifiedasset.infrastructure.jpa.MarketIndexQuoteJpaRepository
import com.allfolio.unifiedasset.infrastructure.jpa.MarketRateJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyCollection
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

class MarketQueryServiceTest {

    private val indexRepo: MarketIndexQuoteJpaRepository = mock(MarketIndexQuoteJpaRepository::class.java)

    private val fxRepo: HanaFxQuoteJpaRepository = mock(HanaFxQuoteJpaRepository::class.java)

    private val rateRepo: MarketRateJpaRepository = mock(MarketRateJpaRepository::class.java)

    /** 묶음 조회에 넘어간 코드들 — 호출 한 번당 한 줄 */
    private val requestedCodes = mutableListOf<List<String>>()

    private val today = LocalDate.of(2026, 8, 13)
    private val yesterday = LocalDate.of(2026, 8, 12)

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
     * **직전 회차가 아니라 직전 기준일과 비교한다.** 하나은행은 하루에 회차가 여러 번 나오므로,
     * 직전 회차와 비교하면 전일대비가 아니라 장중 변동이 된다.
     */
    @Test
    fun `전일대비는 직전 기준일의 마지막 회차와 비교한다`() {
        `when`(fxRepo.findTopByOrderByBaseDateDescRoundNoDesc()).thenReturn(fxQuote("USD", today, 32, "1390.00"))
        `when`(fxRepo.findAllByBaseDateAndRoundNo(today, 32)).thenReturn(listOf(fxQuote("USD", today, 32, "1390.00")))
        `when`(fxRepo.findTopByBaseDateLessThanOrderByBaseDateDescRoundNoDesc(today))
            .thenReturn(fxQuote("USD", yesterday, 40, "1380.00"))
        `when`(fxRepo.findAllByBaseDateAndRoundNo(yesterday, 40))
            .thenReturn(listOf(fxQuote("USD", yesterday, 40, "1380.00")))

        val fx = service().snapshot().fx!!

        assertThat(fx.baseDate).isEqualTo(today)
        assertThat(fx.roundNo).isEqualTo(32)
        assertThat(fx.quotes.single().change).isEqualByComparingTo("10.00")
        assertThat(fx.quotes.single().changeRate).isEqualByComparingTo("0.72")
    }

    /** 어제 없던 통화가 오늘 생기면 전일대비를 만들어 낼 수 없다. 0이 아니라 null이다 */
    @Test
    fun `직전 기준일에 없던 통화는 전일대비가 null이다`() {
        `when`(fxRepo.findTopByOrderByBaseDateDescRoundNoDesc()).thenReturn(fxQuote("XPF", today, 32, "12.00"))
        `when`(fxRepo.findAllByBaseDateAndRoundNo(today, 32)).thenReturn(listOf(fxQuote("XPF", today, 32, "12.00")))
        `when`(fxRepo.findTopByBaseDateLessThanOrderByBaseDateDescRoundNoDesc(today))
            .thenReturn(fxQuote("USD", yesterday, 40, "1380.00"))
        `when`(fxRepo.findAllByBaseDateAndRoundNo(yesterday, 40))
            .thenReturn(listOf(fxQuote("USD", yesterday, 40, "1380.00")))

        val quote = service().snapshot().fx!!.quotes.single()

        assertThat(quote.change).isNull()
        assertThat(quote.changeRate).isNull()
    }

    /** 수집이 한 번도 안 됐으면 환율 구간 자체가 null이다 */
    @Test
    fun `환율 데이터가 없으면 fx가 null이다`() {
        `when`(fxRepo.findTopByOrderByBaseDateDescRoundNoDesc()).thenReturn(null)

        assertThat(service().snapshot().fx).isNull()
    }

    /**
     * 통화가 몇 종이든 왕복은 4번이다 — 최신 한 건 → 그 회차 전량 → 직전 기준일 한 건 → 그 회차 전량.
     * 통화마다 최신을 따로 찾으면 운영에서 58번이 되고, 통화별로 회차가 갈려
     * 한 화면에 서로 다른 회차가 섞인다.
     */
    @Test
    fun `통화가 여럿이어도 환율 조회는 네 번으로 끝난다`() {
        `when`(fxRepo.findTopByOrderByBaseDateDescRoundNoDesc()).thenReturn(fxQuote("USD", today, 32, "1390.00"))
        `when`(fxRepo.findAllByBaseDateAndRoundNo(today, 32)).thenReturn(
            listOf(
                fxQuote("USD", today, 32, "1390.00"),
                fxQuote("JPY", today, 32, "9.50"),
                fxQuote("EUR", today, 32, "1500.00"),
            ),
        )
        `when`(fxRepo.findTopByBaseDateLessThanOrderByBaseDateDescRoundNoDesc(today))
            .thenReturn(fxQuote("USD", yesterday, 40, "1380.00"))
        `when`(fxRepo.findAllByBaseDateAndRoundNo(yesterday, 40)).thenReturn(
            listOf(
                fxQuote("USD", yesterday, 40, "1380.00"),
                fxQuote("JPY", yesterday, 40, "9.40"),
                fxQuote("EUR", yesterday, 40, "1490.00"),
            ),
        )

        service().snapshot()

        verify(fxRepo).findTopByOrderByBaseDateDescRoundNoDesc()
        verify(fxRepo).findAllByBaseDateAndRoundNo(today, 32)
        verify(fxRepo).findTopByBaseDateLessThanOrderByBaseDateDescRoundNoDesc(today)
        verify(fxRepo).findAllByBaseDateAndRoundNo(yesterday, 40)
        // 위 넷 말고는 아무것도 안 불렀다는 뜻 — 통화별 조회가 끼어들면 여기서 깨진다
        verifyNoMoreInteractions(fxRepo)
    }

    /** 리포지터리는 순서를 보장하지 않는다. 화면 줄 순서가 수집할 때마다 흔들리면 안 된다 */
    @Test
    fun `통화를 코드 순으로 정렬해 싣는다`() {
        `when`(fxRepo.findTopByOrderByBaseDateDescRoundNoDesc()).thenReturn(fxQuote("USD", today, 32, "1390.00"))
        `when`(fxRepo.findAllByBaseDateAndRoundNo(today, 32)).thenReturn(
            listOf(
                fxQuote("USD", today, 32, "1390.00"),
                fxQuote("EUR", today, 32, "1500.00"),
                fxQuote("JPY", today, 32, "9.50"),
            ),
        )

        val fx = service().snapshot().fx!!

        assertThat(fx.quotes.map { it.currency }).containsExactly("EUR", "JPY", "USD")
    }

    /**
     * bp 변동은 직전 기준일과 비교한다. **%p가 아니라 bp다 — 1%p = 100bp.**
     * 국고채가 하루에 0.01%p 움직이면 `1.00`이어야 한다. 100을 안 곱하면 `-0.01`이 나오는데
     * 그것도 금리처럼 생긴 숫자라 눈으로는 안 걸린다.
     *
     * 행을 **기준일 순서가 뒤섞인 채로** 넘긴다. 리포지터리는 순서를 보장하지 않으므로
     * 정렬 없이 `last()`/`first()`를 쓰면 8/11 행이 최신이나 직전으로 잡힌다.
     */
    @Test
    fun `금리의 bp 변동은 직전 기준일과 비교한다`() {
        stubRates(
            "KTB_3Y",
            marketRate("KTB_3Y", LocalDate.of(2026, 8, 13), "3.7810"),
            marketRate("KTB_3Y", LocalDate.of(2026, 8, 11), "3.5000"),
            marketRate("KTB_3Y", LocalDate.of(2026, 8, 12), "3.7910"),
        )

        val view = service().snapshot().rates.single()

        assertThat(view.code).isEqualTo("KTB_3Y")
        assertThat(view.value).isEqualByComparingTo("3.7810")
        assertThat(view.quoteDate).isEqualTo(LocalDate.of(2026, 8, 13))
        assertThat(view.changeBp).isEqualByComparingTo("-1.00")
    }

    /** 수집 첫날처럼 비교할 직전 행이 없으면 0이 아니라 null이다 — 0은 "안 움직였다"는 뜻이 된다 */
    @Test
    fun `행이 하나뿐이면 bp 변동이 null이다`() {
        stubRates("KTB_3Y", marketRate("KTB_3Y", LocalDate.of(2026, 8, 13), "3.7810"))

        val view = service().snapshot().rates.single()

        assertThat(view.value).isEqualByComparingTo("3.7810")
        assertThat(view.changeBp).isNull()
    }

    /** 수집이 한 번도 안 된 지표는 행이 없다. 0으로 채우면 화면이 그걸 진짜 금리로 보여준다 */
    @Test
    fun `행이 없는 금리는 응답에서 빠진다`() {
        assertThat(service().snapshot().rates).isEmpty()
    }

    /**
     * **같은 응답 안에서 항목마다 기준일이 다르다.** 실측으로 기준금리 공표가 시장금리보다
     * 이틀 늦은 것이 확인됐다. 헤더에 기준일 하나를 두고 뭉뚱그리면 화면이 거짓말을 한다.
     */
    @Test
    fun `항목마다 자기 기준일을 단다`() {
        stubRates("BASE_RATE", marketRate("BASE_RATE", LocalDate.of(2026, 8, 11), "2.5000"))
        stubRates("KTB_3Y", marketRate("KTB_3Y", LocalDate.of(2026, 8, 13), "3.7810"))

        val rates = service().snapshot().rates

        // 설정 순서 그대로 싣는다(운영 설정도 기준금리가 앞이다)
        assertThat(rates.map { it.code }).containsExactly("BASE_RATE", "KTB_3Y")
        assertThat(rates.map { it.quoteDate })
            .containsExactly(LocalDate.of(2026, 8, 11), LocalDate.of(2026, 8, 13))
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

    /**
     * 조회 창(from·to)은 `LocalDate.now(KST)`에 걸려 있어 날마다 달라진다 — 값으로 스텁할 수 없다.
     *
     * `?: `가 붙은 이유: 매처는 null을 돌려주는데 인자가 코틀린 non-null이라 코틀린이 호출 지점에
     * 널 검사를 넣는다. 그대로 두면 스텁 지점에서 NPE가 나고, 매처가 큐에 남아 **다음 테스트가**
     * `InvalidUseOfMatchersException`으로 깨진다 — 무엇이 틀렸는지가 아니라 엉뚱한 테스트가 붉어진다.
     * 뒤의 값은 쓰이지 않는다(매처가 이미 등록됐다).
     */
    private fun stubRates(code: String, vararg rows: MarketRateEntity) {
        `when`(
            rateRepo.findByRateCodeAndQuoteDateBetween(
                eq(code) ?: code,
                any(LocalDate::class.java) ?: LocalDate.EPOCH,
                any(LocalDate::class.java) ?: LocalDate.EPOCH,
            ),
        ).thenReturn(rows.toList())
    }

    private fun service(): MarketQueryService {
        val properties = MarketIndexProperties().apply {
            domestic = listOf(MarketIndexProperties.DomesticIndex().apply { code = "KOSPI" })
            overseas = listOf(MarketIndexProperties.OverseasIndex().apply { code = "SPX" })
        }
        // 운영 설정 순서와 같게 기준금리를 앞에 둔다 — 공표가 늦는 쪽이 앞줄이라 순서 회귀가 눈에 띈다
        val rateProperties = MarketRateProperties().apply {
            series = listOf("BASE_RATE", "KTB_3Y").map { seriesCode ->
                MarketRateProperties.RateSeries().apply { code = seriesCode }
            }
        }
        return MarketQueryService(indexRepo, properties, fxRepo, rateRepo, rateProperties)
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

    private fun marketRate(code: String, date: LocalDate, value: String) = MarketRateEntity(
        id = UUID.randomUUID(),
        rateCode = code,
        quoteDate = date,
        rateValue = BigDecimal(value),
        source = "ECOS",
        collectedAt = LocalDateTime.of(2026, 8, 13, 18, 10),
    )

    private fun fxQuote(currency: String, baseDate: LocalDate, roundNo: Int, rate: String) = HanaFxQuoteEntity(
        id = UUID.randomUUID(),
        baseDate = baseDate,
        roundNo = roundNo,
        currency = currency,
        baseRate = BigDecimal(rate),
        cashBuy = null,
        cashSell = null,
        remitSend = null,
        remitReceive = null,
        collectedAt = LocalDateTime.of(2026, 8, 13, 18, 0),
    )
}
