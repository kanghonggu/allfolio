package com.allfolio.marketdata.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

@Configuration
class AsyncConfig {

    /**
     * WebSocket 이벤트 처리용 스레드풀
     * - corePoolSize=4: WS 어댑터 수 × 2
     * - maxPoolSize=8: 버스트 허용
     * - 큐 용량=2000: 백프레셔 (초과 시 CallerRuns)
     */
    @Bean("wsEventExecutor")
    fun wsEventExecutor(): Executor = ThreadPoolTaskExecutor().apply {
        corePoolSize     = 4
        maxPoolSize      = 8
        queueCapacity    = 2000
        threadNamePrefix = "ws-event-"
        setRejectedExecutionHandler(java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy())
        initialize()
    }
}
