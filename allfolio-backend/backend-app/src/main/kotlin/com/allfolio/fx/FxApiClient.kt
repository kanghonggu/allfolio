package com.allfolio.fx

import java.math.BigDecimal

/**
 * 외부 시세 API 클라이언트 인터페이스
 *
 * 구현체는 @ConditionalOnProperty 로 선택적 활성화.
 * 실패 시 예외를 던지면 스케줄러에서 캐치 → 기존 Redis 값 유지.
 */
interface FxApiClient {
    /**
     * 심볼 → KRW 현재가. 키는 [com.allfolio.fx.exchange.FxSymbols]의 값.
     *
     * **일부만 담겨 있을 수 있다.** 한 심볼을 못 가져왔다고 나머지 갱신을 막지 않는다.
     * 하나도 못 가져오면 예외를 던진다.
     */
    fun fetchKrwRates(): Map<String, BigDecimal>
}
