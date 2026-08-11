package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.HistoricalFxRateEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.UUID

interface HistoricalFxRateJpaRepository : JpaRepository<HistoricalFxRateEntity, UUID> {

    /**
     * 지정일 이하의 가장 최근 고시 한 건.
     * 주말·공휴일은 이 쿼리 하나로 직전 영업일에 이어진다.
     * 백필 범위보다 이른 날짜는 행이 없어 null.
     */
    fun findTopByCurrencyAndBaseDateLessThanEqualOrderByBaseDateDesc(
        currency: String,
        baseDate: LocalDate,
    ): HistoricalFxRateEntity?

    /** 백필 시 기존 행을 한 번에 읽어 덮어쓸 대상을 가려내는 용도 (경계 포함) */
    fun findAllByCurrencyAndBaseDateBetween(
        currency: String,
        from: LocalDate,
        to: LocalDate,
    ): List<HistoricalFxRateEntity>
}
