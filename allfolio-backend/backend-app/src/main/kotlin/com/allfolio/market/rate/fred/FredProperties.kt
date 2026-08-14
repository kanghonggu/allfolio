package com.allfolio.market.rate.fred

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * FRED 접속 설정.
 *
 * **인증키가 쿼리 파라미터에 실린다.** ECOS는 경로 첫 세그먼트지만 FRED는 `api_key=`다.
 * 위치만 다를 뿐 노출 위험은 같다 — 전체 URL을 로그에 찍지 말 것.
 */
@Component
@ConfigurationProperties(prefix = "fred")
class FredProperties {
    var apiKey: String = ""
    var baseUrl: String = "https://api.stlouisfed.org"
}
