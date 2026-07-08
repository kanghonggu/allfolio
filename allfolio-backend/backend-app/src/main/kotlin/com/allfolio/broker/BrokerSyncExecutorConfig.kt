package com.allfolio.broker

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@Configuration
class BrokerSyncExecutorConfig {

    /**
     * 브로커 그룹 병렬 동기화 전용 풀.
     * 크기 = 브로커 타입 수 — 동시성 상한이 구조적으로 브로커 수를 넘지 않는다.
     */
    @Bean(destroyMethod = "shutdown")
    fun brokerSyncExecutor(): ExecutorService {
        val counter = AtomicInteger(1)
        return Executors.newFixedThreadPool(BrokerType.entries.size) { runnable ->
            Thread(runnable, "broker-sync-${counter.getAndIncrement()}").apply { isDaemon = true }
        }
    }
}
