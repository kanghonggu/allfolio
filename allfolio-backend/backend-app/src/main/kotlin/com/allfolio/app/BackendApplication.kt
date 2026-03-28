package com.allfolio.app

import com.allfolio.broker.samsung.SamsungProperties
import com.allfolio.broker.toss.TossProperties
import com.allfolio.external.crypto.BinanceProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication(scanBasePackages = ["com.allfolio"])
@EntityScan(basePackages = ["com.allfolio"])
@EnableJpaRepositories(basePackages = ["com.allfolio"])
@EnableScheduling
@EnableAsync
@EnableConfigurationProperties(BinanceProperties::class, TossProperties::class, SamsungProperties::class)
class BackendApplication

fun main(args: Array<String>) {
    runApplication<BackendApplication>(*args)
}
