package com.allfolio.api.portfolio

import com.allfolio.api.cache.SnapshotCacheRepository
import com.allfolio.fx.CurrencyConverter
import com.allfolio.fx.FxRateService
import com.allfolio.snapshot.infrastructure.entity.PerformanceDailyEntity
import com.allfolio.snapshot.infrastructure.entity.SnapshotDailyId
import com.allfolio.snapshot.infrastructure.repository.PerformanceDailyJpaRepository
import com.allfolio.trade.domain.TradeType
import com.allfolio.trade.infrastructure.entity.TradeRawEntity
import com.allfolio.trade.infrastructure.repository.TradeRawJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 거래 파이프라인 포트폴리오의 스냅샷 리스크는 risk_daily가 아니라 NAV + 매매 대금 플로우에서 계산한다.
 * 이 포트폴리오엔 현금이 없어 daily_return 기반이면 매수일이 수익, 매도일이 손실로 잡힌다.
 */
class PortfolioSnapshotQueryControllerRiskTest {

    private val userId = UUID.randomUUID()
    private val portfolioId = UUID.randomUUID()
    private val day0 = LocalDate.of(2026, 9, 1)

    private val performanceRepository = mock(PerformanceDailyJpaRepository::class.java)
    private val tradeRepository = mock(TradeRawJpaRepository::class.java)

    private val controller = PortfolioSnapshotQueryController(
        performanceRepository,
        tradeRepository,
        CurrencyConverter(mock(FxRateService::class.java)), // 전부 KRW라 환율을 안 본다
        mock(SnapshotCacheRepository::class.java),
    )

    private fun perf(day: Long, nav: String, tenantId: UUID = userId) = PerformanceDailyEntity(
        id = SnapshotDailyId(tenantId, portfolioId, day0.plusDays(day)),
        nav = BigDecimal(nav),
        dailyReturn = BigDecimal.ZERO,
        cumulativeReturn = BigDecimal.ZERO,
        benchmarkReturn = null,
        alpha = null,
    )

    private fun trade(day: Long, type: TradeType, qty: String, price: String) = TradeRawEntity(
        id = UUID.randomUUID(), portfolioId = portfolioId, assetId = ASSET,
        tradeType = type, quantity = BigDecimal(qty), price = BigDecimal(price), fee = BigDecimal.ZERO,
        tradeCurrency = "KRW", executedAt = day0.plusDays(day).atTime(10, 0), createdAt = LocalDateTime.now(),
    )

    private fun latest(series: List<PerformanceDailyEntity>, trades: List<TradeRawEntity>): PortfolioSnapshotResponse {
        val mine = series.filter { it.id.tenantId == userId }
        `when`(performanceRepository.findTopByIdTenantIdAndIdPortfolioIdOrderByIdDateDesc(userId, portfolioId))
            .thenReturn(mine.maxBy { it.id.date })
        `when`(performanceRepository.findByIdPortfolioIdAndIdDateBetween(any() ?: portfolioId, any() ?: day0, any() ?: day0))
            .thenReturn(series)
        `when`(tradeRepository.findByPortfolioIdAndExecutedAtLessThanEqualOrderByExecutedAtAsc(any() ?: portfolioId, any() ?: LocalDateTime.MIN))
            .thenReturn(trades)
        return controller.getLatestSnapshot(portfolioId, userId).body!!
    }

    // 0일 10주 매수(@100,000) → 1일 10주 추가 매수 → 2일 가격 −10% → 3일 5주 매도(@90,000)
    private val series = listOf(
        perf(0, "1000000"),
        perf(1, "2000000"),
        perf(2, "1800000"),
        perf(3, "1350000"),
    )
    private val trades = listOf(
        trade(0, TradeType.BUY, "10", "100000"),
        trade(1, TradeType.BUY, "10", "100000"),
        trade(3, TradeType.SELL, "5", "90000"),
    )

    @Test
    fun `매수·매도는 수익·손실이 아니다 — 실제 가격 하락 −10%만 MDD로 잡힌다`() {
        // 구간 수익률: 0(매수), −10%, 0(매도) → MDD −10%.
        // daily_return이면 +100%, −10%, −25% → MDD −32.5%.
        val risk = latest(series, trades).risk!!

        assertThat(risk.maxDrawdown).isEqualByComparingTo("-0.1")
    }

    @Test
    fun `가격이 안 움직이면 매수·매도가 있어도 변동성·VaR·MDD가 0이다`() {
        val flat = listOf(perf(0, "1000000"), perf(1, "2000000"), perf(2, "2000000"), perf(3, "1500000"))
        val flatTrades = listOf(
            trade(0, TradeType.BUY, "10", "100000"),
            trade(1, TradeType.BUY, "10", "100000"),
            trade(3, TradeType.SELL, "5", "100000"),
        )

        val risk = latest(flat, flatTrades).risk!!

        assertThat(risk.volatility).isEqualByComparingTo("0")
        assertThat(risk.var95).isEqualByComparingTo("0")
        assertThat(risk.maxDrawdown).isEqualByComparingTo("0")
    }

    @Test
    fun `구간 수익률이 2건 미만이면 리스크를 0으로 채우지 않고 null로 준다`() {
        val response = latest(series.take(2), trades.take(2))

        assertThat(response.performance.nav).isEqualByComparingTo("2000000")
        assertThat(response.risk).isNull()
    }

    @Test
    fun `30일 넘게 비었다가 찍힌 날은 이전 날의 리스크를 오늘 것으로 내지 않는다`() {
        // 0~3일 리스크는 있다. 50일째 창 (20, 50]엔 구간이 하나뿐 → 그날 리스크는 없다(null).
        // 날짜 확인이 빠지면 3일의 MDD −10%가 50일 것으로 나간다.
        val response = latest(series + perf(50, "1400000"), trades)

        assertThat(response.date).isEqualTo(day0.plusDays(50))
        assertThat(response.risk).isNull()
    }

    @Test
    fun `다른 tenant의 같은 portfolio 행은 리스크 시계열에 섞이지 않는다`() {
        // 2일에 다른 tenant 행(NAV 1원)이 섞이면 −99.99% 낙폭이 생긴다
        val risk = latest(series + perf(2, "1", tenantId = UUID.randomUUID()), trades).risk!!

        assertThat(risk.maxDrawdown).isEqualByComparingTo("-0.1")
    }

    private companion object {
        val ASSET: UUID = UUID.randomUUID()
    }
}
