package com.allfolio.unifiedasset.application.usecase

import com.allfolio.common.crypto.LegacyPlaintextDetectedException
import com.allfolio.common.crypto.SENSITIVE_DATA_RECONNECTION_REQUIRED_MESSAGE
import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.SyncAdapter
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountStatus
import com.allfolio.unifiedasset.domain.asset.Asset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

class SyncAccountUseCaseSensitiveDataTest {

    @Test
    fun `legacy plaintext account credentials return reconnect-required sync result`() {
        val accountId = UUID.randomUUID()
        val accountRepository = ThrowingAccountRepository(LegacyPlaintextDetectedException("legacy plaintext"))
        val adapter = RecordingSyncAdapter()
        val service = SyncAccountUseCase(
            accountRepository = accountRepository,
            assetRepository = NoopAssetRepository(),
            adapters = listOf(adapter),
            snapshotService = PerformanceSnapshotService(mock(JdbcTemplate::class.java)),
        )

        val result = service.execute(accountId)

        assertEquals(accountId, result.accountId)
        assertEquals(AccountStatus.ERROR, result.status)
        assertEquals(SENSITIVE_DATA_RECONNECTION_REQUIRED_MESSAGE, result.error)
        assertFalse(adapter.called)
    }

    private class ThrowingAccountRepository(
        private val exception: RuntimeException,
    ) : AccountRepository {
        override fun save(account: Account): Account = account
        override fun findById(id: UUID): Account? = throw exception
        override fun findByUserId(userId: UUID): List<Account> = emptyList()
        override fun delete(id: UUID) = Unit
        override fun updateStatus(id: UUID, status: AccountStatus) = Unit
    }

    private class RecordingSyncAdapter : SyncAdapter {
        var called: Boolean = false
        override val supportedProvider: AccountProvider = AccountProvider.BINANCE
        override fun sync(account: Account): List<Asset> {
            called = true
            return emptyList()
        }
    }

    private class NoopAssetRepository : AssetRepository {
        override fun save(asset: Asset): Asset = asset
        override fun saveAll(assets: List<Asset>): List<Asset> = assets
        override fun findById(id: UUID): Asset? = null
        override fun findByUserId(userId: UUID): List<Asset> = emptyList()
        override fun findByAccountId(accountId: UUID): List<Asset> = emptyList()
        override fun deleteByAccountId(accountId: UUID) = Unit
        override fun delete(id: UUID) = Unit
    }
}
