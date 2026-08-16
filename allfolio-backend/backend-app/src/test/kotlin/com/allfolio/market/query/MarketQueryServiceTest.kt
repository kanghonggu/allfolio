package com.allfolio.market.query

import com.allfolio.market.commodity.CommodityProperties
import com.allfolio.market.index.MarketIndexProperties
import com.allfolio.market.rate.MarketRateProperties
import com.allfolio.unifiedasset.infrastructure.entity.HanaFxQuoteEntity
import com.allfolio.unifiedasset.infrastructure.entity.MarketCommodityQuoteEntity
import com.allfolio.unifiedasset.infrastructure.entity.MarketIndexQuoteEntity
import com.allfolio.unifiedasset.infrastructure.entity.MarketRateEntity
import com.allfolio.unifiedasset.infrastructure.jpa.HanaFxQuoteJpaRepository
import com.allfolio.unifiedasset.infrastructure.jpa.MarketCommodityQuoteJpaRepository
import com.allfolio.unifiedasset.infrastructure.jpa.MarketIndexQuoteJpaRepository
import com.allfolio.unifiedasset.infrastructure.jpa.MarketRateJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyCollection
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

class MarketQueryServiceTest {

    private val indexRepo: MarketIndexQuoteJpaRepository = mock(MarketIndexQuoteJpaRepository::class.java)

    private val fxRepo: HanaFxQuoteJpaRepository = mock(HanaFxQuoteJpaRepository::class.java)

    private val rateRepo: MarketRateJpaRepository = mock(MarketRateJpaRepository::class.java)

    private val commodityRepo: MarketCommodityQuoteJpaRepository =
        mock(MarketCommodityQuoteJpaRepository::class.java)

    /** 묶음 조회에 넘어간 코드들 — 호출 한 번당 한 줄 */
    private val requestedCodes = mutableListOf<List<String>>()

    /** 금리 스텁이 돌려줄 행. 리포지터리가 순서를 보장하지 않으므로 넣은 순서 그대로 돌려준다 */
    private val rateRows = mutableListOf<MarketRateEntity>()

    /** 금리 묶음 조회에 넘어간 (코드들, from, to) — 호출 한 번당 한 줄 */
    private val rateQueries = mutableListOf<Triple<List<String>, LocalDate, LocalDate>>()

    /** 원자재 스텁이 돌려줄 행. 리포지터리가 순서를 보장하지 않으므로 넣은 순서 그대로 돌려준다 */
    private val commodityRows = mutableListOf<MarketCommodityQuoteEntity>()

    /** 원자재 묶음 조회에 넘어간 코드들 — 호출 한 번당 한 줄 */
    private val commodityQueries = mutableListOf<List<String>>()

    private val today = LocalDate.of(2026, 8, 13)
    private val yesterday = LocalDate.of(2026, 8, 12)

    init {
        stubRateQuery()
        stubCommodityQuery()
    }

    @Test
    fun `설정에 있는 지수를 국내와 해외로 나눠 싣는다`() {
        stubLatest(indexQuote("KOSPI", "2500.00"), indexQuote("SPX", "5600.00"))

        val snapshot = service().snapshot()

        assertThat(snapshot.domestic?.map { it.code }).containsExactly("KOSPI")
        assertThat(snapshot.overseas?.map { it.code }).containsExactly("SPX")
        assertThat(snapshot.domestic?.single()?.price).isEqualByComparingTo("2500.00")
        // 플래그 on 쪽 값도 여기서 함께 못 박는다 — off 테스트만 있으면 `false` 상수 반환이 통과한다
        assertThat(snapshot.flags.indicesEnabled).isTrue()
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
     * **플래그가 off면 서버가 지수를 아예 싣지 않는다 — 빈 리스트가 아니라 null이다.**
     * 빈 리스트는 "조회는 했는데 데이터가 없다"는 뜻이라, 프런트가 실수로 렌더해도 이미 늦다.
     * 재배포를 실제로 멈추는 것은 서버가 안 싣는 것이지 프런트가 안 그리는 것이 아니다(AF-108).
     *
     * **조회조차 안 하는 것까지 못 박는다.** 읽어 놓고 응답에서만 빼는 구현은 지금 당장은 같아 보여도,
     * 조립부를 손대는 순간 다시 새어 나간다. 그래서 데이터가 **있는** 상태로 스텁해 두고 검증한다 —
     * 스텁을 비워 두면 "안 불렀다"와 "불렀는데 없더라"가 구분되지 않는다.
     */
    @Test
    fun `플래그가 off면 지수를 조회하지도 싣지도 않는다`() {
        stubLatest(indexQuote("KOSPI", "2500.00"), indexQuote("SPX", "5600.00"))

        val snapshot = service(indicesEnabled = false).snapshot()

        assertThat(snapshot.domestic).isNull()
        assertThat(snapshot.overseas).isNull()
        assertThat(snapshot.flags.indicesEnabled).isFalse()
        verifyNoInteractions(indexRepo)
    }

    /**
     * 플래그가 지우는 건 지수뿐이다. 환율(하나은행)·금리(한국은행)는 AF-108 재배포 검토 대상이
     * 아니라 같은 제약을 안 받는다. 플래그 확인을 `snapshot()` 맨 앞의 조기 반환으로 넣으면
     * 지수 두 탭을 끄려다 네 탭이 다 사라진다 — 그 변이를 여기서 잡는다.
     */
    @Test
    fun `플래그가 off여도 환율과 금리는 그대로 싣는다`() {
        `when`(fxRepo.findTopByOrderByBaseDateDescRoundNoDesc()).thenReturn(fxQuote("USD", today, 32, "1390.00"))
        `when`(fxRepo.findAllByBaseDateAndRoundNo(today, 32)).thenReturn(listOf(fxQuote("USD", today, 32, "1390.00")))
        stubRates(marketRate("KTB_3Y", LocalDate.of(2026, 8, 13), "3.7810"))

        val snapshot = service(indicesEnabled = false).snapshot()

        assertThat(snapshot.fx?.quotes?.map { it.currency }).containsExactly("USD")
        assertThat(snapshot.rates.map { it.code }).containsExactly("KTB_3Y")
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
        stubRates(marketRate("KTB_3Y", LocalDate.of(2026, 8, 13), "3.7810"))

        val view = service().snapshot().rates.single()

        assertThat(view.value).isEqualByComparingTo("3.7810")
        assertThat(view.changeBp).isNull()
    }

    /**
     * 응답에 나가는 자리수를 못 박는다. Jackson이 BigDecimal 스케일을 보존하므로 계산 결과의
     * 스케일이 곧 전송 형식이다 — 컬럼에서 물려받은 채로 두면 1bp가 `-1.0000`으로 나가는데
     * KDoc은 `1.00`이라고 말한다. `isEqualByComparingTo`는 스케일을 안 보므로 여기서는 문자열로 본다.
     */
    @Test
    fun `bp 변동은 소수 둘째 자리로 고정해 내보낸다`() {
        stubRates(
            marketRate("KTB_3Y", LocalDate.of(2026, 8, 12), "3.7910"),
            marketRate("KTB_3Y", LocalDate.of(2026, 8, 13), "3.7810"),
        )

        assertThat(service().snapshot().rates.single().changeBp.toString()).isEqualTo("-1.00")
    }

    /**
     * 지표가 몇 종이든 쿼리는 한 번이다. 지표마다 부르면 운영 설정 6종에 원격 Postgres 왕복이 6번 난다.
     */
    @Test
    fun `지표가 여럿이어도 금리 조회는 한 번으로 끝난다`() {
        stubRates(
            marketRate("BASE_RATE", LocalDate.of(2026, 8, 11), "2.5000"),
            marketRate("KTB_3Y", LocalDate.of(2026, 8, 13), "3.7810"),
        )

        service().snapshot()

        verify(rateRepo, times(1)).findByRateCodeInAndQuoteDateBetween(
            anyCollection() ?: emptyList(),
            any(LocalDate::class.java) ?: LocalDate.EPOCH,
            any(LocalDate::class.java) ?: LocalDate.EPOCH,
        )
        // 지표별 조회가 끼어들면 여기서 깨진다
        verifyNoMoreInteractions(rateRepo)
        // 픽스처 설정 전량이다(한국 2종 + 미국 1종). 행이 없는 종목도 조회에는 들어가야 한다 —
        // 코드 목록은 설정에서 오고, 뭐가 있는지는 조회해 봐야 아는 것이라서다
        assertThat(rateQueries.single().first).containsExactlyInAnyOrder("BASE_RATE", "KTB_3Y", "US_FFR")
    }

    /**
     * **조회 창의 인자 순서를 값으로 못 박는다.** 매처로만 넘기면 `Between(codes, to, from)`으로
     * 뒤집는 변이가 통과한다 — 실제로 13개가 다 초록이었다. 운영에서 그 변이는
     * `BETWEEN to AND from`(to > from)이라 어떤 행도 안 걸려 금리 구간이 통째로 `[]`가 되고,
     * 그 화면은 "수집된 적 없음"과 구분되지 않는다. 창을 0일로 줄이는 변이도 여기서 걸린다.
     *
     * 시간대(KST)는 여기서 단언하지 않는다 — 개발 장비에서는 항상 참이라 무의미하고,
     * CI가 UTC면 하루 중 아홉 시간만 붉어지는 테스트가 된다.
     */
    @Test
    fun `금리 조회 창은 오늘까지 거슬러 30일이다`() {
        stubRates(marketRate("KTB_3Y", LocalDate.of(2026, 8, 13), "3.7810"))

        service().snapshot()

        val (_, from, to) = rateQueries.single()
        assertThat(from).isBefore(to)
        assertThat(ChronoUnit.DAYS.between(from, to)).isEqualTo(30)
    }

    /** 수집이 한 번도 안 된 지표는 행이 없다. 0으로 채우면 화면이 그걸 진짜 금리로 보여준다 */
    @Test
    fun `행이 없는 금리는 응답에서 빠진다`() {
        assertThat(service().snapshot().rates).isEmpty()
    }

    /**
     * **같은 응답 안에서 항목마다 기준일이 다르다.** 실측으로 기준금리 공표가 시장금리보다
     * 이틀 늦은 것이 확인됐다. 헤더에 기준일 하나를 두고 뭉뚱그리면 화면이 거짓말을 한다.
     *
     * 줄 순서도 여기서 함께 못 박는다 — 설정 순서다. 픽스처의 설정 순서는 사전순과도, 기준일
     * 순서와도, 아래 스텁을 쌓은 순서(= DB가 행을 주는 순서)와도 일부러 다르게 잡았다([service] 주석).
     */
    @Test
    fun `항목마다 자기 기준일을 달고 설정 순서로 실린다`() {
        // 설정 순서(KTB_3Y 먼저)와 **반대로** 쌓는다 — 묶음 결과의 맵을 그냥 도는 구현이면 여기서 깨진다
        stubRates(marketRate("BASE_RATE", LocalDate.of(2026, 8, 11), "2.5000"))
        stubRates(marketRate("KTB_3Y", LocalDate.of(2026, 8, 13), "3.7810"))

        val rates = service().snapshot().rates

        assertThat(rates.map { it.code }).containsExactly("KTB_3Y", "BASE_RATE")
        assertThat(rates.map { it.quoteDate })
            .containsExactly(LocalDate.of(2026, 8, 13), LocalDate.of(2026, 8, 11))
    }

    /**
     * **수집만 되고 화면에는 안 나오는 실패를 여기서 막는다.**
     *
     * 조회는 설정 목록에서 코드를 열거하는데, 그 목록이 소스별로 갈려 있다. `ecos`만 열거하면
     * FRED 종목은 조회 대상에 아예 안 들어가 DB에는 쌓이는데 화면에만 없고, 오류도 로그도 안 난다.
     * (계획이 실제로 "조회는 안 고쳐도 된다"고 적었다가 리뷰에서 잡힌 지점이다.)
     *
     * 줄 순서도 함께 못 박는다 — 한국 → 미국이고, 그게 화면 순서다. 두 목록을 미국부터 더하는
     * 변이는 여기서 깨진다.
     */
    @Test
    fun `한국과 미국 금리를 한 응답에 함께 싣는다`() {
        stubRates(
            marketRate("US_FFR", LocalDate.of(2026, 8, 12), "4.3300", source = "FRED"),
            marketRate("KTB_3Y", LocalDate.of(2026, 8, 13), "3.7810"),
        )

        val rates = service().snapshot().rates

        assertThat(rates.map { it.code }).containsExactly("KTB_3Y", "US_FFR")
        // 응답만 보면 스텁이 코드로 걸러 주기에 의존하게 된다. 묶음 조회에 미국 코드가 실제로
        // 넘어갔는지를 따로 본다 — 쿼리가 두 번으로 갈리는 변이도 single()이 잡는다
        assertThat(rateQueries.single().first).contains("US_FFR")
    }

    /**
     * **플래그가 off면 서버가 원자재를 아예 싣지 않는다 — 빈 리스트가 아니라 null이다.**
     * 지수와 같은 관례다(MarketSnapshot KDoc): `[]`는 "조회는 했는데 데이터가 없다"는 뜻이라
     * 프런트가 그걸 렌더하면 감춰야 할 탭이 빈 표로 나간다.
     *
     * **조회조차 안 하는 것까지 못 박는다** — 데이터가 **있는** 상태로 스텁해 두고 검증한다.
     * 스텁을 비워 두면 "안 불렀다"와 "불렀는데 없더라"가 구분되지 않는다.
     *
     * 지수가 그대로 실리는지도 함께 본다: 플래그 확인을 `snapshot()` 맨 앞의 조기 반환으로 넣는
     * 변이가 여기서 깨진다(원자재 하나 끄려다 다섯 탭이 다 사라진다).
     */
    @Test
    fun `원자재 플래그가 off면 조회하지도 싣지도 않는다`() {
        stubLatest(indexQuote("KOSPI", "2500.00"))
        stubCommodities(commodityQuote("WTI"))

        val snapshot = service(commoditiesEnabled = false).snapshot()

        assertThat(snapshot.commodities).isNull()
        assertThat(snapshot.flags.commoditiesEnabled).isFalse()
        verifyNoInteractions(commodityRepo)
        // 원자재만 사라져야 한다
        assertThat(snapshot.domestic?.map { it.code }).containsExactly("KOSPI")
    }

    /**
     * 플래그가 켜져 있는데 수집이 한 번도 안 됐으면 `[]`다 — **null이 아니다.**
     * 이 둘이 뭉개지면 "약관 때문에 감췄다"와 "아직 안 들어왔다"를 화면이 구분할 수 없다.
     */
    @Test
    fun `플래그가 on인데 데이터가 없으면 빈 리스트다`() {
        val snapshot = service().snapshot()

        assertThat(snapshot.commodities).isNotNull()
        assertThat(snapshot.commodities).isEmpty()
        assertThat(snapshot.flags.commoditiesEnabled).isTrue()
    }

    /** 수집된 적 없는 종목은 빠진다. 0으로 채우면 화면이 그걸 진짜 시세로 보여준다 */
    @Test
    fun `행이 없는 원자재는 응답에서 빠진다`() {
        stubCommodities(commodityQuote("WTI"))

        assertThat(service().snapshot().commodities?.map { it.code }).containsExactly("WTI")
    }

    /**
     * **줄 순서는 설정 순서다.** 픽스처의 설정 순서(WTI → COPPER → ALL_INDEX)는 사전순
     * (ALL_INDEX → COPPER → WTI)과 정확히 반대이고, 거래일 순서와도, 아래 스텁을 쌓은 순서
     * (= DB가 행을 주는 순서)와도 다르게 잡았다.
     *
     * `sortedBy { it.code }`로 정렬하는 변이가 여기서 깨진다 — 운영에서 그 변이는 종합지수가
     * 맨 앞에 오고 에너지 3종이 농산물 사이로 흩어져, 화면이 섹션을 가르는 근거가 사라진다.
     */
    @Test
    fun `원자재를 설정 순서로 싣는다`() {
        stubCommodities(
            commodityQuote("COPPER", date = LocalDate.of(2026, 6, 1), unit = "USD/MT", frequency = "M"),
            commodityQuote("ALL_INDEX", date = LocalDate.of(2026, 7, 1), unit = "index", frequency = "M"),
            commodityQuote("WTI", date = LocalDate.of(2026, 8, 13)),
        )

        val commodities = service().snapshot().commodities!!

        assertThat(commodities.map { it.code }).containsExactly("WTI", "COPPER", "ALL_INDEX")
        // 기준일 정렬(오름·내림 어느 쪽이든)로 바뀌는 변이도 함께 잡는다
        assertThat(commodities.map { it.tradeDate }).containsExactly(
            LocalDate.of(2026, 8, 13), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1),
        )
    }

    /**
     * **단위와 주기를 저장된 값 그대로 내보낸다.** 화면이 섹션을 가르고(D/M) 숫자 옆에 단위를
     * 붙이는 근거가 이 둘이다. `USc/lb`를 `USD/lb`로 "정리"하는 정규화가 들어오면 한 글자 차이에
     * 100배가 틀리고, 숫자는 그럴듯해서 눈으로는 안 걸린다.
     */
    @Test
    fun `단위와 주기와 가격을 그대로 싣는다`() {
        stubCommodities(
            commodityQuote("WTI", price = "70.1234"),
            commodityQuote("COPPER", date = LocalDate.of(2026, 6, 1), price = "9000.0000", unit = "USc/lb", frequency = "M"),
        )

        val commodities = service().snapshot().commodities!!

        assertThat(commodities[0].unit).isEqualTo("USD/bbl")
        assertThat(commodities[0].frequency).isEqualTo("D")
        assertThat(commodities[0].price).isEqualByComparingTo("70.1234")
        assertThat(commodities[1].unit).isEqualTo("USc/lb")
        assertThat(commodities[1].frequency).isEqualTo("M")
    }

    /**
     * **`0`(무변동)을 null로 뭉개지 않는다.** 화면은 이 값을 "보합"으로 찍어야 하고,
     * null로 바뀌면 "직전 값 없음"이 되어 등락 칸이 통째로 비어 버린다.
     * 등락률도 함께 본다 — 값 쪽만 살리는 부분 변이가 있을 수 있다.
     */
    @Test
    fun `전일대비가 0인 행은 0으로 나간다`() {
        stubCommodities(commodityQuote("WTI", changeValue = "0.0000", changeRate = "0.0000"))

        val view = service().snapshot().commodities!!.single()

        assertThat(view.changeValue).isNotNull()
        assertThat(view.changeValue).isEqualByComparingTo("0")
        assertThat(view.changeRate).isNotNull()
        assertThat(view.changeRate).isEqualByComparingTo("0")
    }

    /**
     * 첫 관측처럼 비교할 직전 값이 없으면 저장된 값이 null이고, 그대로 null로 나가야 한다.
     * **`?: BigDecimal.ZERO`로 채우는 변이가 여기서 깨진다** — 0은 "안 움직였다"는 뜻이라
     * 위 테스트의 진짜 0과 구분되지 않게 된다(AF-104가 이 구분을 놓쳐 사고를 냈다).
     */
    @Test
    fun `직전 값이 없는 원자재는 전일대비가 null이다`() {
        stubCommodities(commodityQuote("WTI", changeValue = null, changeRate = null))

        val view = service().snapshot().commodities!!.single()

        assertThat(view.changeValue).isNull()
        assertThat(view.changeRate).isNull()
    }

    /**
     * 종목이 몇 종이든 쿼리는 한 번이다. 종목마다 부르면 원격 Postgres 왕복이 16번(금이 붙으면 17번) 난다.
     *
     * **설정 목록 셋을 다 열거하는지도 함께 못 박는다.** `fredDaily`만 읽는 구현은 월간 13종이
     * 조회 대상에 아예 안 들어가고, 증상은 "수집은 되는데 화면에 없다"이다 — 오류도 로그도 안 난다.
     * (AF-FRED가 금리에서 정확히 이 실수를 했다.)
     */
    @Test
    fun `종목이 여럿이어도 원자재 조회는 한 번으로 끝난다`() {
        stubCommodities(
            commodityQuote("WTI"),
            commodityQuote("COPPER", date = LocalDate.of(2026, 6, 1), unit = "USD/MT", frequency = "M"),
        )

        service().snapshot()

        verify(commodityRepo, times(1)).findLatestByCodes(anyCollection() ?: emptyList())
        // 종목별 조회가 끼어들면 여기서 깨진다
        verifyNoMoreInteractions(commodityRepo)
        // 픽스처 설정 전량이다(일간 1 + 월간 2). 행이 없는 종목도 조회에는 들어가야 한다
        assertThat(commodityQueries.single()).containsExactlyInAnyOrder("WTI", "COPPER", "ALL_INDEX")
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
     * 그래서 매처로 받고 넘어온 인자는 [rateQueries]에 직접 적어 둔다. `ArgumentCaptor.capture()`는
     * null을 돌려주는데 인자가 코틀린 non-null이라 검증 지점에서 NPE가 난다([stubLatest]와 같은 이유).
     *
     * `?: `가 붙은 이유: 매처도 null을 돌려주는데 인자가 코틀린 non-null이라 코틀린이 호출 지점에
     * 널 검사를 넣는다. 그대로 두면 스텁 지점에서 NPE가 나고, 매처가 큐에 남아 **다음 테스트가**
     * `InvalidUseOfMatchersException`으로 깨진다 — 무엇이 틀렸는지가 아니라 엉뚱한 테스트가 붉어진다.
     * 뒤의 값은 쓰이지 않는다(매처가 이미 등록됐다).
     *
     * 요청한 코드로 걸러 돌려준다 — 리포지터리가 하는 일이 그것이고, 걸러 두면 엉뚱한 코드를
     * 넘기는 변이가 빈 결과로 드러난다.
     */
    private fun stubRateQuery() {
        `when`(
            rateRepo.findByRateCodeInAndQuoteDateBetween(
                anyCollection() ?: emptyList(),
                any(LocalDate::class.java) ?: LocalDate.EPOCH,
                any(LocalDate::class.java) ?: LocalDate.EPOCH,
            ),
        ).thenAnswer { invocation ->
            val codes = invocation.getArgument<Collection<String>>(0).toList()
            rateQueries += Triple(codes, invocation.getArgument(1), invocation.getArgument(2))
            rateRows.filter { it.rateCode in codes }
        }
    }

    /** 스텁이 돌려줄 행을 쌓는다. 여러 번 불러 지표를 섞을 수 있고, 쌓은 순서가 곧 DB가 준 순서다 */
    private fun stubRates(vararg rows: MarketRateEntity) {
        rateRows += rows
    }

    /**
     * 요청한 코드로 걸러 돌려준다 — 리포지터리가 하는 일이 그것이고, 걸러 두면 엉뚱한 코드를
     * 넘기는 변이(설정 목록 하나만 열거하는 등)가 빈 결과로 드러난다.
     *
     * **"코드마다 최신 한 행"이라는 규칙 자체는 여기서 검증되지 않는다.** 그 규칙은 JPQL
     * (`findLatestByCodes`의 `NOT EXISTS`) 안에만 있고, 이 스텁은 그 의미를 손으로 다시 적는 것이라
     * 쿼리를 망가뜨려도 여기는 초록이다. 그래서 H2로 도는
     * `MarketCommodityQuoteJpaRepositoryTest`가 그 규칙을 따로 문다 — 조회 서비스를 고칠 때
     * 그 파일도 같이 볼 것.
     */
    private fun stubCommodityQuery() {
        `when`(commodityRepo.findLatestByCodes(anyCollection())).thenAnswer { invocation ->
            val codes = invocation.getArgument<Collection<String>>(0).toList()
            commodityQueries += codes
            commodityRows.filter { it.code in codes }
        }
    }

    /** 쌓은 순서가 곧 DB가 준 순서다 — 설정 순서와 일부러 다르게 쌓아 정렬 변이를 잡는다 */
    private fun stubCommodities(vararg rows: MarketCommodityQuoteEntity) {
        commodityRows += rows
    }

    private fun service(indicesEnabled: Boolean = true, commoditiesEnabled: Boolean = true): MarketQueryService {
        val properties = MarketIndexProperties().apply {
            domestic = listOf(MarketIndexProperties.DomesticIndex().apply { code = "KOSPI" })
            overseas = listOf(MarketIndexProperties.OverseasIndex().apply { code = "SPX" })
        }
        // **일부러 운영 설정 순서(BASE_RATE가 앞)와 반대로 둔다.** 운영 순서를 그대로 베끼면
        // 설정 순서가 사전순과도, 기준일 오름차순과도 우연히 같아져 정렬 회귀가 통과해 버린다 —
        // 실제로 결과에 `.sortedBy { it.quoteDate }`를 넣어도 13개가 다 초록이었다.
        // 뒤집어 두면 세 가지 변이가 전부 여기서 깨진다: 코드 사전순 정렬, 기준일 정렬,
        // 그리고 설정 대신 groupBy 맵(= DB가 준 순서)을 도는 것.
        val rateProperties = MarketRateProperties().apply {
            ecos = listOf("KTB_3Y", "BASE_RATE").map { seriesCode ->
                MarketRateProperties.EcosSeries().apply { code = seriesCode }
            }
            // **미국 종목을 반드시 함께 채운다.** 설정이 소스별로 갈려 있어서, ecos만 채운 픽스처로는
            // 조회가 `ecos`만 열거하는 구현이 전부 초록으로 통과한다 — 그 구현의 증상은
            // "수집은 되는데 화면에 없다"이고 오류도 로그도 안 난다
            fred = listOf(MarketRateProperties.FredSeries().apply { code = "US_FFR" })
        }
        // **설정 순서를 사전순과 반대로 둔다** — 운영 설정(WTI·BRENT·NATGAS → 금속 → 농산물 →
        // 종합지수)도 사전순이 아니고, 사전순 정렬 변이가 통과하지 않아야 한다.
        // **월간 목록을 반드시 함께 채운다**: 설정이 목록 셋으로 갈려 있어 fredDaily만 채운
        // 픽스처로는 `fredDaily`만 열거하는 구현이 전부 초록이다(증상은 "수집은 되는데 화면에 없다").
        // fsc는 운영과 같이 비워 둔다 — 금이 붙어도 이 코드가 안 바뀌는 것이 요구사항이다.
        val commodityProperties = CommodityProperties().apply {
            fredDaily = listOf(commodityItem("WTI", "USD/bbl", "D"))
            fredMonthly = listOf(
                commodityItem("COPPER", "USD/MT", "M"),
                commodityItem("ALL_INDEX", "index", "M"),
            )
        }
        val queryProperties = MarketQueryProperties().apply {
            this.indicesEnabled = indicesEnabled
            this.commoditiesEnabled = commoditiesEnabled
        }
        return MarketQueryService(
            indexRepo,
            properties,
            fxRepo,
            rateRepo,
            rateProperties,
            commodityRepo,
            commodityProperties,
            queryProperties,
        )
    }

    private fun commodityItem(code: String, unit: String, frequency: String) =
        CommodityProperties.CommodityItem().apply {
            this.code = code
            this.seriesId = "SERIES_$code"
            this.unit = unit
            this.frequency = frequency
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

    private fun marketRate(
        code: String,
        date: LocalDate,
        value: String,
        source: String = "ECOS",
    ) = MarketRateEntity(
        id = UUID.randomUUID(),
        rateCode = code,
        quoteDate = date,
        rateValue = BigDecimal(value),
        source = source,
        collectedAt = LocalDateTime.of(2026, 8, 13, 18, 10),
    )

    /**
     * `unit`·`frequency`는 **행에 저장된 값**이지 설정값이 아니다 — 조회가 설정에서 다시 가져오면
     * 단위 표기를 고친 날 과거 행이 새 단위로 둔갑한다. 그래서 픽스처의 기본 단위도 설정과 같은
     * 값을 쓰되, 다르게 주는 테스트를 함께 둔다.
     */
    private fun commodityQuote(
        code: String,
        date: LocalDate = LocalDate.of(2026, 8, 13),
        price: String = "70.0000",
        unit: String = "USD/bbl",
        frequency: String = "D",
        changeValue: String? = "1.0000",
        changeRate: String? = "1.4493",
    ) = MarketCommodityQuoteEntity(
        id = UUID.randomUUID(),
        code = code,
        tradeDate = date,
        price = BigDecimal(price),
        unit = unit,
        frequency = frequency,
        // price와 **다른 값**이어야 한다. 같게 두면 뷰가 price를 prevClose에서 가져오도록
        // 잘못 바뀌어도 테스트가 통과한다 (지수 픽스처가 같은 이유로 같은 장치를 둔다)
        prevClose = BigDecimal("69.0000"),
        changeValue = changeValue?.let { BigDecimal(it) },
        changeRate = changeRate?.let { BigDecimal(it) },
        source = "FRED",
        collectedAt = LocalDateTime.of(2026, 8, 13, 18, 20),
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
