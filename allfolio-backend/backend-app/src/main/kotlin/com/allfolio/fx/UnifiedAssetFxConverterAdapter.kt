package com.allfolio.fx

import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.application.port.KrwConversion
import com.allfolio.unifiedasset.infrastructure.jpa.HistoricalFxRateJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * unified-asset의 [FxConverter] 포트를 backend-app FX 인프라로 연결하는 어댑터.
 *
 * - [toKrw]   현재 환율 (Redis 캐시) — 자산 평가액용
 * - [toKrwOn] 지정일 환율 (fx_rate_daily) — 현금흐름용
 *
 * 폴백 정책을 여기 한 곳에 모아 둔다. 소비 지점이 "과거 없으면 현재로" 규칙을
 * 각자 구현하면 같은 로직이 복제되고 넷째 소비자가 생길 때 또 복제된다.
 */
@Component
class UnifiedAssetFxConverterAdapter(
    private val currencyConverter: CurrencyConverter,
    private val historicalRates: HistoricalFxRateJpaRepository,
) : FxConverter {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 확정된 과거 환율은 변하지 않으므로 무기한 캐싱해도 안전하다.
     * 거래 수백 건짜리 sync에서 날짜별 조회가 반복되는 것을 막는 용도이고,
     * 프로세스 재시작 시 비워져도 무방하다.
     *
     * "결과가 아닌 것"은 절대 기억하지 않는다 — 요청한 날짜의 행을 정확히 찾았을 때만 넣는다.
     * 조회 실패(예외)나 행 없음을 캐시하면 잠깐 끊긴 커넥션 하나가 그 날짜를 프로세스 수명 내내
     * 현재환율 폴백으로 고정시키고, 나중에 백필로 들어온 행도 영영 못 보게 된다.
     * 직전 영업일로 해소된 결과도 넣지 않는다 — 요청 날짜 키에 옛 base_date 값이 박히면
     * 그 날짜의 행이 나중에 들어와도 계속 옛 값을 확정치인 양 내놓는다.
     */
    private val cache = ConcurrentHashMap<String, ResolvedRate>()

    private data class ResolvedRate(val rateKrw: BigDecimal, val rateDate: LocalDate)

    companion object {
        private val KST = ZoneId.of("Asia/Seoul")

        /** 과거 시계열을 가진 통화. ECOS로 채울 수 있는 것만 여기 들어간다. */
        private val HISTORICAL = setOf("USD")

        /** 과거 시세 소스가 없어 현재가로만 환산되는 통화 */
        private val CRYPTO = setOf("BTC", "ETH")
    }

    // Account.reconstruct는 DB 값을 재정규화 없이 되살리므로 Currencies.normalize를 우회한 코드가
    // 그대로 도달한다. 정규화 없이 넘기면 " usdt "가 1:1로 떨어져 100 USDT가 100원이 된다
    override fun toKrw(amount: BigDecimal, currency: String): BigDecimal =
        currencyConverter.toKrw(amount, canonical(currency))

    override fun toKrwOn(amount: BigDecimal, currency: String, date: LocalDate): KrwConversion {
        val code = canonical(currency)

        if (code == "KRW") return KrwConversion(amount, rateDate = null, estimated = false)

        // BTC/ETH는 과거 시세를 가진 소스가 없다 — 현행 현재가 환산을 유지한다
        if (code in CRYPTO) return estimatedNow(amount, code)

        if (code !in HISTORICAL) {
            log.error("[Fx] 지원하지 않는 통화 — 환산 없이 그대로 둔다 currency={} date={}", currency, date)
            return estimatedNow(amount, code)
        }

        val resolved = lookup(code, date)
            ?: return estimatedNow(amount, code).also {
                log.warn("[Fx] 과거 환율 없음 — 현재 환율로 환산 currency={} date={}", code, date)
            }

        return KrwConversion(
            amountKrw = (amount * resolved.rateKrw).setScale(0, RoundingMode.HALF_UP),
            rateDate = resolved.rateDate,
            estimated = false,
        )
    }

    /**
     * 캐시를 통째로 비운다. 백필이 **같은 날짜의 값을 정정**했을 때 [FxRateBackfillService]가 호출한다 —
     * 확정치라 무기한 캐싱하지만 "확정"은 ECOS가 준 값 기준이고, 그 값이 바뀌면 캐시는 낡은 값이 된다.
     *
     * 통화별로 가려 비우지 않는 이유: 캐시는 정확히 맞은 날짜만 담고(직전 영업일 해소분·미스는 안 담는다)
     * 백필은 어드민이 수동으로 한 번씩 돌리는 경로라, 다 비우고 다시 채우는 비용이 선별 로직보다 싸다.
     */
    fun invalidate() {
        val size = cache.size
        cache.clear()
        log.info("[Fx] 과거 환율 캐시 무효화 entries={}", size)
    }

    /**
     * 반드시 [canonical]을 거친 코드를 넘긴다. [CurrencyConverter]는 uppercase만 하고 trim을 안 해서
     * " btc " 같은 원본 값을 넘기면 어느 갈래에도 안 맞고 1:1로 떨어진다 — 0.5 BTC가 0.5원이 된다.
     */
    private fun estimatedNow(amount: BigDecimal, code: String) =
        KrwConversion(currencyConverter.toKrw(amount, code), rateDate = null, estimated = true)

    /**
     * 별칭을 정리한 통화 코드. USDT는 USD 시계열로 근사한다 —
     * 현재 환율 경로(CurrencyConverter)와 같은 취급이다.
     *
     * 화이트리스트 검증을 겸하는 `Currencies.normalize`와 달리 여기서는 별칭 치환만 한다.
     */
    private fun canonical(currency: String): String =
        when (val code = currency.trim().uppercase()) {
            "USDT" -> "USD"
            else -> code
        }

    private fun lookup(code: String, date: LocalDate): ResolvedRate? {
        // 오늘 이후는 아직 확정 전이라 캐시에 넣지 않는다
        if (!date.isBefore(LocalDate.now(KST))) return query(code, date)

        val key = "$code@$date"
        cache[key]?.let { return it }

        // computeIfAbsent 밖에서 조회한다 — 맵 락을 쥔 채로 DB I/O를 하지 않는다
        val resolved = query(code, date) ?: return null
        if (resolved.rateDate == date) cache[key] = resolved
        return resolved
    }

    private fun query(code: String, date: LocalDate): ResolvedRate? =
        runCatching {
            historicalRates
                .findTopByCurrencyAndBaseDateLessThanEqualOrderByBaseDateDesc(code, date)
                ?.let { ResolvedRate(it.rateKrw, it.baseDate) }
        }.getOrElse { e ->
            log.error("[Fx] 과거 환율 조회 실패 currency={} date={}: {}", code, date, e.message)
            null
        }
}
