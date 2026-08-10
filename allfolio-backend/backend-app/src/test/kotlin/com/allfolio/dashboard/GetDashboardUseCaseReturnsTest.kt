package com.allfolio.dashboard

import com.allfolio.snapshot.infrastructure.entity.PerformanceDailyEntity
import com.allfolio.snapshot.infrastructure.entity.SnapshotDailyId
import com.allfolio.snapshot.infrastructure.repository.BenchmarkDailyJpaRepository
import com.allfolio.snapshot.infrastructure.repository.PerformanceDailyJpaRepository
import com.allfolio.snapshot.infrastructure.repository.RiskDailyJpaRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.domain.cashflow.FlowType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * QA P1 #6/#7 — 대시보드 기간 수익률을 flow-aware TWR로 통일.
 * 계좌 연동 초기 편입(DEPOSIT flow 대응)이 수익으로 오인되어 +2060%가 나오던 증상 회귀 방지.
 */
class GetDashboardUseCaseReturnsTest {

    private val userId = UUID.randomUUID()
    private val today = LocalDate.now()

    private val assetRepository = mock(AssetRepository::class.java)
    private val performanceRepo = mock(PerformanceDailyJpaRepository::class.java)
    private val riskRepo = mock(RiskDailyJpaRepository::class.java)
    private val benchmarkRepo = mock(BenchmarkDailyJpaRepository::class.java)
    private val fx = object : FxConverter {
        override fun toKrw(amount: BigDecimal, currency: String): BigDecimal = amount
    }

    private class FixedCashFlows(private val flows: List<CashFlow>) : CashFlowRepository {
        override fun save(cashFlow: CashFlow): CashFlow = cashFlow
        override fun findById(id: UUID): CashFlow? = null
        override fun findByUserIdAndPeriod(userId: UUID, from: LocalDate, to: LocalDate) =
            flows.filter { it.flowDate in from..to }
        override fun findByUserId(userId: UUID) = flows
        override fun delete(id: UUID) = Unit
        override fun deleteByAccountId(accountId: UUID) = Unit
    }

    private fun perf(date: LocalDate, nav: String) = PerformanceDailyEntity(
        id = SnapshotDailyId(userId, userId, date),
        nav = BigDecimal(nav),
        dailyReturn = BigDecimal.ZERO,
        cumulativeReturn = BigDecimal.ZERO,
        benchmarkReturn = null,
        alpha = null,
    )

    private fun deposit(date: LocalDate, amountKrw: String) = CashFlow.create(
        userId = userId, accountId = null, flowDate = date, type = FlowType.DEPOSIT,
        amount = BigDecimal(amountKrw), currency = "KRW", amountKrw = BigDecimal(amountKrw), memo = null,
    )

    private fun useCase(series: List<PerformanceDailyEntity>, flows: List<CashFlow>): GetDashboardUseCase {
        `when`(assetRepository.findByUserId(userId)).thenReturn(emptyList())
        `when`(performanceRepo.findByIdPortfolioIdAndIdDateBetween(any() ?: userId, any() ?: today, any() ?: today))
            .thenReturn(series)
        return GetDashboardUseCase(
            assetRepository, performanceRepo, riskRepo, benchmarkRepo, fx, FixedCashFlows(flows),
        )
    }

    @Test
    fun `계좌 연동 초기 편입은 수익이 아니다 - QA +2060% 재현 케이스`() {
        // day-40: 기존 소액 10만원 → day-10: 계좌 연동으로 3,800만원 (DEPOSIT flow 3,790만원 동반)
        // → day-1: 3,850만원. 단순 NAV 비율이면 +38,400%, TWR이면 +1.32%.
        val series = listOf(
            perf(today.minusDays(40), "100000"),
            perf(today.minusDays(10), "38000000"),
            perf(today.minusDays(1), "38500000"),
        )
        val flows = listOf(deposit(today.minusDays(10), "37900000"))

        val metrics = useCase(series, flows).execute(userId).portfolio.metrics

        val r1m = metrics.return1m!!.value
        assertThat(r1m).isCloseTo(BigDecimal("1.32"), within(BigDecimal("0.05")))
        assertThat(r1m.abs()).isLessThan(BigDecimal("100"))
    }

    @Test
    fun `커버리지 미달 기간은 null - performance 리포트와 동일 규칙 (QA 후속 3)`() {
        // 10일치 시계열 — 1M/3M 윈도우를 못 덮으므로 부분 시계열로 왜곡된 값을 만들지 않고
        // null(FE '데이터 부족')이어야 한다. 기존: 전체 기간 TWR을 그대로 반환(+2060% 유형).
        var nav = BigDecimal("1000000")
        val series = (9 downTo 0).map { d ->
            val p = perf(today.minusDays(d.toLong()), nav.toPlainString())
            nav = nav.multiply(BigDecimal("1.01"))
            p
        }

        val metrics = useCase(series, emptyList()).execute(userId).portfolio.metrics

        assertThat(metrics.return1m).isNull()
        assertThat(metrics.return3m).isNull()
    }

    @Test
    fun `윈도우 시작 이전의 마지막 관측을 기저로 쓴다`() {
        val series = listOf(
            perf(today.minusDays(40), "1000000"),
            perf(today.minusDays(5), "1100000"),
        )

        val metrics = useCase(series, emptyList()).execute(userId).portfolio.metrics

        assertThat(metrics.return1m!!.value).isCloseTo(BigDecimal("10.00"), within(BigDecimal("0.05")))
    }
}
