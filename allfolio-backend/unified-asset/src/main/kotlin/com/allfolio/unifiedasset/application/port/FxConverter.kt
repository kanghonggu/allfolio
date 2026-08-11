package com.allfolio.unifiedasset.application.port

import java.math.BigDecimal
import java.time.LocalDate

/**
 * 환산 결과.
 *
 * @param amountKrw KRW 환산 금액
 * @param rateDate  적용된 환율 고시일. 과거 환율표에서 찾았을 때만 채워진다.
 *                  KRW(환산 없음)와 현재환율 폴백은 null.
 * @param estimated 요청한 날짜의 환율이 아니라 현재 환율로 근사했으면 true
 */
data class KrwConversion(
    val amountKrw: BigDecimal,
    val rateDate: LocalDate?,
    val estimated: Boolean,
)

/**
 * 통화 → KRW 환산 포트.
 *
 * unified-asset는 여러 증권사/거래소 자산을 통합하므로 통화가 섞인다
 * (예: KIS 국내주식=KRW, Binance 보유=USD). NAV·총자산을 합산하기 전에
 * 반드시 이 포트로 단일 기준통화(KRW)로 환산해야 한다.
 *
 * 구현 어댑터는 backend-app FX 인프라(Redis 캐시 환율 + fx_rate_daily)를 래핑한다.
 */
interface FxConverter {
    /**
     * 현재 환율로 환산한다. 자산 평가액(NAV·보유·리포트)에 쓴다.
     *
     * @param amount   환산 전 금액
     * @param currency ISO 통화 코드("KRW", "USD", "USDT" 등, 대소문자 무관)
     * @return KRW 환산 금액
     */
    fun toKrw(amount: BigDecimal, currency: String): BigDecimal

    /**
     * 지정한 날짜의 환율로 환산한다. 현금흐름(cash_flow.amount_krw)에 쓴다.
     *
     * 자산 평가는 오늘 환율, 현금흐름은 발생일 환율 — 이 경계를 지켜야
     * netFlow가 맞고 TWR/MWR이 왜곡되지 않는다.
     *
     * default 구현은 과거 환율을 모르는 구현체를 위한 것으로, 현재 환율로 근사하고
     * estimated=true를 반환한다 (단, KRW는 환산이 없으므로 estimated=false).
     */
    fun toKrwOn(amount: BigDecimal, currency: String, date: LocalDate): KrwConversion =
        KrwConversion(
            amountKrw = toKrw(amount, currency),
            rateDate = null,
            estimated = !currency.trim().equals("KRW", ignoreCase = true),
        )
}
