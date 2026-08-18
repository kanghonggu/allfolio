package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.DartInsiderTradeEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface DartInsiderTradeJpaRepository : JpaRepository<DartInsiderTradeEntity, Long> {

    /** Task 11 — 델타 중 이미 저장된 소유변동 행을 찾는다(중복 elestock 호출 방지) */
    fun findByRceptNoIn(rceptNos: Collection<String>): List<DartInsiderTradeEntity>

    /** Task 14 — 보유종목 피드. 최신 변동부터 보여줘야 해서 정렬을 메서드명에 고정한다 */
    fun findByStockCodeInAndReportDateGreaterThanEqualOrderByReportDateDesc(
        stockCodes: Collection<String>,
        from: LocalDate,
    ): List<DartInsiderTradeEntity>
}
