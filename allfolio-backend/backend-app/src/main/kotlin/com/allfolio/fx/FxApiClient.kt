package com.allfolio.fx

import java.math.BigDecimal

/**
 * 외부 환율 API 클라이언트 인터페이스
 *
 * 구현체는 @ConditionalOnProperty 로 선택적 활성화.
 * 실패 시 예외를 던지면 스케줄러에서 캐치 → fallback rate 유지.
 */
interface FxApiClient {
    /** USDT → KRW 현재 환율 조회 */
    fun getUsdtKrw(): BigDecimal
}
