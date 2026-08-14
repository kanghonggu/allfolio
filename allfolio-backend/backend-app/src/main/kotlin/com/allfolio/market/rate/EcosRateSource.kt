package com.allfolio.market.rate

import com.allfolio.fx.EcosApiClient
import com.allfolio.fx.EcosQuery
import com.allfolio.fx.RateValuePolicy
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * ECOS(한국은행) 금리 소스 (AF-102).
 *
 * `RateCollectService`에 인라인으로 있던 조회를 [RateSource] 뒤로 옮긴 것이다 —
 * 동작은 그대로다. 옮긴 이유는 FRED가 두 번째 소스로 붙기 때문이고,
 * 옮기면서 아무것도 바뀌지 않았다는 것은 기존 테스트가 지킨다.
 */
@Component
class EcosRateSource(
    private val client: EcosApiClient,
    private val properties: MarketRateProperties,
) : RateSource {

    override val sourceName = "ECOS"

    override val codes: List<String>
        get() = properties.ecos.map { it.code }

    override fun fetch(code: String, from: LocalDate, to: LocalDate): RateFetch {
        val series = properties.ecos.firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("ECOS 설정에 없는 금리 코드입니다: $code")

        val result = client.fetch(
            EcosQuery(
                statCode = series.statCode,
                itemCode = series.itemCode,
                cycle = series.cycle,
                // 금리는 0.00%도 마이너스도 실재한다 — 환율 정책으로 부르면 그 날이 사라진다
                valuePolicy = RateValuePolicy.PERCENT,
            ),
            from,
            to,
        )
        return RateFetch(
            rows = result.rates.map { RateObservation(it.baseDate, it.value) },
            skipped = result.skipped,
        )
    }
}
