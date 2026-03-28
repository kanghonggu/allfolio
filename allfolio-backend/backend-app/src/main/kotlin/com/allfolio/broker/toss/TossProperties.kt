package com.allfolio.broker.toss

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.DefaultValue

@ConfigurationProperties(prefix = "toss")
data class TossProperties(
    val clientId: String = "",
    val clientSecret: String = "",
    @DefaultValue("https://openapi.toss.im")
    val baseUrl: String,
    @DefaultValue("http://localhost:8090/api/broker/toss/callback")
    val redirectUri: String,
) {
    fun isConfigured(): Boolean = clientId.isNotBlank() && clientSecret.isNotBlank()
}
