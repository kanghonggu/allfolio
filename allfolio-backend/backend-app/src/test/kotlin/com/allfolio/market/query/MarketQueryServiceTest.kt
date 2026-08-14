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

    /** 묶음 조회에 넘어간 코드들 — 호출 한 번당 한 줄 */
    private val requestedCodes = mutableListOf<List<String>>()

    /** 금리 스텁이 돌려줄 행. 리포지터리가 순서를 보장하지 않으므로 넣은 순서 그대로 돌려준다 */
    private val rateRows = mutableListOf<MarketRateEntity>()

    /** 금리 묶음 조회에 넘어간 (코드들, from, to) — 호출 한 번당 한 줄 */
    private val rateQueries = mutableListOf<Triple<List<String>, LocalDate, LocalDate>>()

    private val today = LocalDate.of(2026, 8, 13)
    private val yesterday = LocalDate.of(2026, 8, 12)

    init {
        stubRateQuery()
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
        assertThat(rateQueries.single().first).containsExactlyInAnyOrder("BASE_RATE", "KTB_3Y")
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

    private fun service(indicesEnabled: Boolean = true): MarketQueryService {
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
        }
        val queryProperties = MarketQueryProperties().apply { this.indicesEnabled = indicesEnabled }
        return MarketQueryService(indexRepo, properties, fxRepo, rateRepo, rateProperties, queryProperties)
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
