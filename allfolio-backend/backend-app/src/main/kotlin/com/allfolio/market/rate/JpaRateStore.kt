package com.allfolio.market.rate

import com.allfolio.unifiedasset.infrastructure.entity.MarketRateEntity
import com.allfolio.unifiedasset.infrastructure.jpa.MarketRateJpaRepository
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * JPA 레포를 [RateCollectService.Store]에 맞춘다.
 *
 * 서비스가 JPA 인터페이스를 직접 받지 않게 하는 얇은 층이다 — 테스트가 스무 개 넘는
 * 상속 메서드를 흉내 내지 않아도 되게 하는 것이 목적이고, 다른 의도는 없다.
 *
 * 포트(`Store`)는 서비스 안에 두고 어댑터만 여기로 뺐다: 포트는 서비스가 무엇을 요구하는지의
 * 일부라 붙어 있는 게 맞지만, 어댑터는 그 자체가 별도의 스프링 빈이다.
 */
@Component
class JpaRateStore(private val repository: MarketRateJpaRepository) : RateCollectService.Store {
    override fun findRange(rateCode: String, from: LocalDate, to: LocalDate) =
        repository.findByRateCodeAndQuoteDateBetween(rateCode, from, to)

    override fun saveAll(entities: List<MarketRateEntity>) {
        repository.saveAll(entities)
    }
}
