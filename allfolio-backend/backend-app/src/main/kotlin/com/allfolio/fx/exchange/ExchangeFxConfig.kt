package com.allfolio.fx.exchange

import com.allfolio.fx.FxApiClient
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 거래소 FX 소스 조립.
 *
 * 소스 순서를 @Order로 흩뿌리지 않고 여기 한 줄에 모으는 이유는, 순서가 곧 폴백 정책이라
 * 코드를 읽는 사람이 한 곳에서 확인할 수 있어야 하기 때문이다.
 *
 * 활성화 조건은 fx.scheduler.enabled=true로 [com.allfolio.fx.FxRateScheduler]와 같다 —
 * 스케줄러 없이 클라이언트만 있으면 아무도 호출하지 않는다.
 */
@Configuration
@ConditionalOnProperty(name = ["fx.scheduler.enabled"], havingValue = "true")
class ExchangeFxConfig {

    @Bean
    fun exchangeFxApiClient(
        properties: ExchangeFxProperties,
        upbitParser: UpbitFxParser,
        bithumbParser: BithumbFxParser,
    ): FxApiClient = ExchangeFxApiClient(
        listOf(
            UpbitFxSource(properties.upbitBaseUrl, upbitParser),
            BithumbFxSource(properties.bithumbBaseUrl, bithumbParser),
        )
    )
}
