package com.allfolio.trade.infrastructure.repository

import com.allfolio.trade.infrastructure.entity.TradeRawEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDateTime
import java.util.UUID

/**
 * TradeRaw 저장소
 *
 * 제약:
 * - UPDATE 메서드 없음
 * - DELETE 메서드 없음
 * - save() 만 사용 (INSERT ONLY 보장은 @Immutable + 운영 정책)
 */
interface TradeRawJpaRepository : JpaRepository<TradeRawEntity, UUID> {

    /**
     * 포트폴리오의 특정 시점까지의 거래 이력 조회 (Snapshot 재계산용)
     * executedAt 오름차순 — PositionEngine은 시간순 정렬을 전제로 함
     */
    fun findByPortfolioIdAndExecutedAtLessThanEqualOrderByExecutedAtAsc(
        portfolioId: UUID,
        executedAt: LocalDateTime,
    ): List<TradeRawEntity>

    /**
     * 자산별 분리 조회 — PositionEngine 입력 구성용
     */
    fun findByPortfolioIdAndAssetIdAndExecutedAtLessThanEqualOrderByExecutedAtAsc(
        portfolioId: UUID,
        assetId: UUID,
        executedAt: LocalDateTime,
    ): List<TradeRawEntity>

    fun existsByPortfolioId(portfolioId: UUID): Boolean

    /** 거래 내역 목록 — 최신순, 최대 200건 (프론트 조회용) */
    fun findTop200ByPortfolioIdOrderByExecutedAtDesc(portfolioId: UUID): List<TradeRawEntity>
}
