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

    /**
     * 공식 원/미국달러 매매기준율 (AF-99).
     *
     * 자산 평가의 USD 환산이 쓴다. USDT는 이걸 쓰지 않는다 —
     * Binance USDT/KRW에는 김치 프리미엄이 섞여 있고, 그건 부정확이 아니라
     * 거래소에 실제 USDT를 들고 있는 사용자에게 실현 가능한 값이다.
     *
     * default는 하나은행 고시를 모르는 구현체를 위한 것으로, 현행 동작(USDT 환율로 근사)이다.
     */
    fun getUsdToKrw(): BigDecimal = getUsdtToKrw()
}
