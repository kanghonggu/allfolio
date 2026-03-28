package com.allfolio.fx

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * USDT/KRW 환율 자동 수집 스케줄러
 *
 * 활성화 조건: fx.scheduler.enabled=true
 * 기본 주기: 60초 (fx.scheduler.delay-ms 로 조정 가능)
 *
 * 실패 시:
 *   - ERROR 로그만 남기고 계속 실행
 *   - Redis 기존 값 유지 (TTL 60초 만료 전까지)
 *   - TTL 만료 후 RedisFxRateService 가 fallback-rate 반환
 */
@Component
@ConditionalOnProperty(name = ["fx.scheduler.enabled"], havingValue = "true")
class FxRateScheduler(
    private val fxApiClient: FxApiClient,
    private val fxRateService: FxRateService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${fx.scheduler.delay-ms:60000}")
    fun updateFx() {
        runCatching {
            val rate = fxApiClient.getUsdtKrw()
            fxRateService.setUsdtToKrw(rate)
            log.info("[FxScheduler] updated USDTKRW={}", rate)
        }.onFailure { e ->
            log.error("[FxScheduler] FX update failed — keeping cached rate: {}", e.message)
        }
    }
}
