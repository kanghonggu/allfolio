package com.allfolio.unifiedasset.application.port

import java.math.BigDecimal

/**
 * 통화 → KRW 환산 포트.
 *
 * unified-asset는 여러 증권사/거래소 자산을 통합하므로 통화가 섞인다
 * (예: KIS 국내주식=KRW, Binance 보유=USD). NAV·총자산을 합산하기 전에
 * 반드시 이 포트로 단일 기준통화(KRW)로 환산해야 한다.
 *
 * 구현 어댑터는 backend-app FX 인프라(Redis 캐시 환율)를 래핑한다.
 */
interface FxConverter {
    /**
     * @param amount   환산 전 금액
     * @param currency ISO 통화 코드("KRW", "USD", "USDT" 등, 대소문자 무관)
     * @return KRW 환산 금액
     */
    fun toKrw(amount: BigDecimal, currency: String): BigDecimal
}
