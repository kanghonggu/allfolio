package com.allfolio.account

import com.allfolio.auth.UserEntity
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import java.util.UUID

/**
 * 계정 완전 삭제(파기) 전용 네이티브 삭제 모음.
 *
 * trade_raw 삭제 메서드가 이 인터페이스에만 존재한다 — TradeRawJpaRepository는
 * 삭제 메서드 없는 채로 유지되어 "원장 삭제 금지" 불변식을 보존한다.
 * 호출 순서는 AccountDeletionService가 FK 안전하게 관리한다.
 */
interface AccountPurgeRepository : Repository<UserEntity, UUID> {

    @Query("SELECT id FROM portfolios WHERE user_id = :userId", nativeQuery = true)
    fun findPortfolioIds(userId: UUID): List<UUID>

    @Modifying
    @Query("DELETE FROM broker_auth WHERE user_id = :userId", nativeQuery = true)
    fun deleteBrokerAuth(userId: UUID): Int

    @Modifying
    @Query("DELETE FROM ua_ai_configs WHERE user_id = :userId", nativeQuery = true)
    fun deleteAiConfigs(userId: UUID): Int

    @Modifying
    @Query("DELETE FROM ua_goals WHERE user_id = :userId", nativeQuery = true)
    fun deleteGoals(userId: UUID): Int

    /** ua_assets / ua_stock_trades 는 ua_accounts FK cascade 로 함께 삭제된다. */
    @Modifying
    @Query("DELETE FROM ua_accounts WHERE user_id = :userId", nativeQuery = true)
    fun deleteUaAccounts(userId: UUID): Int

    @Modifying
    @Query("DELETE FROM risk_daily WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = :userId)", nativeQuery = true)
    fun deleteRiskDaily(userId: UUID): Int

    @Modifying
    @Query("DELETE FROM performance_daily WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = :userId)", nativeQuery = true)
    fun deletePerformanceDaily(userId: UUID): Int

    @Modifying
    @Query("DELETE FROM position_daily WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = :userId)", nativeQuery = true)
    fun deletePositionDaily(userId: UUID): Int

    @Modifying
    @Query("DELETE FROM broker_sync_state WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = :userId)", nativeQuery = true)
    fun deleteBrokerSyncState(userId: UUID): Int

    @Modifying
    @Query("DELETE FROM binance_sync_cursor WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = :userId)", nativeQuery = true)
    fun deleteBinanceSyncCursor(userId: UUID): Int

    /** 계정 파기 전용 예외 — trade_raw 는 평소 삭제 금지(@Immutable, INSERT ONLY). */
    @Modifying
    @Query("DELETE FROM trade_raw WHERE portfolio_id IN (SELECT id FROM portfolios WHERE user_id = :userId)", nativeQuery = true)
    fun deleteTradeRaw(userId: UUID): Int

    @Modifying
    @Query("DELETE FROM portfolios WHERE user_id = :userId", nativeQuery = true)
    fun deletePortfolios(userId: UUID): Int

    /** app_refresh_tokens 는 FK cascade 로 함께 삭제된다. */
    @Modifying
    @Query("DELETE FROM app_users WHERE id = :userId", nativeQuery = true)
    fun deleteUser(userId: UUID): Int
}
