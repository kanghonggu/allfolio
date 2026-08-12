package com.allfolio.fx

import com.allfolio.unifiedasset.infrastructure.entity.HanaFxQuoteEntity
import com.allfolio.unifiedasset.infrastructure.jpa.HanaFxQuoteJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * 하나은행 고시가 있으면 그것을, 없거나 조회가 실패하면 기존 구현에 위임한다.
 * 수집을 한 번도 안 돌린 상태에서도 오늘과 똑같이 굴러가야 한다.
 */
class HanaFxRateServiceTest {

    @Test
    fun `하나은행 고시가 있으면 그 매매기준율을 준다`() {
        val service = service(FakeRepo(quote("1390.5000")))

        assertThat(service.getUsdToKrw()).isEqualByComparingTo("1390.5")
    }

    @Test
    fun `고시가 없으면 기존 구현에 위임한다`() {
        val service = service(FakeRepo())

        assertThat(service.getUsdToKrw()).isEqualByComparingTo("1350")
    }

    @Test
    fun `조회가 실패해도 예외를 던지지 않고 위임한다`() {
        val service = service(ExplodingRepo())

        assertThat(service.getUsdToKrw()).isEqualByComparingTo("1350")
    }

    @Test
    fun `USDT와 코인은 기존 구현에 그대로 위임한다`() {
        val service = service(FakeRepo(quote("1390.5000")))

        assertThat(service.getUsdtToKrw()).isEqualByComparingTo("1350")
        assertThat(service.getCryptoToKrw("BTC")).isEqualByComparingTo("90000000")
    }

    @Test
    fun `반복 조회해도 DB는 한 번만 친다`() {
        val repo = FakeRepo(quote("1390.5000"))
        val service = service(repo)

        repeat(5) { service.getUsdToKrw() }

        assertThat(repo.callCount).isEqualTo(1)
    }

    @Test
    fun `캐시가 TTL을 넘기면 다시 조회해 새 고시를 반영한다`() {
        val repo = FakeRepo(quote("1390.5000"))
        val service = service(repo)
        assertThat(service.getUsdToKrw()).isEqualByComparingTo("1390.5")

        repo.stored = quote("1400.0000")
        ageCacheBeyondTtl(service)

        assertThat(service.getUsdToKrw()).isEqualByComparingTo("1400.0")
        assertThat(repo.callCount).isEqualTo(2)
    }

    @Test
    fun `조회 실패는 캐시에 남지 않아 DB가 돌아오면 곧바로 고시를 다시 읽는다`() {
        val repo = FlakyRepo()
        val service = service(repo)
        assertThat(service.getUsdToKrw()).isEqualByComparingTo("1350")

        repo.stored = quote("1390.5000")
        repo.explode = false

        // TTL을 기다리지 않는다 — 실패를 기억했다면 여기서 1350이 그대로 나온다.
        // AF-100에서 어댑터가 "결과가 아닌 것"을 캐시해 커넥션 순단 하나가
        // 프로세스 수명 내내 폴백을 고정시켰던 함정을 막는다.
        assertThat(service.getUsdToKrw()).isEqualByComparingTo("1390.5")
    }

    @Test
    fun `고시가 있으면 usdQuoteRef가 기준일과 회차까지 돌려준다`() {
        val service = service(FakeRepo(quote("1390.5000", LocalDate.of(2026, 8, 11), 32)))

        val ref = service.usdQuoteRef()

        assertThat(ref).isNotNull
        assertThat(ref!!.rate).isEqualByComparingTo("1390.5")
        assertThat(ref.baseDate).isEqualTo(LocalDate.of(2026, 8, 11))
        assertThat(ref.roundNo).isEqualTo(32)
    }

    // 둘이 다른 값을 말하면 화면이 밝히는 환율과 환산에 쓰인 환율이 갈라진다.
    // AF-105 전체가 이 둘이 같다는 전제 위에 서 있다.
    @Test
    fun `usdQuoteRef와 getUsdToKrw는 같은 환율을 말한다`() {
        val service = service(FakeRepo(quote("1390.5000")))

        assertThat(service.usdQuoteRef()!!.rate).isEqualByComparingTo(service.getUsdToKrw())
    }

    @Test
    fun `고시가 없으면 usdQuoteRef는 null이다`() {
        assertThat(service(FakeRepo()).usdQuoteRef()).isNull()
    }

    @Test
    fun `조회가 실패해도 usdQuoteRef는 예외 대신 null이다`() {
        assertThat(service(ExplodingRepo()).usdQuoteRef()).isNull()
    }

    // ── helpers ──────────────────────────────────────────────────

    /**
     * 캐시 나이만 TTL 밖으로 밀어 만료를 시뮬레이션한다. 60초를 실제로 기다릴 수 없고,
     * TTL을 생성자로 빼면 Spring이 Long 빈을 찾다 죽어서 리플렉션을 쓴다.
     * [HanaFxRateService]의 `cached` 필드 이름·타입에 묶여 있다 — 바뀌면 여기도 고칠 것.
     */
    private fun ageCacheBeyondTtl(service: HanaFxRateService) {
        val field = HanaFxRateService::class.java.getDeclaredField("cached").apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val ref = field.get(service) as AtomicReference<Pair<Long, UsdQuoteRef>?>
        val (at, quoteRef) = requireNotNull(ref.get()) { "캐시가 비어 있다 — 테스트 전제가 깨졌다" }
        ref.set((at - 60_001L) to quoteRef)
    }

    private fun service(repo: HanaFxQuoteJpaRepository) = HanaFxRateService(StubDelegate(), repo)

    private fun quote(
        rate: String,
        baseDate: LocalDate = LocalDate.of(2026, 8, 7),
        roundNo: Int = 32,
    ) = HanaFxQuoteEntity(
        id = UUID.randomUUID(), baseDate = baseDate, roundNo = roundNo,
        currency = "USD", baseRate = BigDecimal(rate), cashBuy = null, cashSell = null,
        remitSend = null, remitReceive = null, collectedAt = LocalDateTime.now(),
    )

    private class StubDelegate : FxRateService {
        override fun getUsdtToKrw(): BigDecimal = BigDecimal("1350")
        override fun setUsdtToKrw(rate: BigDecimal) = Unit
        override fun getCryptoToKrw(symbol: String): BigDecimal = BigDecimal("90000000")
        override fun setCryptoToKrw(symbol: String, rate: BigDecimal) = Unit
    }

    private open class FakeRepo(
        var stored: HanaFxQuoteEntity? = null,
    ) : HanaFxQuoteJpaRepository by mock(HanaFxQuoteJpaRepository::class.java) {
        var callCount = 0

        override fun findTopByCurrencyOrderByBaseDateDescRoundNoDesc(currency: String): HanaFxQuoteEntity? {
            callCount++
            return stored?.takeIf { it.currency == currency }
        }
    }

    private class ExplodingRepo : FakeRepo() {
        override fun findTopByCurrencyOrderByBaseDateDescRoundNoDesc(currency: String): HanaFxQuoteEntity? =
            throw RuntimeException("DB down")
    }

    /** 처음엔 죽고, [explode]를 내리면 살아나는 DB */
    private class FlakyRepo(var explode: Boolean = true) : FakeRepo() {
        override fun findTopByCurrencyOrderByBaseDateDescRoundNoDesc(currency: String): HanaFxQuoteEntity? {
            if (explode) throw RuntimeException("DB down")
            return super.findTopByCurrencyOrderByBaseDateDescRoundNoDesc(currency)
        }
    }
}
