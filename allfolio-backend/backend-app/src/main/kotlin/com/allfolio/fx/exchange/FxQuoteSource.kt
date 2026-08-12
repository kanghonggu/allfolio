package com.allfolio.fx.exchange

import java.math.BigDecimal

/**
 * 수집 대상 심볼.
 *
 * 한 곳에 모으는 이유는 파서·소스·가드·스케줄러가 같은 목록을 봐야 하기 때문이다.
 * 흩어 두면 심볼을 늘릴 때 한 곳을 빠뜨려도 컴파일이 통과한다.
 */
object FxSymbols {
    const val USDT = "USDT"
    const val BTC = "BTC"
    const val ETH = "ETH"

    /** 수집 순서와 무관한 전체 목록 */
    val ALL = listOf(USDT, BTC, ETH)

    /** 코인만 — USDT는 스테이블코인이라 FxRateService에서 다른 경로를 탄다 */
    val CRYPTO = listOf(BTC, ETH)
}

/**
 * 개별 거래소의 KRW 시세 소스.
 *
 * [com.allfolio.fx.FxApiClient]가 아니라 그 구현체(ExchangeFxApiClient)의 부품이다.
 * FxApiClient를 직접 구현하면 빈이 둘이 되어 FxRateScheduler의 주입이 깨진다.
 *
 * 실패는 예외로 알린다. 0이나 null을 돌려주면 호출자가 "실패"와 "진짜 0원"을
 * 구분할 수 없고, 그 값이 그대로 모든 자산 평가에 흘러든다.
 */
interface FxQuoteSource {
    /** 로그·진단에 쓰는 소스 이름 (예: "UPBIT") */
    val sourceName: String

    /**
     * 심볼 → KRW 현재가. 키는 [FxSymbols]의 값.
     *
     * **일부만 담겨 있어도 된다.** 거래소가 특정 마켓을 안 주는 경우가 있고,
     * 그때 전체를 실패로 만들면 멀쩡한 나머지 심볼까지 낡는다.
     * 호출자가 심볼 단위로 부족한 것만 다음 소스에서 채운다.
     *
     * 호출 자체가 실패하면 [FxQuoteException].
     */
    fun fetchKrwRates(): Map<String, BigDecimal>
}

/** 소스 하나가 실패했다는 신호. 체인이 다음 소스로 넘어가는 근거가 된다. */
class FxQuoteException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
