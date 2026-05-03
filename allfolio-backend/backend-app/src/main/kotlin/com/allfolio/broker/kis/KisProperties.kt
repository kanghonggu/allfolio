package com.allfolio.broker.kis

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "kis")
class KisProperties {
    var appKey: String = ""
    var appSecret: String = ""
    var baseUrl: String = "https://openapi.koreainvestment.com:9443"
    var wsUrl: String = "wss://ops.koreainvestment.com:21000"
    var mock: Boolean = false          // true = 모의투자 (tr_id 접두사 V vs T)
    var redirectUri: String = ""
    var symbols: String = ""           // 실시간 구독할 종목코드 콤마구분 ex) 005930,000660

    fun isConfigured() = appKey.isNotBlank() && appSecret.isNotBlank()

    fun symbolList(): List<String> =
        if (symbols.isBlank()) emptyList()
        else symbols.split(",").map { it.trim() }.filter { it.isNotBlank() }

    /** 거래내역 조회 tr_id (실전/모의) */
    fun trIdDailyOrder(): String = if (mock) "VTTC8001R" else "TTTC8001R"
}
