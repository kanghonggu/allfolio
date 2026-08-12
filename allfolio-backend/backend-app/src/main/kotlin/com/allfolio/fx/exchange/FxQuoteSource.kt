package com.allfolio.fx.exchange

import java.math.BigDecimal

/**
 * 개별 거래소의 USDT/KRW 시세 소스.
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

    /** USDT → KRW 현재가. 실패 시 [FxQuoteException]. */
    fun fetchUsdtKrw(): BigDecimal
}

/** 소스 하나가 실패했다는 신호. 체인이 다음 소스로 넘어가는 근거가 된다. */
class FxQuoteException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
