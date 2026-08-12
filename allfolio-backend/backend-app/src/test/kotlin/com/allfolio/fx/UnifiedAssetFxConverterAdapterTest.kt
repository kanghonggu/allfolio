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

        // 현재 환율 경로로 넘어간다 — StubFxRateService가 USD 1350을 준다
        assertThat(result.amountKrw).isEqualByComparingTo("135000")
        assertThat(result.estimated).isTrue()
        assertThat(result.rateDate).isNull()
    }

    @Test
    fun `BTC는 과거 시세가 없으므로 현재가로 환산하고 추정치로 표시한다`() {
        val result = adapter(FakeRepo()).toKrwOn(BigDecimal("0.5"), "BTC", date)

        assertThat(result.amountKrw).isEqualByComparingTo("45000000")
        assertThat(result.estimated).isTrue()
        assertThat(result.rateDate).isNull()
    }

    @Test
    fun `통화 코드에 공백이 섞여도 크립토 현재가로 환산한다`() {
        // 계좌 통화는 엔티티 값이 그대로 넘어오므로 어댑터가 유일한 방어선이다.
        // 폴백에 정규화 전 코드를 넘기면 CurrencyConverter가 " BTC "를 못 알아보고
        // 0.5 BTC를 0.5원으로 돌려준다
        val result = adapter(FakeRepo()).toKrwOn(BigDecimal("0.5"), " btc ", date)

        assertThat(result.amountKrw).isEqualByComparingTo("45000000")
        assertThat(result.estimated).isTrue()
    }

    @Test
    fun `현재 환율 경로도 공백이 섞인 통화 코드를 환산한다`() {
        // Account.reconstruct는 DB 값을 재정규화 없이 되살리므로, 정규화 없이 저장된
        // 과거 행은 Currencies.normalize 방어를 우회한다. 자산 평가 경로가 이 메서드를 쓴다
        val result = adapter(FakeRepo()).toKrw(BigDecimal("100"), " usdt ")

        assertThat(result).isEqualByComparingTo("135000")
    }

    @Test
    fun `화이트리스트 밖 통화는 환산하지 못하고 추정치로 표시한다`() {
        val result = adapter(FakeRepo()).toKrwOn(BigDecimal("100"), "EUR", date)

        // CurrencyConverter가 모르는 통화라 그대로 돌려준다 — 환산이 안 된 값이다
        assertThat(result.amountKrw).isEqualByComparingTo("100")
        assertThat(result.estimated).isTrue()
        assertThat(result.rateDate).isNull()
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

    @Test
    fun `일시적 조회 실패는 기억하지 않는다`() {
        // Neon autosuspend로 커넥션이 한 번 끊긴 날짜가 프로세스 수명 내내
        // 현재환율 폴백으로 고정되면, 그 값이 cash_flow에 그대로 저장된다
        val repo = FlakyRepo(row(date, "1390.200000"))
        val adapter = adapter(repo)

        val duringOutage = adapter.toKrwOn(BigDecimal("100"), "USD", date)
        val afterRecovery = adapter.toKrwOn(BigDecimal("100"), "USD", date)

        assertThat(duringOutage.estimated).isTrue()
        assertThat(afterRecovery.amountKrw).isEqualByComparingTo("139020")
        assertThat(afterRecovery.estimated).isFalse()
        assertThat(afterRecovery.rateDate).isEqualTo(date)
        assertThat(repo.callCount).isEqualTo(2)
    }

    @Test
    fun `백필로 나중에 들어온 행을 다음 조회부터 반영한다`() {
        // 빈 테이블로 먼저 배포하고 살아있는 프로세스에 백필을 때리는 계획이라,
        // 백필 전에 조회된 날짜가 empty로 굳으면 백필이 무의미해진다
        val repo = FakeRepo()
        val adapter = adapter(repo)

        val beforeBackfill = adapter.toKrwOn(BigDecimal("100"), "USD", date)
        repo.stored = row(date, "1390.200000")
        val afterBackfill = adapter.toKrwOn(BigDecimal("100"), "USD", date)

        assertThat(beforeBackfill.amountKrw).isEqualByComparingTo("135000")
        assertThat(beforeBackfill.estimated).isTrue()
        assertThat(afterBackfill.amountKrw).isEqualByComparingTo("139020")
        assertThat(afterBackfill.estimated).isFalse()
        assertThat(afterBackfill.rateDate).isEqualTo(date)
    }

    @Test
    fun `직전 영업일로 해소된 조회는 캐시하지 않는다`() {
        // 주말·공휴일은 직전 영업일 행으로 이어지는데, 그 결과를 요청 날짜 키에 박아두면
        // 나중에 그 날짜의 행이 들어와도 계속 옛 값을 확정치인 양 내놓는다
        val repo = FakeRepo(row(date.minusDays(3), "1390.200000"))
        val adapter = adapter(repo)

        val first = adapter.toKrwOn(BigDecimal("100"), "USD", date)
        val second = adapter.toKrwOn(BigDecimal("100"), "USD", date)

        assertThat(first.rateDate).isEqualTo(date.minusDays(3))
        assertThat(first.estimated).isFalse()
        assertThat(second.amountKrw).isEqualByComparingTo("139020")
        assertThat(repo.callCount).isEqualTo(2)
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

    /** 조회 두 메서드만 쓰므로 나머지는 위임하지 않는다. stored가 var인 이유는 백필 시나리오 */
    private open class FakeRepo(
        var stored: HistoricalFxRateEntity? = null,
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

    /** 첫 조회만 끊기고 이후엔 정상 — 커넥션이 잠깐 끊기는 실제 양상 */
    private class FlakyRepo(row: HistoricalFxRateEntity) : FakeRepo(row) {
        private var brokenOnce = false

        override fun findTopByCurrencyAndBaseDateLessThanEqualOrderByBaseDateDesc(
            currency: String,
            baseDate: LocalDate,
        ): HistoricalFxRateEntity? {
            // 실패 경로는 super를 타지 않으므로 여기서 직접 센다 — 호출 수와 callCount를 1:1로 유지
            if (!brokenOnce) {
                brokenOnce = true
                callCount++
                throw RuntimeException("connection reset by peer")
            }
            return super.findTopByCurrencyAndBaseDateLessThanEqualOrderByBaseDateDesc(currency, baseDate)
        }
    }
}
