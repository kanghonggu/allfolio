package com.allfolio.dashboard

import com.allfolio.fx.CurrencyConverter
import com.allfolio.fx.FxRateService
import com.allfolio.snapshot.infrastructure.entity.PerformanceDailyEntity
import com.allfolio.snapshot.infrastructure.entity.SnapshotDailyId
import com.allfolio.snapshot.infrastructure.repository.PerformanceDailyJpaRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.BenchmarkDailyStore
import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.domain.benchmark.BenchmarkType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * 대시보드 KOSPI YTD는 리포트와 같은 규칙(IndexPeriodReturn) — **1월 1일 이전 마지막 종가**가 기저다.
 * 예전엔 `1월 1일 + 5일` 이하 마지막 종가라 그해 첫 주의 움직임이 빠져 리포트와 다른 값이 나왔다.
 */
class GetDashboardUseCaseKospiYtdTest {

    private val userId = UUID.randomUUID()
    private val today = LocalDate.now()
    private val jan1 = today.withDayOfYear(1)

    private val assetRepository = mock(AssetRepository::class.java)
    private val performanceRepo = mock(PerformanceDailyJpaRepository::class.java)
    private val fx = object : FxConverter {
        override fun toKrw(amount: BigDecimal, currency: String): BigDecimal = amount
        override fun rateOf(currency: String): BigDecimal = BigDecimal.ONE
    }

    private object IdentityFxRates : FxRateService {
        override fun getUsdtToKrw(): BigDecimal = BigDecimal.ONE
        override fun setUsdtToKrw(rate: BigDecimal) = Unit
        override fun getCryptoToKrw(symbol: String): BigDecimal = BigDecimal.ONE
        override fun setCryptoToKrw(symbol: String, rate: BigDecimal) = Unit
    }

    /** benchmark_daily를 흉내 낸다 — 실제 SQL처럼 [from, to]로 자른다 */
    private class FixedBenchmarks(private val kospi: List<Pair<LocalDate, BigDecimal>>) : BenchmarkDailyStore {
        override fun latestDate(type: BenchmarkType): LocalDate? = null
        override fun upsert(type: BenchmarkType, rows: List<Pair<LocalDate, BigDecimal>>) = Unit
        override fun series(type: BenchmarkType, from: LocalDate, to: LocalDate) =
            if (type == BenchmarkType.KOSPI) kospi.filter { it.first in from..to } else emptyList()
    }

    private fun perf(date: LocalDate, nav: String) = PerformanceDailyEntity(
        id = SnapshotDailyId(userId, userId, date),
        nav = BigDecimal(nav),
        dailyReturn = BigDecimal.ZERO,
        cumulativeReturn = BigDecimal.ZERO,
        benchmarkReturn = null,
        alpha = null,
    )

    @Test
    fun `KOSPI YTD 기저는 1월 1일 이전 마지막 종가다 - 첫 주의 움직임을 빼지 않는다`() {
        assumeTrue(today.isAfter(jan1.plusDays(10))) { "연초 열흘 안엔 아래 1월 6일 종가가 오늘 이후가 된다" }
        // 포트폴리오: 전년 12/31 100만 → 오늘 110만 = YTD +10.00%
        `when`(assetRepository.findByUserId(userId)).thenReturn(emptyList())
        `when`(performanceRepo.findByIdPortfolioIdAndIdDateBetween(any() ?: userId, any() ?: today, any() ?: today))
            .thenReturn(listOf(perf(jan1.minusDays(1), "1000000"), perf(today, "1100000")))
        // KOSPI: 전년 12/30 4000 → 1/2 4400 → 1/6 4800 → 오늘 5000
        //  - 1월 1일 이전 마지막 종가(12/30 4000) 기준 = +25.00% → 포트폴리오 대비 −15.00
        //  - 예전 규칙(1/6 이하 마지막 = 4800) 기준 = +4.17% → +5.83
        val kospi = listOf(
            jan1.minusDays(2) to BigDecimal("4000"),
            jan1.plusDays(1) to BigDecimal("4400"),
            jan1.plusDays(5) to BigDecimal("4800"),
            today to BigDecimal("5000"),
        )
        val useCase = GetDashboardUseCase(
            assetRepository, performanceRepo, FixedBenchmarks(kospi), fx,
            mock(CashFlowRepository::class.java), CurrencyConverter(IdentityFxRates), { null },
        )

        val ytd = useCase.execute(userId).portfolio.metrics.returnYtd!!

        assertThat(ytd.value).isEqualByComparingTo("10.00")
        assertThat(ytd.benchmarkVsKospi).isEqualByComparingTo("-15.00")
    }
}
