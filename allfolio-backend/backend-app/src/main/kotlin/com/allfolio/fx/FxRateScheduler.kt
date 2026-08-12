package com.allfolio.fx

import com.allfolio.fx.exchange.FxSymbols
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * KRW 시세 자동 수집 스케줄러 (USDT·BTC·ETH)
 *
 * 활성화 조건: fx.scheduler.enabled=true
 * 기본 주기: 60초 (fx.scheduler.delay-ms 로 조정 가능)
 *
 * 실패 시:
 *   - ERROR 로그만 남기고 계속 실행
 *   - Redis 기존 값 유지 (USDT는 TTL 180초, 코인은 24시간)
 *   - USDT는 TTL 만료 후 fallback-rate로, 코인은 상수가 없으므로 조회가 예외를 던진다
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
            val rates = fxApiClient.fetchKrwRates()

            rates[FxSymbols.USDT]?.let { fxRateService.setUsdtToKrw(it) }
            FxSymbols.CRYPTO.forEach { symbol ->
                rates[symbol]?.let { fxRateService.setCryptoToKrw(symbol, it) }
            }

            log.info("[FxScheduler] updated {}", rates)
        }.onFailure { e ->
            log.error("[FxScheduler] FX update failed — keeping cached rate: {}", e.message)
        }
    }
}
