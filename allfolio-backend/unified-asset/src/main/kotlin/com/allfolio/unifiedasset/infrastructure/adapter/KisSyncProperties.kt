package com.allfolio.unifiedasset.infrastructure.adapter

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * KIS 잔고조회 연동 설정 (실전 기본값).
 * env: KIS_SYNC_MOCK=true 로 모의투자 전환.
 */
@Component
@ConfigurationProperties(prefix = "kis-sync")
class KisSyncProperties {
    var mock: Boolean = false
    var realBaseUrl: String = "https://openapi.koreainvestment.com:9443"
    var mockBaseUrl: String = "https://openapivts.koreainvestment.com:29443"

    fun baseUrl(): String = if (mock) mockBaseUrl else realBaseUrl
    fun trIdBalance(): String = if (mock) "VTTC8434R" else "TTTC8434R"
}
