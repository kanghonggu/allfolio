package com.allfolio.fx

import com.allfolio.unifiedasset.infrastructure.entity.HistoricalFxRateEntity
import com.allfolio.unifiedasset.infrastructure.jpa.HistoricalFxRateJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 과거 크립토 시세가 조회 경로에 실제로 닿는지 본다.
 *
 * 이 연결이 없으면 백필을 아무리 돌려도 `toKrwOn`이 크립토를 현재가로 우회시켜
 * 저장된 `cash_flow.amount_krw`가 계속 틀린다 — 2026-08-01 두 행에서 실제로 일어났고
 * ETH 2.0이 9,000,000원(상수 4,500,000)으로 굳어 있었다. 실제 그날 종가는 2,660,000이다.
 */
class HistoricalCryptoRateTest {

    private val date = LocalDate.of(2026, 8, 1)

    /**
     * `HistoricalFxRateJpaRepository`는 `JpaRepository`를 상속해 메서드가 수십 개다.
     * 이 리포의 관례대로 Mockito 목에 위임하고 필요한 것만 오버라이드한다
     * (`FxRateBackfillServiceTest.FakeRepo`와 같은 방식).
     */
    private class FakeRepo(private val rows: List<HistoricalFxRateEntity>) :
        HistoricalFxRateJpaRepository by mock(HistoricalFxRateJpaRepository::class.java) {

        override fun findTopByCurrencyAndBaseDateLessThanEqualOrderByBaseDateDesc(
            currency: String,
            baseDate: LocalDate,
        ): HistoricalFxRateEntity? =
            rows.filter { it.currency == currency && !it.baseDate.isAfter(baseDate) }
                .maxByOrNull { it.baseDate }
    }

    private fun adapter(rows: List<HistoricalFxRateEntity>) =
        UnifiedAssetFxConverterAdapter(CurrencyConverter(StubFx()), FakeRepo(rows))

    private fun row(currency: String, rate: String) = HistoricalFxRateEntity(
        id = UUID.randomUUID(), baseDate = date, currency = currency,
        rateKrw = BigDecimal(rate), source = "UPBIT", createdAt = LocalDateTime.now(),
    )

    @Test
    fun `ETH는 그날 종가로 환산하고 추정이 아니다`() {
        // 2026-08-01 Upbit 종가 2,660,000. 2.0 ETH = 5,320,000원.
        // 상수 4,500,000으로 굳었던 값은 9,000,000원이었다 — 69% 과대평가.
        val result = adapter(listOf(row("ETH", "2660000"))).toKrwOn(BigDecimal("2.0"), "ETH", date)

        assertThat(result.amountKrw).isEqualByComparingTo("5320000")
        assertThat(result.estimated).isFalse()
        assertThat(result.rateDate).isEqualTo(date)
    }

    @Test
    fun `BTC도 같은 경로를 탄다`() {
        val result = adapter(listOf(row("BTC", "90557000"))).toKrwOn(BigDecimal("0.5"), "BTC", date)

        assertThat(result.amountKrw).isEqualByComparingTo("45278500")
        assertThat(result.estimated).isFalse()
    }

    @Test
    fun `행이 없으면 현재가 폴백이고 추정으로 표시된다`() {
        // 백필을 안 돌린 구간. 동작은 기존과 같고 estimated=true로 드러난다.
        val result = adapter(emptyList()).toKrwOn(BigDecimal("2.0"), "ETH", date)

        assertThat(result.estimated).isTrue()
        assertThat(result.rateDate).isNull()
    }

    @Test
    fun `USD 경로는 그대로다 - 크립토 추가가 기존 통화를 건드리지 않는다`() {
        val result = adapter(listOf(row("USD", "1390"))).toKrwOn(BigDecimal("100"), "USD", date)

        assertThat(result.amountKrw).isEqualByComparingTo("139000")
        assertThat(result.estimated).isFalse()
    }

    private class StubFx : FxRateService {
        override fun getUsdtToKrw(): BigDecimal = BigDecimal("1400")
        override fun setUsdtToKrw(rate: BigDecimal) = Unit
        override fun getCryptoToKrw(symbol: String): BigDecimal = BigDecimal("1")
        override fun setCryptoToKrw(symbol: String, rate: BigDecimal) = Unit
    }
}
