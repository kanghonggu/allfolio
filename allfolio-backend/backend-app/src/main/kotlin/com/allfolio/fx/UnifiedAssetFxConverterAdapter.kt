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
import java.util.Optional
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
     * 프로세스 재시작 시 비워져도 무방하다. 오늘 이후는 캐싱하지 않는다 — 아직 확정 전이다.
     */
    private val cache = ConcurrentHashMap<String, Optional<ResolvedRate>>()

    private data class ResolvedRate(val rateKrw: BigDecimal, val rateDate: LocalDate)

    companion object {
        private val KST = ZoneId.of("Asia/Seoul")

        /** 과거 시계열을 가진 통화. ECOS로 채울 수 있는 것만 여기 들어간다. */
        private val HISTORICAL = setOf("USD")
    }

    override fun toKrw(amount: BigDecimal, currency: String): BigDecimal =
        currencyConverter.toKrw(amount, currency)

    override fun toKrwOn(amount: BigDecimal, currency: String, date: LocalDate): KrwConversion {
        val code = normalize(currency)

        if (code == "KRW") return KrwConversion(amount, rateDate = null, estimated = false)

        // BTC/ETH는 과거 시세를 가진 소스가 없다 — 현행 현재가 환산을 유지한다
        if (code !in HISTORICAL) return estimatedNow(amount, currency)

        val resolved = lookup(code, date)
            ?: return estimatedNow(amount, currency).also {
                log.warn("[Fx] 과거 환율 없음 — 현재 환율로 환산 currency={} date={}", code, date)
            }

        return KrwConversion(
            amountKrw = (amount * resolved.rateKrw).setScale(0, RoundingMode.HALF_UP),
            rateDate = resolved.rateDate,
            estimated = false,
        )
    }

    private fun estimatedNow(amount: BigDecimal, currency: String) =
        KrwConversion(currencyConverter.toKrw(amount, currency), rateDate = null, estimated = true)

    /** USDT는 USD 시계열로 근사한다 — 현재 환율 경로(CurrencyConverter)와 같은 취급이다. */
    private fun normalize(currency: String): String =
        when (val code = currency.trim().uppercase()) {
            "USDT" -> "USD"
            else -> code
        }

    private fun lookup(code: String, date: LocalDate): ResolvedRate? {
        if (!date.isBefore(LocalDate.now(KST))) return query(code, date)
        return cache.computeIfAbsent("$code@$date") { Optional.ofNullable(query(code, date)) }.orElse(null)
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
