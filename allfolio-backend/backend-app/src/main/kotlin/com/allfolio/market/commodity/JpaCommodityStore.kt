package com.allfolio.market.commodity

import com.allfolio.unifiedasset.infrastructure.entity.MarketCommodityQuoteEntity
import com.allfolio.unifiedasset.infrastructure.jpa.MarketCommodityQuoteJpaRepository
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * JPA 레포를 [CommodityCollectService.Store]에 맞춘다.
 *
 * 서비스가 JPA 인터페이스를 직접 받지 않게 하는 얇은 층이다 — 테스트가 스무 개 넘는
 * 상속 메서드를 흉내 내지 않아도 되게 하는 것이 목적이고, 다른 의도는 없다.
 * 포트는 서비스 안에 두고 어댑터만 여기로 뺐다(`JpaRateStore`와 같은 배치).
 */
@Component
class JpaCommodityStore(
    private val repository: MarketCommodityQuoteJpaRepository,
) : CommodityCollectService.Store {

    override fun findRange(code: String, from: LocalDate, to: LocalDate) =
        repository.findByCodeAndTradeDateBetween(code, from, to)

    override fun findLatestBefore(code: String, before: LocalDate): MarketCommodityQuoteEntity? =
        repository.findFirstByCodeAndTradeDateLessThanOrderByTradeDateDesc(code, before)

    override fun saveAll(entities: List<MarketCommodityQuoteEntity>) {
        repository.saveAll(entities)
    }
}
