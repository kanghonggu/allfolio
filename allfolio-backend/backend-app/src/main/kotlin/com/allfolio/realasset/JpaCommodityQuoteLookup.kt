package com.allfolio.realasset

import com.allfolio.unifiedasset.infrastructure.jpa.MarketCommodityQuoteJpaRepository
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * JPA 레포를 [CommodityQuoteLookup]에 맞춘다. `JpaCommodityStore`와 같은 배치다.
 *
 * 다만 그쪽과 달리 **엔티티를 그대로 넘기지 않고 세 필드만 옮긴다.** 평가 어댑터가
 * 엔티티를 통째로 받으면 `prevClose`·`changeRate` 같은 수집 전용 필드까지 손이 닿는데,
 * 그것들은 평가에 아무 의미가 없고 잘못 집으면 조용히 틀린 값이 나온다.
 */
@Component
class JpaCommodityQuoteLookup(
    private val repository: MarketCommodityQuoteJpaRepository,
) : CommodityQuoteLookup {

    override fun latestAsOf(code: String, asOf: LocalDate): CommodityQuote? =
        repository.findFirstByCodeAndTradeDateLessThanEqualOrderByTradeDateDesc(code, asOf)
            ?.let { CommodityQuote(tradeDate = it.tradeDate, price = it.price, unit = it.unit) }
}
