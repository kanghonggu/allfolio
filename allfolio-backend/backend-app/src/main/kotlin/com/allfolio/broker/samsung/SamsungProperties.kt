package com.allfolio.broker.samsung

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

@ConfigurationProperties(prefix = "samsung")
data class SamsungProperties(
    val appKey: String = "",
    val appSecret: String = "",
    @DefaultValue("https://openapi.samsungpop.com")
    val baseUrl: String,
    @DefaultValue("http://localhost:8090/api/broker/samsung/callback")
    val redirectUri: String,
) {
    fun isConfigured(): Boolean = appKey.isNotBlank() && appSecret.isNotBlank()
}
