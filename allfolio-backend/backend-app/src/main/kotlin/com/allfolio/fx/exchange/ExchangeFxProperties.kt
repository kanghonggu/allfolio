package com.allfolio.fx.exchange

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 거래소 시세 소스 접속 설정.
 *
 * base-url을 환경변수로 뺀 이유는 BinanceFxApiClient가 testnet 기본값에 묶여 있던 문제를
 * 되풀이하지 않기 위해서다. 기본값은 운영 주소이고, 테스트에서만 로컬 스텁으로 덮는다.
 */
@ConfigurationProperties(prefix = "fx.exchange")
data class ExchangeFxProperties(
    val upbitBaseUrl: String = "https://api.upbit.com",
    val bithumbBaseUrl: String = "https://api.bithumb.com",
)
