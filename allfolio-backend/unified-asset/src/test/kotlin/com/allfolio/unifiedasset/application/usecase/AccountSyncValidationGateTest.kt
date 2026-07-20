package com.allfolio.unifiedasset.application.usecase

import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountStatus
import com.allfolio.unifiedasset.domain.account.AccountType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.UUID

class AccountSyncValidationGateTest {

    private val userId = UUID.randomUUID()
    private val period = ReportPeriod.monthly(2026, 6)

    private fun account(
        provider: AccountProvider,
        status: AccountStatus,
        lastSyncedAt: LocalDateTime?,
    ): Account = Account.reconstruct(
        id = UUID.randomUUID(), userId = userId, provider = provider,
        accountType = AccountType.STOCK, accountName = "테스트",
        externalId = null, currency = "KRW", status = status,
        lastSyncedAt = lastSyncedAt, createdAt = LocalDateTime.now(),
        apiKey = null, apiSecret = null, walletAddress = null, chain = null,
    )

    private fun gateWith(vararg accounts: Account): AccountSyncValidationGate {
        val repo = object : AccountRepository {
            override fun save(account: Account) = account
            override fun findById(id: UUID): Account? = null
            override fun findByUserId(userId: UUID) = accounts.toList()
            override fun findByProviders(providers: Collection<AccountProvider>) = emptyList<Account>()
            override fun delete(id: UUID) {}
            override fun updateStatus(id: UUID, status: AccountStatus) {}
        }
        return AccountSyncValidationGate(repo)
    }

    @Test
    fun `ERROR account produces SYNC_ERROR warning`() {
        val gate = gateWith(account(AccountProvider.KIS, AccountStatus.ERROR, LocalDateTime.now()))
        val warnings = gate.check(userId, period)
        assertEquals(listOf("SYNC_ERROR"), warnings.map { it.code })
    }

    @Test
    fun `syncable account never synced produces NEVER_SYNCED warning`() {
        val gate = gateWith(account(AccountProvider.BINANCE, AccountStatus.ACTIVE, null))
        val warnings = gate.check(userId, period)
        assertEquals(listOf("NEVER_SYNCED"), warnings.map { it.code })
    }

    @Test
    fun `syncable account synced before period end produces STALE_SYNC warning`() {
        val gate = gateWith(account(AccountProvider.KIS, AccountStatus.ACTIVE, LocalDateTime.of(2026, 6, 15, 12, 0)))
        val warnings = gate.check(userId, period)
        assertEquals(listOf("STALE_SYNC"), warnings.map { it.code })
    }

    @Test
    fun `healthy account synced after period end produces no warnings`() {
        val gate = gateWith(account(AccountProvider.KIS, AccountStatus.ACTIVE, LocalDateTime.of(2026, 7, 1, 0, 30)))
        assertTrue(gate.check(userId, period).isEmpty())
    }

    @Test
    fun `manual account is exempt from stale check`() {
        val gate = gateWith(account(AccountProvider.MANUAL, AccountStatus.ACTIVE, null))
        assertTrue(gate.check(userId, period).isEmpty())
    }
}
