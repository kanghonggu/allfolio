package com.allfolio.market.rate.fred

import com.allfolio.market.rate.MarketRateProperties
import com.allfolio.market.rate.RateFetch
import com.allfolio.market.rate.RateSource
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * FRED(세인트루이스 연은) 미국 금리 소스.
 *
 * 값 정책·구간 밖 필터·멱등 upsert는 전부 공용이다 — 이 클래스는 설정의 시리즈 ID로
 * 조회만 한다. [RateSource]의 KDoc 참조.
 */
@Component
class FredRateSource(
    private val client: FredApiClient,
    private val properties: MarketRateProperties,
) : RateSource {

    override val sourceName = "FRED"

    override val codes: List<String>
        get() = properties.fred.map { it.code }

    override fun fetch(code: String, from: LocalDate, to: LocalDate): RateFetch {
        val series = properties.fred.firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("FRED 설정에 없는 금리 코드입니다: $code")
        return client.fetch(series.seriesId, from, to)
    }
}
