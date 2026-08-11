package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.application.port.KrwConversion
import com.allfolio.unifiedasset.application.port.ReconMutex
import com.allfolio.unifiedasset.application.port.SyncAdapter
import com.allfolio.unifiedasset.application.port.SyncLogRepository
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountStatus
import com.allfolio.unifiedasset.domain.account.AccountType
import com.allfolio.unifiedasset.domain.account.StockTrade
import com.allfolio.unifiedasset.domain.account.StockTradeType
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
 * AF-93 — 거래 로그가 있는 계좌는 초기 편입 현금흐름을 체결일·체결금액으로 소급 생성한다.
 *
 * 예전에는 오늘 날짜·현재 평가액으로 한 건만 남겨서, 1년 전 700만을 넣어 2,315만이 된
 * 계좌가 "오늘 2,315만 입금"으로 기록됐다 — 과거 수익 1,615만이 통째로 사라졌다.
 */
class SyncAccountUseCaseBackdatedInflowTest {

    private val userId = UUID.randomUUID()
    private val account = Account.create(
        userId = userId, provider = AccountProvider.STOCK,
        accountType = AccountType.STOCK, accountName = "증권계좌", currency = "KRW",
    )
    private val usdAccount = Account.create(
        userId = userId, provider = AccountProvider.STOCK,
        accountType = AccountType.STOCK, accountName = "달러계좌", currency = "USD",
    )

    private val fx = object : FxConverter {
        override fun toKrw(amount: BigDecimal, currency: String): BigDecimal =
            if (currency.uppercase() == "KRW") amount else amount.multiply(BigDecimal("1300"))
    }

    @Test
    fun `거래 로그가 있으면 체결일과 체결금액으로 현금흐름을 남긴다`() {
        val cashFlows = RecordingCashFlowRepository()
        val trade = buy("삼성전자", quantity = 100, price = 70_000, on = LocalDate.of(2025, 8, 11))

        useCase(cashFlows, listOf(trade), synced = listOf(asset("23150000"))).execute(account.id)

        val flow = cashFlows.saved.single()
        assertThat(flow.type).isEqualTo(FlowType.DEPOSIT)
        assertThat(flow.flowDate).isEqualTo(LocalDate.of(2025, 8, 11))
        // 오늘 평가액 2,315만이 아니라 실제 투입액 700만
        assertThat(flow.amountKrw).isEqualByComparingTo("7000000")
        // 원화 계좌는 환산 자체가 없다 — 다수 경로이므로 계약을 여기서 고정한다
        assertThat(flow.memo).doesNotContain("환율 추정치")
    }

    @Test
    fun `수수료와 세금은 투입액에 포함한다`() {
        val cashFlows = RecordingCashFlowRepository()
        val trade = buy("삼성전자", quantity = 10, price = 70_000, on = LocalDate.of(2025, 8, 11),
            fee = BigDecimal("1500"), tax = BigDecimal("500"))

        useCase(cashFlows, listOf(trade), synced = listOf(asset("800000"))).execute(account.id)

        assertThat(cashFlows.saved.single().amountKrw).isEqualByComparingTo("702000")
    }

    @Test
    fun `매도는 회수로 기록하고 배당은 외부 투입이 아니라 건너뛴다`() {
        val cashFlows = RecordingCashFlowRepository()
        val trades = listOf(
            buy("삼성전자", quantity = 100, price = 70_000, on = LocalDate.of(2025, 8, 11)),
            sell("삼성전자", quantity = 40, price = 80_000, on = LocalDate.of(2026, 1, 5)),
            dividend("삼성전자", amount = 50_000, on = LocalDate.of(2026, 4, 1)),
        )

        useCase(cashFlows, trades, synced = listOf(asset("5000000"))).execute(account.id)

        assertThat(cashFlows.saved).hasSize(2)
        val (deposit, withdrawal) = cashFlows.saved.partition { it.type == FlowType.DEPOSIT }
        assertThat(deposit.single().amountKrw).isEqualByComparingTo("7000000")
        assertThat(withdrawal.single().amountKrw).isEqualByComparingTo("3200000")
        assertThat(withdrawal.single().flowDate).isEqualTo(LocalDate.of(2026, 1, 5))
    }

    @Test
    fun `거래 로그가 없는 계좌는 연동 시점 평가액으로 남긴다`() {
        val cashFlows = RecordingCashFlowRepository()

        useCase(cashFlows, trades = emptyList(), synced = listOf(asset("1800000"))).execute(account.id)

        val flow = cashFlows.saved.single()
        assertThat(flow.flowDate).isEqualTo(LocalDate.now(java.time.ZoneId.of("Asia/Seoul")))
        assertThat(flow.amountKrw).isEqualByComparingTo("1800000")
    }

    @Test
    fun `배당만 있는 거래 로그도 초기 편입을 남긴다`() {
        // 배당은 외부 투입이 아니라 걸러진다. 그렇다고 원금이 0인 건 아니다 —
        // 여기서 아무것도 안 남기면 다음 sync는 hadAssets=true라 영영 기록되지 않고,
        // 그 계좌는 NAV 전체가 수익으로 잡힌다.
        val cashFlows = RecordingCashFlowRepository()
        val trades = listOf(dividend("삼성전자", amount = 50_000, on = LocalDate.of(2026, 4, 1)))

        useCase(cashFlows, trades, synced = listOf(asset("1800000"))).execute(account.id)

        val flow = cashFlows.saved.single()
        assertThat(flow.flowDate).isEqualTo(LocalDate.now(java.time.ZoneId.of("Asia/Seoul")))
        assertThat(flow.amountKrw).isEqualByComparingTo("1800000")
    }

    @Test
    fun `이미 같은 계좌의 현금흐름이 있으면 중복 생성하지 않는다`() {
        val existing = CashFlow.create(
            userId = userId, accountId = account.id, flowDate = LocalDate.of(2026, 7, 14),
            type = FlowType.DEPOSIT, amount = BigDecimal("7000000"), currency = "KRW",
            amountKrw = BigDecimal("7000000"), memo = "계좌 연동 초기 자산 편입(소급 보정)",
        )
        val cashFlows = RecordingCashFlowRepository(existing)

        useCase(cashFlows, listOf(buy("삼성전자", 100, 70_000, LocalDate.of(2025, 8, 11))),
            synced = listOf(asset("23150000"))).execute(account.id)

        assertThat(cashFlows.saved).isEmpty()
    }

    @Test
    fun `계좌에 매이지 않은 수동 보정 레코드가 있어도 중복 생성하지 않는다`() {
        // 운영 계정의 보정 레코드는 계좌 없이 사용자 단위로 들어가 있을 수 있다
        val existing = CashFlow.create(
            userId = userId, accountId = null, flowDate = LocalDate.of(2026, 7, 14),
            type = FlowType.DEPOSIT, amount = BigDecimal("7000000"), currency = "KRW",
            amountKrw = BigDecimal("7000000"), memo = "계좌 연동 초기 자산 편입(소급 보정)",
        )
        val cashFlows = RecordingCashFlowRepository(existing)

        useCase(cashFlows, listOf(buy("삼성전자", 100, 70_000, LocalDate.of(2025, 8, 11))),
            synced = listOf(asset("23150000"))).execute(account.id)

        assertThat(cashFlows.saved).isEmpty()
    }

    @Test
    fun `다른 계좌의 편입 기록은 이 계좌의 초기 편입을 막지 않는다`() {
        val otherAccountFlow = CashFlow.create(
            userId = userId, accountId = UUID.randomUUID(), flowDate = LocalDate.of(2026, 7, 14),
            type = FlowType.DEPOSIT, amount = BigDecimal("3000000"), currency = "KRW",
            amountKrw = BigDecimal("3000000"), memo = "계좌 연동 초기 자산 편입(자동)",
        )
        val cashFlows = RecordingCashFlowRepository(otherAccountFlow)

        useCase(cashFlows, listOf(buy("삼성전자", 100, 70_000, LocalDate.of(2025, 8, 11))),
            synced = listOf(asset("23150000"))).execute(account.id)

        assertThat(cashFlows.saved.single().amountKrw).isEqualByComparingTo("7000000")
    }

    @Test
    fun `USD 계좌는 오늘이 아니라 체결일 환율로 환산한다`() {
        val tradedOn = LocalDate.of(2025, 8, 11)
        val cashFlows = RecordingCashFlowRepository()
        // 체결일 1100, 오늘 1300 — 오늘 환율을 쓰면 130만이 나온다
        val datedFx = DatedFxConverter(on = tradedOn, rate = BigDecimal("1100"), now = BigDecimal("1300"))

        useCase(
            cashFlows, listOf(usdTrade(quantity = 10, price = 100, on = tradedOn)),
            synced = listOf(asset("1000", accountId = usdAccount.id)),
            account = usdAccount, fx = datedFx,
        ).execute(usdAccount.id)

        val flow = cashFlows.saved.single()
        assertThat(flow.amountKrw).isEqualByComparingTo("1100000")
        assertThat(flow.flowDate).isEqualTo(tradedOn)
        assertThat(flow.currency).isEqualTo("USD")
        assertThat(flow.memo).doesNotContain("환율 추정치")
    }

    @Test
    fun `체결일 환율을 못 찾으면 메모에 추정치임을 남긴다`() {
        val tradedOn = LocalDate.of(2019, 3, 4)   // 백필 범위 밖
        val cashFlows = RecordingCashFlowRepository()
        val datedFx = DatedFxConverter(on = null, rate = BigDecimal.ZERO, now = BigDecimal("1300"))

        useCase(
            cashFlows, listOf(usdTrade(quantity = 10, price = 100, on = tradedOn)),
            synced = listOf(asset("1000", accountId = usdAccount.id)),
            account = usdAccount, fx = datedFx,
        ).execute(usdAccount.id)

        val flow = cashFlows.saved.single()
        assertThat(flow.amountKrw).isEqualByComparingTo("1300000")
        assertThat(flow.flowDate).isEqualTo(tradedOn)
        assertThat(flow.currency).isEqualTo("USD")
        assertThat(flow.memo).contains("환율 추정치")
    }

    // ── helpers ──────────────────────────────────────────────────

    private fun useCase(
        cashFlows: CashFlowRepository,
        trades: List<StockTrade>,
        synced: List<Asset>,
        account: Account = this.account,
        fx: FxConverter = this.fx,
    ) = SyncAccountUseCase(
        accountRepository = FixedAccountRepository(account),
        assetRepository = StatefulAssetRepository(),
        adapters = listOf(object : SyncAdapter {
            override val supportedProvider = account.provider
            override fun sync(account: Account): List<Asset> = synced
        }),
        snapshotService = mock(PerformanceSnapshotService::class.java),
        fx = fx,
        syncLogRepository = NoopSyncLogRepository(),
        reconMutex = AlwaysAcquiredReconMutex(),
        cashFlowRepository = cashFlows,
        stockTradeRepository = FakeStockTradeRepository(trades),
    )

    private fun buy(
        name: String, quantity: Int, price: Int, on: LocalDate,
        fee: BigDecimal = BigDecimal.ZERO, tax: BigDecimal = BigDecimal.ZERO,
    ) = trade(StockTradeType.BUY, name, quantity, price, on, fee, tax)

    private fun sell(
        name: String, quantity: Int, price: Int, on: LocalDate,
        fee: BigDecimal = BigDecimal.ZERO, tax: BigDecimal = BigDecimal.ZERO,
    ) = trade(StockTradeType.SELL, name, quantity, price, on, fee, tax)

    private fun dividend(name: String, amount: Int, on: LocalDate) = StockTrade.create(
        accountId = account.id, userId = userId, tradeType = StockTradeType.DIVIDEND,
        stockName = name, symbol = "005930",
        quantity = BigDecimal.ZERO, price = BigDecimal.ZERO, totalAmount = BigDecimal(amount),
        fee = BigDecimal.ZERO, tax = BigDecimal.ZERO, tradedAt = on, memo = null,
    )

    private fun trade(
        type: StockTradeType, name: String, quantity: Int, price: Int,
        on: LocalDate, fee: BigDecimal, tax: BigDecimal,
    ) = StockTrade.create(
        accountId = account.id, userId = userId, tradeType = type,
        stockName = name, symbol = "005930",
        quantity = BigDecimal(quantity), price = BigDecimal(price),
        totalAmount = BigDecimal(quantity) * BigDecimal(price),
        fee = fee, tax = tax, tradedAt = on, memo = null,
    )

    private fun usdTrade(quantity: Int, price: Int, on: LocalDate) =
        StockTrade.create(
            accountId = usdAccount.id, userId = userId, tradeType = StockTradeType.BUY,
            stockName = "AAPL", symbol = "AAPL",
            quantity = BigDecimal(quantity), price = BigDecimal(price),
            totalAmount = BigDecimal(quantity) * BigDecimal(price),
            fee = BigDecimal.ZERO, tax = BigDecimal.ZERO, tradedAt = on, memo = null,
        )

    private fun asset(value: String, accountId: UUID = account.id): Asset = Asset.create(
        userId = userId, accountId = accountId,
        category = AssetCategory.FINANCIAL, type = AssetType.STOCK, sourceType = AssetSourceType.STOCK_API,
        name = "삼성전자", symbol = "005930", quantity = BigDecimal.ONE,
        purchasePrice = BigDecimal(value), currentValue = BigDecimal(value),
        currency = "KRW", valuationMethod = ValuationMethod.USER_INPUT,
    )

    // ── fakes ────────────────────────────────────────────────────

    /**
     * on 날짜만 과거 환율을 가진 fake. on이 null이면 언제나 미보유(=추정치 폴백).
     *
     * 실제 어댑터보다 느슨하다 — 어댑터는 "그 날짜 이하 가장 최근"으로 해소하므로
     * 토요일 거래도 직전 영업일 환율로 `estimated=false`(단 `rateDate != date`)를 돌려준다.
     * 즉 주말 거래에 추정치 메모가 붙지 않는다. 이 유스케이스는 `rateDate`로 분기하지 않아
     * 결과가 같으므로 이 fake는 hit/miss만 구분한다.
     * 직전 영업일 해소 자체는 `UnifiedAssetFxConverterAdapterTest`에서 검증한다.
     */
    private class DatedFxConverter(
        private val on: LocalDate?,
        private val rate: BigDecimal,
        private val now: BigDecimal,
    ) : FxConverter {
        override fun toKrw(amount: BigDecimal, currency: String): BigDecimal =
            if (currency.uppercase() == "KRW") amount else amount.multiply(now)

        override fun toKrwOn(amount: BigDecimal, currency: String, date: LocalDate) = when {
            currency.uppercase() == "KRW" -> KrwConversion(amount, null, false)
            date == on -> KrwConversion(amount.multiply(rate), date, false)
            else -> KrwConversion(amount.multiply(now), null, true)
        }
    }

    private class RecordingCashFlowRepository(vararg existing: CashFlow) : CashFlowRepository {
        private val preExisting = existing.toList()
        val saved = mutableListOf<CashFlow>()
        override fun save(cashFlow: CashFlow): CashFlow { saved.add(cashFlow); return cashFlow }
        override fun findById(id: UUID): CashFlow? = null
        override fun findByUserIdAndPeriod(userId: UUID, from: LocalDate, to: LocalDate) = preExisting
        override fun findByUserId(userId: UUID) = preExisting
        override fun delete(id: UUID) = Unit
        override fun deleteByAccountId(accountId: UUID) = Unit
    }

    private class StatefulAssetRepository : AssetRepository {
        private val stored = mutableListOf<Asset>()
        override fun save(asset: Asset): Asset = asset
        override fun saveAll(assets: List<Asset>): List<Asset> { stored.addAll(assets); return assets }
        override fun findById(id: UUID): Asset? = null
        override fun findByUserId(userId: UUID): List<Asset> = stored
        override fun findByAccountId(accountId: UUID): List<Asset> = stored
        override fun deleteByAccountId(accountId: UUID) { stored.clear() }
        override fun delete(id: UUID) = Unit
    }

    private class FixedAccountRepository(private val account: Account) : AccountRepository {
        override fun save(account: Account): Account = account
        override fun findById(id: UUID): Account? = account
        override fun findByUserId(userId: UUID): List<Account> = listOf(account)
        override fun findByProviders(providers: Collection<AccountProvider>): List<Account> = emptyList()
        override fun delete(id: UUID) = Unit
        override fun updateStatus(id: UUID, status: AccountStatus) = Unit
    }

    private class NoopSyncLogRepository : SyncLogRepository {
        override fun save(log: SyncLog): SyncLog = log
        override fun findByAccountId(accountId: UUID, limit: Int): List<SyncLog> = emptyList()
        override fun findLatestByUserId(userId: UUID): Map<UUID, SyncLog> = emptyMap()
        override fun deleteByAccountId(accountId: UUID) = Unit
    }

    private class AlwaysAcquiredReconMutex : ReconMutex {
        override fun tryAcquire(userId: UUID): String? = "token"
        override fun release(userId: UUID, token: String) = Unit
    }
}
