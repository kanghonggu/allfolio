package com.allfolio.fx.upbit

import com.allfolio.fx.HistoricalRateSource
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Upbit 일봉 소스 조립.
 *
 * base-url을 프로퍼티로 빼는 이유는 테스트에서 스텁 서버를 물리기 위해서다.
 * 기본값은 운영 주소다 — Binance가 testnet 기본값에 묶여 운영이 테스트넷 가격으로
 * 자산을 평가하던 사고를 되풀이하지 않는다.
 *
 * [EcosHistoricalRateSource][com.allfolio.fx.EcosHistoricalRateSource]는 `@Component`라
 * 이 빈과 함께 `List<HistoricalRateSource>`로 주입된다. 둘은 `supports`로 갈리므로
 * (ECOS는 `ecos.series` 설정, Upbit은 BTC·ETH) 순서에 의존하지 않는다.
 */
@Configuration
class UpbitCandleConfig {

    @Bean
    fun upbitCandleRateSource(
        @Value("\${fx.upbit.candle-base-url:https://api.upbit.com}") baseUrl: String,
        parser: UpbitCandleParser,
    ): HistoricalRateSource = UpbitCandleRateSource(UpbitCandleClient(baseUrl), parser)
}
