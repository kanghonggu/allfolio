package com.allfolio.unifiedasset.infrastructure.jpa

import com.allfolio.unifiedasset.infrastructure.entity.CashFlowEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.LocalDate
import java.util.UUID

interface CashFlowJpaRepository : JpaRepository<CashFlowEntity, UUID> {
    fun findByUserIdOrderByFlowDateDesc(userId: UUID): List<CashFlowEntity>
    fun findByUserIdAndFlowDateBetweenOrderByFlowDateDesc(
        userId: UUID, from: LocalDate, to: LocalDate,
    ): List<CashFlowEntity>

    @Modifying
    @Query("DELETE FROM CashFlowEntity c WHERE c.accountId = :accountId")
    fun deleteByAccountId(accountId: UUID)

    /**
     * 원화가 아닌 모든 행을 흐름일자 오름차순으로 (소급 재계산용).
     *
     * 다른 조회와 달리 **사용자 경계를 넘는다.** 재계산은 일회성 운영 작업이라 그렇다 —
     * 일반 조회 경로에서 쓰지 말 것. 도메인 포트(`CashFlowRepository`)에 두지 않은 이유이기도 하다.
     *
     * 날짜 오름차순인 이유는 환율 해석 캐시가 (통화, 날짜) 단위이기 때문이다.
     * 같은 날짜가 뭉쳐 오면 적중률이 오른다 — 재계산은 같은 날짜를 수없이 반복한다.
     *
     * `UPPER`로 비교하는 이유: 저장 시 `uppercase()`가 걸리지만 과거 데이터나
     * 직접 INSERT된 행까지 보장되지는 않는다.
     */
    @Query("SELECT c FROM CashFlowEntity c WHERE UPPER(c.currency) <> 'KRW' ORDER BY c.flowDate ASC")
    fun findNonKrwOrderByFlowDate(): List<CashFlowEntity>
}
