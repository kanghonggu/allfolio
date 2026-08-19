package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.DartDisclosureEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

/**
 * 공시 원장 조회. 종목별 조회를 기본으로 두지 않는다 — 배치는 날짜 기준 전량 적재이고
 * (설계 1절 원칙 1: 종목별 조회 금지, 날짜 기준 전량 적재), 종목별 조회는 보유종목 피드
 * 전용 메서드로만 연다.
 */
interface DartDisclosureJpaRepository : JpaRepository<DartDisclosureEntity, String> {

    /** 델타(방금 저장된 rcept_no들) 중 Tier 4 화이트리스트 트리거를 찾는다 */
    fun findByRceptNoIn(rceptNos: Collection<String>): List<DartDisclosureEntity>

    /**
     * 보유종목 피드 조회. 이 쿼리가 부분 인덱스 `idx_disclosure_feed`
     * (`WHERE is_material AND stock_code IS NOT NULL`)를 타는 것은 조건 순서를 맞춰서가
     * 아니다 — Postgres 플래너는 WHERE 절의 조건 순서를 보지 않는다. 이 쿼리의 술어 집합
     * (`stock_code IN (...)` · `is_material = true`)이 인덱스의 부분 조건을 함의하기 때문에
     * 플래너가 쓸 수 있는 것이고, 순서는 무관하다.
     */
    fun findByStockCodeInAndRceptDtGreaterThanEqualAndIsMaterialTrue(
        stockCodes: Collection<String>,
        from: LocalDate,
    ): List<DartDisclosureEntity>
}
