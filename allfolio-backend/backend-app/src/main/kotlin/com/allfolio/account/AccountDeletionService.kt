package com.allfolio.account

import com.allfolio.pnl.PositionCacheService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 계정 완전 삭제 오케스트레이터.
 * FK 안전 순서(자식→부모)로 사용자 소유 데이터를 모두 삭제한 뒤,
 * 각 포트폴리오의 Redis 포지션 캐시를 evict 한다.
 */
@Service
class AccountDeletionService(
    private val purgeRepository: AccountPurgeRepository,
    private val positionCacheService: PositionCacheService,
) {
    @Transactional
    fun purge(userId: UUID) {
        val portfolioIds = purgeRepository.findPortfolioIds(userId)

        purgeRepository.deleteBrokerAuth(userId)
        purgeRepository.deleteAiConfigs(userId)
        purgeRepository.deleteGoals(userId)
        purgeRepository.deleteReportArchive(userId)
        purgeRepository.deleteUaAccounts(userId)
        purgeRepository.deleteRiskDaily(userId)
        purgeRepository.deletePerformanceDaily(userId)
        purgeRepository.deletePositionDaily(userId)
        purgeRepository.deleteBrokerSyncState(userId)
        purgeRepository.deleteBinanceSyncCursor(userId)
        purgeRepository.deleteTradeRaw(userId)
        purgeRepository.deletePortfolios(userId)
        purgeRepository.deleteUser(userId)

        portfolioIds.forEach { positionCacheService.evictPortfolio(it) }
    }
}
