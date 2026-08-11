package com.allfolio.fx

import com.allfolio.unifiedasset.infrastructure.entity.HistoricalFxRateEntity
import com.allfolio.unifiedasset.infrastructure.jpa.HistoricalFxRateJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/**
 * 폴백 정책이 어댑터 한 곳에 모여 있어야 소비 지점 3곳이 규칙을 몰라도 맞는 값을 받는다.
 */
class UnifiedAssetFxConverterAdapterTest {

    private val date = LocalDate.of(2025, 8, 11)

    @Test
    fun `KRW는 환산 없이 그대로 두고 추정치가 아니다`() {
        val result = adapter(FakeRepo()).toKrwOn(BigDecimal("5000"), "KRW", date)

        assertThat(result.amountKrw).isEqualByComparingTo("5000")
        assertThat(result.estimated).isFalse()
        assertThat(result.rateDate).isNull()
    }

    @Test
    fun `USD는 저장된 그날 환율로 환산한다`() {
        val repo = FakeRepo(row(date, "1390.200000"))

        val result = adapter(repo).toKrwOn(BigDecimal("100"), "USD", date)

        assertThat(result.amountKrw).isEqualByComparingTo("139020")
        assertThat(result.estimated).isFalse()
        assertThat(result.rateDate).isEqualTo(date)
    }

    @Test
    fun `USDT는 USD 시계열로 환산한다`() {
        val repo = FakeRepo(row(date, "1390.200000"))

        val result = adapter(repo).toKrwOn(BigDecimal("100"), "usdt", date)

        assertThat(result.amountKrw).isEqualByComparingTo("139020")
        assertThat(result.estimated).isFalse()
        assertThat(repo.lastCurrency).isEqualTo("USD")
    }

    @Test
    fun `과거 환율이 없으면 현재 환율로 폴백하고 추정치로 표시한다`() {
        val result = adapter(FakeRepo()).toKrwOn(BigDecimal("100"), "USD", date)

        // CurrencyConverter가 fallback 1350을 쓴다
        assertThat(result.amountKrw).isEqualByComparingTo("135000")
        assertThat(result.estimated).isTrue()
        assertThat(result.rateDate).isNull()
    }

    @Test
    fun `BTC는 과거 시세가 없으므로 현재가로 환산하고 추정치로 표시한다`() {
        val result = adapter(FakeRepo()).toKrwOn(BigDecimal("0.5"), "BTC", date)

        assertThat(result.amountKrw).isEqualByComparingTo("45000000")
        assertThat(result.estimated).isTrue()
    }

    @Test
    fun `조회가 실패해도 예외를 던지지 않고 현재 환율로 폴백한다`() {
        val result = adapter(ExplodingRepo()).toKrwOn(BigDecimal("100"), "USD", date)

        assertThat(result.amountKrw).isEqualByComparingTo("135000")
        assertThat(result.estimated).isTrue()
    }

    @Test
    fun `같은 과거 날짜를 반복 조회해도 DB는 한 번만 친다`() {
        val repo = FakeRepo(row(date, "1390.200000"))
        val adapter = adapter(repo)

        repeat(5) { adapter.toKrwOn(BigDecimal("100"), "USD", date) }

        assertThat(repo.callCount).isEqualTo(1)
    }

    @Test
    fun `오늘 환율은 아직 확정 전이므로 캐싱하지 않는다`() {
        val today = LocalDate.now(KST)
        // 오늘 행이 있어 조회가 hit하는 상황에서도 캐시에 박히면 안 된다 —
        // 장중 값은 확정 전이라, 한 번 캐시되면 하루 종일 그 값이 나온다
        val repo = FakeRepo(row(today, "1390.200000"))
        val adapter = adapter(repo)

        repeat(5) { adapter.toKrwOn(BigDecimal("100"), "USD", today) }

        assertThat(repo.callCount).isEqualTo(5)
    }

    // ── helpers ──────────────────────────────────────────────────

    private val KST = ZoneId.of("Asia/Seoul")

    private fun adapter(repo: HistoricalFxRateJpaRepository) =
        UnifiedAssetFxConverterAdapter(CurrencyConverter(StubFxRateService()), repo)

    private fun row(date: LocalDate, rate: String) = HistoricalFxRateEntity(
        id = UUID.randomUUID(), baseDate = date, currency = "USD",
        rateKrw = BigDecimal(rate), source = "ECOS", createdAt = LocalDateTime.now(),
    )

    private class StubFxRateService : FxRateService {
        override fun getUsdtToKrw(): BigDecimal = BigDecimal("1350")
        override fun setUsdtToKrw(rate: BigDecimal) = Unit
        override fun getCryptoToKrw(symbol: String): BigDecimal = BigDecimal("90000000")
        override fun setCryptoToKrw(symbol: String, rate: BigDecimal) = Unit
    }

    /** 조회 두 메서드만 쓰므로 나머지는 위임하지 않는다 */
    private open class FakeRepo(
        private val stored: HistoricalFxRateEntity? = null,
    ) : HistoricalFxRateJpaRepository by mock(HistoricalFxRateJpaRepository::class.java) {
        var callCount = 0
        var lastCurrency: String? = null

        override fun findTopByCurrencyAndBaseDateLessThanEqualOrderByBaseDateDesc(
            currency: String,
            baseDate: LocalDate,
        ): HistoricalFxRateEntity? {
            callCount++
            lastCurrency = currency
            return stored?.takeIf { it.currency == currency && !it.baseDate.isAfter(baseDate) }
        }
    }

    private class ExplodingRepo : FakeRepo() {
        override fun findTopByCurrencyAndBaseDateLessThanEqualOrderByBaseDateDesc(
            currency: String,
            baseDate: LocalDate,
        ): HistoricalFxRateEntity? = throw RuntimeException("DB down")
    }
}
