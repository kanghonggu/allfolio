package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.CashFlowRepository
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
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.domain.cashflow.FlowType
import com.allfolio.unifiedasset.domain.sync.SyncLog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * QA P1 #8 — 계좌 최초 동기화 시 초기 평가액을 자동 external inflow(DEPOSIT)로 기록.
 * 입출금 0건인데 TWR이 초기 편입 자산을 수익으로 오인(+2060%)하던 근본 원인.
 */
class SyncAccountUseCaseInitialFlowTest {

    private val fx = object : FxConverter {
        override fun toKrw(amount: BigDecimal, currency: String): BigDecimal =
            if (currency.uppercase() == "KRW") amount else amount.multiply(BigDecimal("1300"))

        override fun rateOf(currency: String): BigDecimal =
            if (currency.uppercase() == "KRW") BigDecimal.ONE else BigDecimal("1300")
    }

    private val userId = UUID.randomUUID()
    private val account = Account.create(
        userId = userId, provider = AccountProvider.BINANCE,
        accountType = AccountType.EXCHANGE, accountName = "binance",
    )

    private class RecordingCashFlowRepository : CashFlowRepository {
        val saved = mutableListOf<CashFlow>()
        override fun save(cashFlow: CashFlow): CashFlow { saved.add(cashFlow); return cashFlow }
        override fun findById(id: UUID): CashFlow? = null
        override fun findByUserIdAndPeriod(userId: UUID, from: LocalDate, to: LocalDate) = emptyList<CashFlow>()
        override fun findByUserId(userId: UUID) = emptyList<CashFlow>()
        override fun delete(id: UUID) = Unit
        override fun deleteByAccountId(accountId: UUID) = Unit
    }

    /** deleteByAccountId 이전의 기존 자산과 이후 저장 자산을 구분하는 상태형 fake. */
    private class StatefulAssetRepository(preExisting: List<Asset>) : AssetRepository {
        private val byAccount = preExisting.toMutableList()
        override fun save(asset: Asset): Asset = asset
        override fun saveAll(assets: List<Asset>): List<Asset> { byAccount.addAll(assets); return assets }
        override fun findById(id: UUID): Asset? = null
        override fun findByUserId(userId: UUID): List<Asset> = byAccount
        override fun findByAccountId(accountId: UUID): List<Asset> = byAccount
        override fun deleteByAccountId(accountId: UUID) { byAccount.clear() }
        override fun delete(id: UUID) = Unit
    }

    private fun asset(value: String, currency: String): Asset = Asset.create(
        userId = userId, accountId = account.id,
        category = AssetCategory.FINANCIAL, type = AssetType.CRYPTO, sourceType = AssetSourceType.EXCHANGE_API,
        name = "test-$currency", symbol = null, quantity = BigDecimal.ONE,
        purchasePrice = BigDecimal(value), currentValue = BigDecimal(value),
        currency = currency, valuationMethod = ValuationMethod.BALANCE,
    )

    private fun useCase(
        assetRepo: AssetRepository,
        cashFlows: RecordingCashFlowRepository,
        synced: List<Asset>,
    ) = SyncAccountUseCase(
        accountRepository = FixedAccountRepository(account),
        assetRepository = assetRepo,
        adapters = listOf(object : SyncAdapter {
            override val supportedProvider = account.provider
            override fun sync(account: Account): List<Asset> = synced
        }),
        snapshotService = mock(PerformanceSnapshotService::class.java),
        fx = fx,
        syncLogRepository = NoopSyncLogRepository(),
        reconMutex = NoopReconMutex(),
        cashFlowRepository = cashFlows,
        stockTradeRepository = FakeStockTradeRepository(),
    )

    @Test
    fun `최초 동기화면 초기 평가액을 DEPOSIT flow로 기록한다`() {
        val cashFlows = RecordingCashFlowRepository()
        val synced = listOf(asset("1000", "USD"), asset("500000", "KRW"))

        useCase(StatefulAssetRepository(emptyList()), cashFlows, synced).execute(account.id)

        val flow = cashFlows.saved.single()
        assertThat(flow.type).isEqualTo(FlowType.DEPOSIT)
        assertThat(flow.accountId).isEqualTo(account.id)
        // 1,000 USD × 1,300 + 500,000 KRW = 1,800,000
        assertThat(flow.amountKrw).isEqualByComparingTo("1800000")
    }

    @Test
    fun `재동기화(기존 자산 존재)면 flow를 기록하지 않는다`() {
        val cashFlows = RecordingCashFlowRepository()
        val synced = listOf(asset("1100", "USD"))

        useCase(StatefulAssetRepository(listOf(asset("1000", "USD"))), cashFlows, synced)
            .execute(account.id)

        assertThat(cashFlows.saved).isEmpty()
    }

    @Test
    fun `빈 계좌 동기화는 flow를 기록하지 않는다`() {
        val cashFlows = RecordingCashFlowRepository()

        useCase(StatefulAssetRepository(emptyList()), cashFlows, emptyList()).execute(account.id)

        assertThat(cashFlows.saved).isEmpty()
    }

    // ── 공용 fakes (NavTest와 동일 패턴) ──
    private class FixedAccountRepository(private val account: Account) : AccountRepository {
        override fun save(account: Account): Account = account
        override fun findById(id: UUID): Account? = account
        override fun findByUserId(userId: UUID): List<Account> = listOf(account)
        override fun findByProviders(providers: Collection<AccountProvider>): List<Account> = emptyList()
        override fun delete(id: UUID) = Unit
        override fun updateStatus(id: UUID, status: AccountStatus) = Unit
    }

    private class NoopSyncLogRepository : com.allfolio.unifiedasset.application.port.SyncLogRepository {
        override fun save(log: SyncLog): SyncLog = log
        override fun findByAccountId(accountId: UUID, limit: Int): List<SyncLog> = emptyList()
        override fun findLatestByUserId(userId: UUID): Map<UUID, SyncLog> = emptyMap()
        override fun deleteByAccountId(accountId: UUID) = Unit
    }

    private class NoopReconMutex : com.allfolio.unifiedasset.application.port.ReconMutex {
        override fun tryAcquire(userId: UUID): String? = "token"
        override fun release(userId: UUID, token: String) = Unit
    }
}
