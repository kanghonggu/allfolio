package com.allfolio.fx

import java.math.BigDecimal

/**
 * 환율 서비스 인터페이스
 *
 * KRW 기준 환율 제공.
 * 구현체는 Redis 캐시 기반 (실시간 API 호출 금지).
 */
interface FxRateService {
    /** USDT → KRW 환율 (예: 1350.0) */
    fun getUsdtToKrw(): BigDecimal

    /** USDT → KRW 환율 설정 (어드민 전용) */
    fun setUsdtToKrw(rate: BigDecimal)

    /** 코인 1개당 KRW 가격 (symbol: BTC | ETH). Redis 미스 시 설정 폴백 (QA P3) */
    fun getCryptoToKrw(symbol: String): BigDecimal

    /** 코인 KRW 가격 설정 (어드민 전용) */
    fun setCryptoToKrw(symbol: String, rate: BigDecimal)
}
