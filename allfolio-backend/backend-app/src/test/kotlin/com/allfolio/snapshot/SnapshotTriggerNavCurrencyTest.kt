package com.allfolio.snapshot

import com.allfolio.api.cache.SnapshotCacheRepository
import com.allfolio.dlq.DlqService
import com.allfolio.fx.CurrencyConverter
import com.allfolio.fx.FxRateService
import com.allfolio.fx.UsdQuoteRef
import com.allfolio.metrics.BrokerMetrics
import com.allfolio.service.SnapshotTriggerService
import com.allfolio.snapshot.application.GenerateDailySnapshotUseCase
import com.allfolio.snapshot.infrastructure.entity.PerformanceDailyEntity
import com.allfolio.snapshot.infrastructure.entity.PositionDailyEntity
import com.allfolio.snapshot.infrastructure.entity.PositionDailyId
import com.allfolio.snapshot.infrastructure.entity.RiskDailyEntity
import com.allfolio.snapshot.infrastructure.entity.SnapshotDailyId
import com.allfolio.snapshot.infrastructure.repository.PerformanceDailyJpaRepository
import com.allfolio.snapshot.infrastructure.repository.PositionDailyJpaRepository
import com.allfolio.trade.domain.TradeType
import com.allfolio.trade.infrastructure.entity.TradeRawEntity
import com.allfolio.trade.infrastructure.outbox.OutboxRepository
import com.allfolio.trade.infrastructure.repository.TradeRawJpaRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * AF-106 — 통화별 평가액 쓰기가 스냅샷 경로에 붙었는지, 그리고 그 실패가
 * 스냅샷을 되돌리지 못하는지 고정한다.
 *
 * **`try/catch`를 지우면 [통화별 평가액 쓰기가 실패해도 스냅샷은 살아남는다]가 깨져야 한다.**
 * 깨지지 않는다면 이 테스트는 아무것도 지키지 못하고 있는 것이다.
 */
class SnapshotTriggerNavCurrencyTest {

    private val tenantId = UUID.randomUUID()
    private val portfolioId = UUID.randomUUID()
    private val assetId = UUID.randomUUID()
    private val date = LocalDate.of(2026, 8, 14)

    /** USD 1400원 고정 — 고시가 없어 거래소 시세 근사로 떨어지는 경로 */
    private val fxRates = object : FxRateService {
        override fun getUsdtToKrw(): BigDecimal = BigDecimal("1400")
        override fun setUsdtToKrw(rate: BigDecimal) = Unit
        override fun getCryptoToKrw(symbol: String): BigDecimal = BigDecimal.ONE
        override fun setCryptoToKrw(symbol: String, rate: BigDecimal) = Unit
        override fun usdQuoteRef(): UsdQuoteRef? = null
    }

    private val tradeRepository = mock(TradeRawJpaRepository::class.java)
    private val performanceRepository = mock(PerformanceDailyJpaRepository::class.java)
    private val useCase = mock(GenerateDailySnapshotUseCase::class.java)
    private val cache = mock(SnapshotCacheRepository::class.java)
    private val positionRepository = mock(PositionDailyJpaRepository::class.java)
    private val navCurrencyStore = mock(NavCurrencyDailyStore::class.java)

    // 실제 BrokerMetrics — recordSnapshotLatency 가 람다를 실제로 실행해야 한다
    private val metrics = BrokerMetrics(
        SimpleMeterRegistry(),
        mock(DlqService::class.java),
        mock(OutboxRepository::class.java),
    )

    private fun performance() = PerformanceDailyEntity(
        id = SnapshotDailyId(tenantId, portfolioId, date),
        nav = BigDecimal("280000"),
        dailyReturn = BigDecimal.ZERO,
        cumulativeReturn = BigDecimal.ZERO,
        benchmarkReturn = null,
        alpha = null,
    )

    private fun risk() = RiskDailyEntity(
        id = SnapshotDailyId(tenantId, portfolioId, date),
        volatility = BigDecimal.ZERO,
        annualizedVolatility = BigDecimal.ZERO,
        var95 = BigDecimal.ZERO,
        maxDrawdown = BigDecimal.ZERO,
    )

    private fun service(): SnapshotTriggerService {
        // USD 200달러짜리 거래 하나 — 환산 전 통화를 아는 유일한 자리
        val trade = TradeRawEntity(
            id = UUID.randomUUID(),
            portfolioId = portfolioId,
            assetId = assetId,
            tradeType = TradeType.BUY,
            quantity = BigDecimal("10"),
            price = BigDecimal("200"),
            fee = BigDecimal.ZERO,
            tradeCurrency = "USD",
            executedAt = date.atTime(10, 0),
            createdAt = LocalDateTime.now(),
        )
        `when`(
            tradeRepository.findByPortfolioIdAndExecutedAtLessThanEqualOrderByExecutedAtAsc(
                portfolioId, date.atTime(23, 59, 59),
            )
        ).thenReturn(listOf(trade))

        `when`(positionRepository.findByIdPortfolioIdAndIdDate(portfolioId, date)).thenReturn(
            listOf(
                PositionDailyEntity(
                    id = PositionDailyId(tenantId, portfolioId, assetId, date),
                    quantity = BigDecimal("10"),
                    averageCost = BigDecimal.ZERO,
                    realizedPnl = BigDecimal.ZERO,
                    unrealizedPnl = BigDecimal.ZERO,
                ),
            )
        )

        `when`(useCase.generate(anyArg())).thenReturn(performance() to risk())

        return SnapshotTriggerService(
            tradeRepository,
            performanceRepository,
            useCase,
            cache,
            metrics,
            CurrencyConverter(fxRates),
            positionRepository,
            navCurrencyStore,
        )
    }

    @Test
    fun `환산 전 원통화 평가액이 통화별로 기록된다`() {
        val result = service().trigger(tenantId, portfolioId, date)

        assertNotNull(result)
        @Suppress("UNCHECKED_CAST")
        val captor = ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<List<CurrencyValue>>
        verify(navCurrencyStore).replace(eqArg(portfolioId), eqArg(date), captureArg(captor, emptyList()))

        val values = captor.value
        assertEquals(1, values.size)
        assertEquals("USD", values[0].currency)
        // 10주 × $200 — KRW 280만이 아니라 원통화 2000달러로 남아야 한다
        assertEquals(0, BigDecimal("2000").compareTo(values[0].valueNative))
        assertEquals(0, BigDecimal("1400").compareTo(values[0].fxRate))
    }

    @Test
    fun `통화별 평가액 쓰기가 실패해도 스냅샷은 살아남는다`() {
        // try/catch 를 지우면 이 예외가 trigger() 밖으로 새어나가 테스트가 깨진다
        val svc = service()
        doThrow(RuntimeException("relation nav_currency_daily does not exist"))
            .`when`(navCurrencyStore).replace(anyArg(), anyArg(), anyArg())

        val result = svc.trigger(tenantId, portfolioId, date)

        // 스냅샷은 이미 커밋됐고 호출자에게 그대로 돌아가야 한다
        assertNotNull(result)
        assertEquals(0, BigDecimal("280000").compareTo(result!!.nav))
        // try/catch 뒤에 오는 캐시 갱신도 건너뛰어져선 안 된다
        verify(cache).evict(tenantId, portfolioId, date)
        verify(cache).saveLatest(eqArg(tenantId), eqArg(portfolioId), anyArg())
    }

    // ── Mockito matcher를 Kotlin non-null 파라미터에 넘기기 위한 래퍼 ──
    // 매처 자체는 null을 돌려주는데 Kotlin이 호출부에 null 검사를 끼워 넣어 NPE가 난다.
    // 매처 등록은 부수효과라 반환값은 아무거나 non-null이면 된다 — Mockito는 안 본다.

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyArg(): T = ArgumentMatchers.any<T>() as T

    private fun <T : Any> eqArg(value: T): T {
        ArgumentMatchers.eq(value)
        return value
    }

    private fun <T : Any> captureArg(captor: ArgumentCaptor<T>, dummy: T): T {
        captor.capture()
        return dummy
    }
}
