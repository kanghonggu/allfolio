package com.allfolio.unifiedasset.infrastructure.adapter

import com.allfolio.unifiedasset.application.port.RiskFreeRate
import com.allfolio.unifiedasset.application.port.RiskFreeRateSource
import com.allfolio.unifiedasset.infrastructure.jpa.MarketRateJpaRepository
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 무위험 수익률 = CD(91일). 단기 원화 무위험 금리의 관행적 대용치이고 AF-102가 매 영업일 수집한다.
 * 국고채 3년(`KTB_3Y`)은 기간 프리미엄이 얹혀 단기 비율의 허들로 과하다.
 */
@Component
class JpaRiskFreeRateSource(private val repo: MarketRateJpaRepository) : RiskFreeRateSource {

    override fun latest(asOf: LocalDate): RiskFreeRate? =
        repo.findFirstByRateCodeAndQuoteDateLessThanEqualOrderByQuoteDateDesc(CODE, asOf)
            ?.let { RiskFreeRate(it.rateCode, it.quoteDate, it.rateValue) }

    companion object {
        /** `application.yml` market-rate.ecos의 code와 같아야 한다 */
        const val CODE = "CD_91D"
    }
}
