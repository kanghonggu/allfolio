package com.allfolio.fx

import java.math.BigDecimal
import java.time.LocalDate

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

    /**
     * 공식 고시의 출처 메타 (AF-105). 고시 기반 구현이 아니면 null.
     *
     * 화면이 "무슨 환율로 계산했나"에 답하려면 값만으론 부족하다 — 기준일과 회차가 있어야
     * 사용자가 하나은행 화면과 직접 대조할 수 있고, 그 대조 가능성이 이 기능의 전부다.
     *
     * **기본 구현을 두는 이유**: 이 인터페이스는 테스트에서 열 곳 넘게 익명 객체로 구현돼 있다.
     * 추상 메서드로 추가하면 그 전부가 컴파일되지 않는다.
     * (AF-100에서 `FxConverter.toKrwOn`에 쓴 것과 같은 방식.)
     */
    fun usdQuoteRef(): UsdQuoteRef? = null
}

/**
 * 하나은행 고시 한 건의 식별 정보 (AF-105).
 *
 * `roundNo`가 핵심이다. 기준일만으로는 하루에 수십 번 바뀌는 고시 중 어느 것인지 특정할 수 없어
 * 대조가 불가능하다.
 */
data class UsdQuoteRef(
    val rate: BigDecimal,
    val baseDate: LocalDate,
    val roundNo: Int,
)
