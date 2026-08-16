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
import com.allfolio.unifiedasset.domain.sync.SyncTrigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class SyncAccountUseCaseNavTest {

    /** KRW 1:1, USD → 1300원. */
    private val fx = object : FxConverter {
        override fun toKrw(amount: BigDecimal, currency: String): BigDecimal =
            if (currency.uppercase() == "KRW") amount else amount.multiply(BigDecimal("1300"))

        override fun rateOf(currency: String): BigDecimal =
            if (currency.uppercase() == "KRW") BigDecimal.ONE else BigDecimal("1300")
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
            reconMutex = NoopReconMutex(),
        cashFlowRepository = org.mockito.Mockito.mock(com.allfolio.unifiedasset.application.port.CashFlowRepository::class.java),
        stockTradeRepository = FakeStockTradeRepository(),
        )

        service.execute(account.id)

        @Suppress("UNCHECKED_CAST")
        val navCaptor = ArgumentCaptor.forClass(Map::class.java) as ArgumentCaptor<Map<String, BigDecimal>>
        // 날짜는 이 테스트의 관심사가 아니다(PerformanceSnapshotDateTest가 못 박는다) — 매처만 채운다
        verify(snapshot).record(eqUuid(userId), captureMap(navCaptor), anyDate())
        val captured = navCaptor.value

        // 총액이 아니라 통화별 원통화 합계가 넘어가야 한다 (AF-106) — 여기서 접어 넘기면
        // record()가 nav_currency_daily에 쓸 내역이 사라진다
        assertEquals(setOf("KRW", "USD"), captured.keys) { "통화별로 갈라지지 않았다: $captured" }
        assertEquals(0, BigDecimal("1000000").compareTo(captured.getValue("KRW")))
        assertEquals(0, BigDecimal("1000").compareTo(captured.getValue("USD")))

        // 1,000,000 + 1,000 * 1,300 = 2,300,000 (raw sum would be a meaningless 1,001,000)
        val navKrw = captured.entries.fold(BigDecimal.ZERO) { acc, (currency, value) ->
            acc + fx.toKrw(value, currency)
        }
        assertEquals(0, BigDecimal("2300000").compareTo(navKrw)) {
            "expected KRW-converted NAV 2,300,000 but was $navKrw (from $captured)"
        }
    }

    /**
     * 마감 워크플로우 S010(`DailyAccountSyncer.syncAll`)이 이 경로를 그대로 쓴다. 그때 스냅샷까지
     * 쓰면 날짜가 **워크플로우의 업무일자가 아니라 실행 시각의 KST 오늘**로 적힌다 — S030이
     * `ctx.ymd.minusDays(1)`로 D−1을 쓰는 동안 같은 실행이 D 행을 하나 더 만든다.
     *
     * 2026-08-16 운영 로그에 12초 간격으로 두 날짜가 그대로 찍혔다:
     * ```
     * 16:05:58 userId=3e055c70… date=2026-08-16 nav=37484059.00   ← S010 (이 경로)
     * 16:06:05 userId=3e055c70… date=2026-08-15 nav=37484060.00   ← S030
     * ```
     * D 행에 앉는 값은 00:05 KST에 읽은 것이라 실질적으로 D−1 종가인데 D로 라벨된다. 다음 밤
     * S030이 같은 UPSERT 키로 덮어 자가 치유되지만, 그 말은 **가장 최근 행은 항상 틀린 행**이라는
     * 뜻이고 `GetReturnsAnalysisUseCase`가 `[from,to]`의 `to` 끝점으로 잡는 게 정확히 그 행이다.
     *
     * 그래서 마감 중 기록자는 S030 하나여야 한다. `SCHEDULED`를 쓰는 호출자는
     * `DailyAccountSyncer` 뿐이라 사용자 경로(MANUAL·AUTO)는 영향을 받지 않는다 —
     * 그쪽이 여전히 기록한다는 것은 위 `records NAV converted to KRW…`가 지킨다.
     */
    @Test
    fun `SCHEDULED 동기화는 스냅샷을 쓰지 않는다 — 마감의 기록자는 S030 하나여야 한다`() {
        val userId = UUID.randomUUID()
        val account = Account.create(
            userId = userId,
            provider = AccountProvider.BINANCE,
            accountType = AccountType.EXCHANGE,
            accountName = "binance",
        )
        val assets = listOf(asset(userId, account.id, BigDecimal("1000000"), "KRW"))

        val snapshot = mock(PerformanceSnapshotService::class.java)
        val service = SyncAccountUseCase(
            accountRepository = FixedAccountRepository(account),
            assetRepository = FixedAssetRepository(assets),
            adapters = listOf(EmptySyncAdapter(account.provider)),
            snapshotService = snapshot,
            fx = fx,
            syncLogRepository = NoopSyncLogRepository(),
            reconMutex = NoopReconMutex(),
            cashFlowRepository = org.mockito.Mockito.mock(com.allfolio.unifiedasset.application.port.CashFlowRepository::class.java),
            stockTradeRepository = FakeStockTradeRepository(),
        )

        service.execute(account.id, SyncTrigger.SCHEDULED)

        verify(snapshot, never()).record(anyUuid(), anyMap(), anyDate())
    }

    // Kotlin non-null 파라미터에 Mockito matcher를 쓰기 위한 null-safe 래퍼.
    private fun eqUuid(v: UUID): UUID = eq(v) ?: v
    private fun captureMap(c: ArgumentCaptor<Map<String, BigDecimal>>): Map<String, BigDecimal> =
        c.capture() ?: emptyMap()
    private fun anyDate(): LocalDate = any(LocalDate::class.java) ?: LocalDate.EPOCH
    private fun anyUuid(): UUID = any(UUID::class.java) ?: UUID.randomUUID()
    @Suppress("UNCHECKED_CAST")
    private fun anyMap(): Map<String, BigDecimal> =
        any(Map::class.java) as Map<String, BigDecimal>? ?: emptyMap()

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

    private class NoopReconMutex : com.allfolio.unifiedasset.application.port.ReconMutex {
        override fun tryAcquire(userId: UUID): String? = "token"
        override fun release(userId: UUID, token: String) = Unit
    }
}
