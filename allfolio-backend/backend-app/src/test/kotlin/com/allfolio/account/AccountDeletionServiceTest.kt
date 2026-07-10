package com.allfolio.account

import com.allfolio.pnl.PositionCacheService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.util.UUID

class AccountDeletionServiceTest {

    private val repo = mock(AccountPurgeRepository::class.java)
    private val cache = mock(PositionCacheService::class.java)
    private val service = AccountDeletionService(repo, cache)

    private val userId = UUID.randomUUID()

    @Test
    fun `purge deletes all owned data in FK-safe order and evicts each portfolio cache`() {
        val pf1 = UUID.randomUUID()
        val pf2 = UUID.randomUUID()
        `when`(repo.findPortfolioIds(userId)).thenReturn(listOf(pf1, pf2))

        service.purge(userId)

        val ordered = inOrder(repo)
        ordered.verify(repo).findPortfolioIds(userId)
        ordered.verify(repo).deleteBrokerAuth(userId)
        ordered.verify(repo).deleteAiConfigs(userId)
        ordered.verify(repo).deleteGoals(userId)
        ordered.verify(repo).deleteUaAccounts(userId)
        ordered.verify(repo).deleteRiskDaily(userId)
        ordered.verify(repo).deletePerformanceDaily(userId)
        ordered.verify(repo).deletePositionDaily(userId)
        ordered.verify(repo).deleteBrokerSyncState(userId)
        ordered.verify(repo).deleteTradeRaw(userId)
        ordered.verify(repo).deletePortfolios(userId)
        ordered.verify(repo).deleteUser(userId)

        verify(cache).evictPortfolio(pf1)
        verify(cache).evictPortfolio(pf2)
    }

    @Test
    fun `purge with no portfolios still deletes user-scoped data and touches no cache`() {
        `when`(repo.findPortfolioIds(userId)).thenReturn(emptyList())

        service.purge(userId)

        verify(repo).deleteBrokerAuth(userId)
        verify(repo).deleteUaAccounts(userId)
        verify(repo).deleteTradeRaw(userId)
        verify(repo).deletePortfolios(userId)
        verify(repo).deleteUser(userId)
        verifyNoInteractions(cache)
    }
}
