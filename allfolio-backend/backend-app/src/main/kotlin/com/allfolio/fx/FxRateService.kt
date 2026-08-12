package com.allfolio.fx

import java.math.BigDecimal

/**
 * 환율 서비스 인터페이스
 *
 * KRW 기준 환율 제공.
 * 구현체는 Redis 캐시 기반 (실시간 API 호출 금지).
 */
interface FxRateService {
    /** USDT → KRW 환율 (예: 1350.0). 거래소 시세 — 김치 프리미엄이 섞여 있다. */
    fun getUsdtToKrw(): BigDecimal

    /**
     * 공식 원/미국달러 매매기준율.
     *
     * **default가 곧 현행 동작이다** — 하나은행 고시를 아직 수집하지 않으므로 USDT 환율로 근사한다.
     * AF-99의 `HanaFxRateService`가 들어오면 그것만 이 메서드를 오버라이드하고,
     * 고시 행이 없거나 조회가 실패하면 여기로 떨어진다. 즉 수집을 한 번도 안 돌린 상태에서도
     * 오늘과 똑같이 굴러간다.
     *
     * default를 두는 실무적 이유도 있다. 이 인터페이스를 구현하는 테스트 fake가 여섯이라,
     * default 없이 메서드를 추가하면 그 변경 하나로 컴파일이 무너진다.
     */
    fun getUsdToKrw(): BigDecimal = getUsdtToKrw()

    /** USDT → KRW 환율 설정 (어드민 전용) */
    fun setUsdtToKrw(rate: BigDecimal)

    /** 코인 1개당 KRW 가격 (symbol: BTC | ETH). Redis 미스 시 설정 폴백 (QA P3) */
    fun getCryptoToKrw(symbol: String): BigDecimal

    /** 코인 KRW 가격 설정 (어드민 전용) */
    fun setCryptoToKrw(symbol: String, rate: BigDecimal)
}
