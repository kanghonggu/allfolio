package com.allfolio.snapshot

import com.allfolio.unifiedasset.application.port.CurrencyValue
import com.allfolio.unifiedasset.application.port.NavCurrencyStore
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** 자산 하나의 원통화 시세 — 환산 전 */
data class NativePrice(val price: BigDecimal, val currency: String)

/**
 * 통화별 일간 평가액 저장 (AF-106).
 *
 * `performance_daily` 옆에 세운다 — 스냅샷 모듈(ABOR 이식분)은 건드리지 않는다.
 */
@Component
class NavCurrencyDailyStore(private val jdbc: JdbcTemplate) : NavCurrencyStore {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /**
         * 자산별 수량 × 원통화 시세를 통화로 묶는다.
         *
         * **미지원 통화는 rate=1이다.** CurrencyConverter가 실제로 환산하는 통화는
         * KRW·USD·USDT·BTC·ETH 다섯뿐이고, 나머지는 경고만 남기고 원금을 그대로 돌려준다.
         * 예외를 던지면 스냅샷이 깨진다. 그 동작을 그대로 기록해야 합계 불변식이 성립하고,
         * `currency='JPY'`인데 `fx_rate=1`인 행이 미환산 자산의 진단 지표가 된다.
         *
         * @param quantities 자산별 보유 수량 (position_daily)
         * @param prices     자산별 원통화 시세. 없는 자산은 건너뛴다
         * @param rateOf     통화 → 1단위당 KRW. `sourceOf(c)?.rate ?: ONE`을 넘긴다
         */
        fun aggregate(
            quantities: Map<UUID, BigDecimal>,
            prices: Map<UUID, NativePrice>,
            rateOf: (String) -> BigDecimal,
        ): List<CurrencyValue> {
            val byCurrency = LinkedHashMap<String, BigDecimal>()
            for ((assetId, qty) in quantities) {
                val p = prices[assetId] ?: continue
                val code = p.currency.uppercase()
                byCurrency[code] = (byCurrency[code] ?: BigDecimal.ZERO) + (qty * p.price)
            }
            return byCurrency.map { (code, value) -> CurrencyValue(code, value, rateOf(code)) }
        }
    }

    /**
     * DELETE 후 INSERT — 스냅샷 모듈의 재계산 멱등성 패턴과 같은 모양.
     *
     * [NavCurrencyStore]의 구현이다 — unified-asset의 스냅샷 경로도 이 메서드로 들어온다.
     *
     * **호출자가 예외를 삼킨다.** 여기서 던지는 건 정상이다 — 스냅샷은 이미 커밋됐고,
     * 통화 분해가 없어도 NAV는 정확하다.
     */
    override fun replace(portfolioId: UUID, date: LocalDate, values: List<CurrencyValue>) {
        jdbc.update("DELETE FROM nav_currency_daily WHERE portfolio_id = ? AND date = ?", portfolioId, date)
        if (values.isEmpty()) return
        jdbc.batchUpdate(
            """INSERT INTO nav_currency_daily (portfolio_id, date, currency, value_native, fx_rate)
               VALUES (?, ?, ?, ?, ?)""",
            values.map { arrayOf<Any>(portfolioId, date, it.currency, it.valueNative, it.fxRate) },
        )
        log.debug("[NavCurrency] wrote {} rows portfolio={} date={}", values.size, portfolioId, date)
    }
}
