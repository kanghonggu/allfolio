package com.allfolio.market.commodity.fred

import com.allfolio.fx.RateValuePolicy
import com.allfolio.market.commodity.CommodityFetch
import com.allfolio.market.commodity.CommodityObservation
import com.allfolio.market.commodity.CommodityProperties
import com.allfolio.market.commodity.CommoditySource
import com.allfolio.market.rate.fred.FredApiClient
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * FRED 원자재 소스 — 일간 에너지(EIA)와 월간 지표(IMF)를 모두 담당한다.
 *
 * **[RateValuePolicy.PRICE]를 명시한다.** 클라이언트 기본값은 PERCENT이고, 그대로 두면
 * 구리·금·지수가 파싱 단계에서 버려진다(WTI만 우연히 통과한다).
 *
 * **`sourceName`은 "FRED" 하나다.** 실제 발행처는 EIA(일간)와 IMF(월간)로 갈리고 신선도도
 * 영업일 3일 대 두 달로 완전히 다르지만, 그 구분은 `frequency`(D|M)가 이미 진다 —
 * `(frequency, source)` 짝이 EIA(D,FRED)·IMF(M,FRED)·금(D,FSC)을 그대로 가른다.
 * `FredRateSource`도 "FRED" 하나다.
 */
@Component
class FredCommoditySource(
    private val client: FredApiClient,
    private val properties: CommodityProperties,
) : CommoditySource {

    override val sourceName = "FRED"

    override val codes: List<String>
        get() = fredItems().map { it.code }

    override fun fetch(code: String, from: LocalDate, to: LocalDate): CommodityFetch {
        val item = fredItems().firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("FRED 설정에 없는 원자재 코드입니다: $code")
        val fetched = client.fetch(item.seriesId, from, to, RateValuePolicy.PRICE)
        // RateFetch를 그대로 반환하면 한 줄이 줄지만, 이 경계가 금리 타입이 원자재 코드로
        // 새지 않게 막는다 — 두 도메인은 값 정책도 저장 테이블도 다르다
        return CommodityFetch(
            rows = fetched.rows.map { CommodityObservation(it.quoteDate, it.value) },
            skipped = fetched.skipped,
        )
    }

    /** FSC는 이 소스가 담당하지 않는다 — 일간·월간 둘만 본다 */
    private fun fredItems(): List<CommodityProperties.CommodityItem> =
        properties.fredDaily + properties.fredMonthly
}
