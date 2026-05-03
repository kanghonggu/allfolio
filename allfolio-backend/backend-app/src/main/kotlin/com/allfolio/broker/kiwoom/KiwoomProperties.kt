package com.allfolio.broker.kiwoom

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "kiwoom")
class KiwoomProperties {
    var appKey: String = ""
    var appSecret: String = ""
    var baseUrl: String = "https://openapi.kiwoom.com"
    var redirectUri: String = ""

    fun isConfigured() = appKey.isNotBlank() && appSecret.isNotBlank()
}
