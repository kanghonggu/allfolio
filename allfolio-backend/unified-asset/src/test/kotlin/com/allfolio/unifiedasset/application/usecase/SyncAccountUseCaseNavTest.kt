package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.application.port.SyncAdapter
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountStatus
import com.allfolio.unifiedasset.domain.account.AccountType
import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.asset.AssetCategory
import com.allfolio.unifiedasset.domain.asset.AssetSourceType
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.domain.asset.ValuationMethod
import com.allfolio.unifiedasset.domain.sync.SyncLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import java.math.BigDecimal
import java.util.UUID

class SyncAccountUseCaseNavTest {

    /** KRW 1:1, USD → 1300원. */
    private val fx = object : FxConverter {
        override fun toKrw(amount: BigDecimal, currency: String): BigDecimal =
            if (currency.uppercase() == "KRW") amount else amount.multiply(BigDecimal("1300"))
    }

    @Test
    fun `records NAV converted to KRW across a mixed-currency portfolio`() {
        val userId = UUID.randomUUID()
        val account = Account.create(
            userId = userId,
            provider = AccountProvider.BINANCE,
            accountType = AccountType.EXCHANGE,
            accountName = "binance",
        )

        // Portfolio spanning two currencies: 1,000,000 KRW stock + 1,000 USD crypto.
        val assets = listOf(
            asset(userId, account.id, BigDecimal("1000000"), "KRW"),
            asset(userId, account.id, BigDecimal("1000"), "USD"),
        )

        val snapshot = mock(PerformanceSnapshotService::class.java)
        val service = SyncAccountUseCase(
            accountRepository = FixedAccountRepository(account),
            assetRepository = FixedAssetRepository(assets),
            adapters = listOf(EmptySyncAdapter(account.provider)),
            snapshotService = snapshot,
            fx = fx,
            syncLogRepository = NoopSyncLogRepository(),
        )

        service.execute(account.id)

        val navCaptor = ArgumentCaptor.forClass(BigDecimal::class.java)
        verify(snapshot).record(eqUuid(userId), captureBd(navCaptor))
        // 1,000,000 + 1,000 * 1,300 = 2,300,000 (raw sum would be a meaningless 1,001,000)
        assertEquals(0, BigDecimal("2300000").compareTo(navCaptor.value)) {
            "expected KRW-converted NAV 2,300,000 but was ${navCaptor.value}"
        }
    }

    // Kotlin non-null 파라미터에 Mockito matcher를 쓰기 위한 null-safe 래퍼.
    private fun eqUuid(v: UUID): UUID = eq(v) ?: v
    private fun captureBd(c: ArgumentCaptor<BigDecimal>): BigDecimal = c.capture() ?: BigDecimal.ZERO

    private fun asset(userId: UUID, accountId: UUID, value: BigDecimal, currency: String): Asset =
        Asset.create(
            userId = userId,
            accountId = accountId,
            category = AssetCategory.FINANCIAL,
            type = AssetType.STOCK,
            sourceType = AssetSourceType.STOCK_API,
            name = "test-$currency",
            symbol = null,
            quantity = BigDecimal.ONE,
            purchasePrice = value,
            currentValue = value,
            currency = currency,
            valuationMethod = ValuationMethod.BALANCE,
        )

    private class FixedAccountRepository(private val account: Account) : AccountRepository {
        override fun save(account: Account): Account = account
        override fun findById(id: UUID): Account? = account
        override fun findByUserId(userId: UUID): List<Account> = listOf(account)
        override fun findByProviders(providers: Collection<AccountProvider>): List<Account> = emptyList()
        override fun delete(id: UUID) = Unit
        override fun updateStatus(id: UUID, status: AccountStatus) = Unit
    }

    private class FixedAssetRepository(private val assets: List<Asset>) : AssetRepository {
        override fun save(asset: Asset): Asset = asset
        override fun saveAll(assets: List<Asset>): List<Asset> = assets
        override fun findById(id: UUID): Asset? = null
        override fun findByUserId(userId: UUID): List<Asset> = assets
        override fun findByAccountId(accountId: UUID): List<Asset> = assets
        override fun deleteByAccountId(accountId: UUID) = Unit
        override fun delete(id: UUID) = Unit
    }

    private class EmptySyncAdapter(override val supportedProvider: AccountProvider) : SyncAdapter {
        override fun sync(account: Account): List<Asset> = emptyList()
    }

    private class NoopSyncLogRepository : com.allfolio.unifiedasset.application.port.SyncLogRepository {
        override fun save(log: SyncLog): SyncLog = log
        override fun findByAccountId(accountId: UUID, limit: Int): List<SyncLog> = emptyList()
        override fun findLatestByUserId(userId: UUID): Map<UUID, SyncLog> = emptyMap()
        override fun deleteByAccountId(accountId: UUID) = Unit
    }
}
