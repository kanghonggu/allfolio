package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.MarketRateEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.UUID

interface MarketRateJpaRepository : JpaRepository<MarketRateEntity, UUID> {

    /**
     * 그 지표의 구간 내 기존 행. 수집은 구간을 통째로 받아 덮으므로 한 번에 읽는다 —
     * 행마다 조회하면 2주 x 6종목이 84번의 왕복이 된다(Neon은 원격이다).
     */
    fun findByRateCodeAndQuoteDateBetween(
        rateCode: String,
        from: LocalDate,
        to: LocalDate,
    ): List<MarketRateEntity>
}
