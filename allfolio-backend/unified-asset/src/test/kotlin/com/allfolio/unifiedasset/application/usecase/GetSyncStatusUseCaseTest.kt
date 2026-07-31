package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.SyncLogRepository
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountStatus
import com.allfolio.unifiedasset.domain.account.AccountType
import com.allfolio.unifiedasset.domain.sync.SyncLog
import com.allfolio.unifiedasset.domain.sync.SyncLogStatus
import com.allfolio.unifiedasset.domain.sync.SyncTrigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class GetSyncStatusUseCaseTest {

    private class FixedAccountRepository(private val accounts: List<Account>) : AccountRepository {
        override fun save(account: Account): Account = account
        override fun findById(id: UUID): Account? = accounts.find { it.id == id }
        override fun findByUserId(userId: UUID): List<Account> = accounts.filter { it.userId == userId }
        override fun findByProviders(providers: Collection<AccountProvider>): List<Account> = emptyList()
        override fun delete(id: UUID) = Unit
        override fun updateStatus(id: UUID, status: AccountStatus) = Unit
    }

    private class FixedSyncLogRepository(private val latest: Map<UUID, SyncLog>) : SyncLogRepository {
        override fun save(log: SyncLog): SyncLog = log
        override fun findByAccountId(accountId: UUID, limit: Int): List<SyncLog> = emptyList()
        override fun findLatestByUserId(userId: UUID): Map<UUID, SyncLog> = latest
        override fun deleteByAccountId(accountId: UUID) = Unit
    }

    @Test
    fun `계좌별 최신 로그와 syncable 여부를 조합한다`() {
        val userId = UUID.randomUUID()
        val kis = Account.create(userId = userId, provider = AccountProvider.KIS,
            accountType = AccountType.STOCK, accountName = "kis", currency = "KRW")
        val manual = Account.create(userId = userId, provider = AccountProvider.MANUAL,
            accountType = AccountType.MANUAL, accountName = "manual", currency = "KRW")
        val log = SyncLog.create(kis.id, userId, SyncTrigger.SCHEDULED, SyncLogStatus.ERROR, 0, "expired")

        val result = GetSyncStatusUseCase(
            FixedAccountRepository(listOf(kis, manual)),
            FixedSyncLogRepository(mapOf(kis.id to log)),
        ).execute(userId)

        assertEquals(2, result.size)
        val kisRow = result.first { it.accountId == kis.id }
        assertTrue(kisRow.syncable)
        assertEquals("expired", kisRow.lastLog?.errorMessage)
        assertEquals("SCHEDULED", kisRow.lastLog?.trigger)
        val manualRow = result.first { it.accountId == manual.id }
        assertFalse(manualRow.syncable)
        assertNull(manualRow.lastLog)
    }
}
