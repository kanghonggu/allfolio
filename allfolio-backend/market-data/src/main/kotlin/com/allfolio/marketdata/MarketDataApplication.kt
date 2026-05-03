package com.allfolio.marketdata

import com.allfolio.marketdata.properties.BinanceWsProperties
import com.allfolio.marketdata.properties.KisWsProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(BinanceWsProperties::class, KisWsProperties::class)
class MarketDataApplication

fun main(args: Array<String>) {
    runApplication<MarketDataApplication>(*args)
}
