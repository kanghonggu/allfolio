package com.allfolio.unifiedasset.infrastructure.adapter

import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountType
import com.allfolio.unifiedasset.domain.asset.AssetSourceType
import com.allfolio.unifiedasset.domain.asset.ValuationMethod
import com.allfolio.unifiedasset.domain.common.Currencies
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * 코인 어댑터의 **평가 통화 라벨 계약**을 고정한다.
 *
 * 해외 거래소 셋(Binance·OKX·Bybit)은 USDT 호가로 평가하므로 라벨도 `"USDT"`여야 하고,
 * Moralis `usd_value`를 그대로 받는 지갑만 `"USD"`다. 이 비대칭은 실수가 아니라 결정이다 —
 * 근거는 각 어댑터 KDoc과 `docs/superpowers/specs/2026-08-12-hana-fx-collector-design.md`에 있다.
 *
 * 라벨이 틀리면 AF-99 이후 평가액이 조용히 틀어진다. 오류도 로그도 안 나므로 여기서 막는다.
 */
class CryptoSyncAdapterCurrencyTest {

    private val mapper = ObjectMapper()

    private fun account(provider: AccountProvider) = Account.create(
        userId      = UUID.randomUUID(),
        provider    = provider,
        accountType = if (provider == AccountProvider.WALLET) AccountType.WALLET else AccountType.EXCHANGE,
        accountName = "$provider 테스트",
        externalId  = null,
        currency    = "USD",
        apiKey      = "key",
        apiSecret   = "secret",
    )

    // ── Binance ──────────────────────────────────────────────────

    @Test
    fun `Binance는 USDT 호가로 평가하고 라벨도 USDT다`() {
        val assets = BinanceSyncAdapter(mapper).toAssets(
            account  = account(AccountProvider.BINANCE),
            balances = listOf("BTC" to BigDecimal("0.5")),
            prices   = mapOf("BTCUSDT" to BigDecimal("60000")),
        )

        val btc = assets.single()
        assertEquals("USDT", btc.currency)
        assertEquals(0, BigDecimal("30000.0").compareTo(btc.currentValue))
        assertEquals(AssetSourceType.EXCHANGE_API, btc.sourceType)
        assertEquals(ValuationMethod.MARKET_PRICE, btc.valuationMethod)
    }

    @Test
    fun `Binance 스테이블코인은 호가 조회 없이 액면가 1로 본다`() {
        val assets = BinanceSyncAdapter(mapper).toAssets(
            account  = account(AccountProvider.BINANCE),
            balances = listOf("USDT" to BigDecimal("100"), "USDC" to BigDecimal("50")),
            prices   = emptyMap(),
        )

        assertEquals(listOf("USDT", "USDT"), assets.map { it.currency })
        assertEquals(0, BigDecimal("100").compareTo(assets[0].currentValue))
        assertEquals(0, BigDecimal("50").compareTo(assets[1].currentValue))
    }

    @Test
    fun `Binance는 호가 없는 코인을 평가액 0으로 남긴다`() {
        val assets = BinanceSyncAdapter(mapper).toAssets(
            account  = account(AccountProvider.BINANCE),
            balances = listOf("NOTLISTED" to BigDecimal("10")),
            prices   = emptyMap(),
        )

        // OKX·Bybit는 같은 상황에서 건너뛴다. 셋이 다르다는 게 의도라는 걸 여기서 못 박는다.
        val orphan = assets.single()
        assertEquals("USDT", orphan.currency)
        assertEquals(0, BigDecimal.ZERO.compareTo(orphan.currentValue))
    }

    @Test
    fun `Binance 잔고 파싱은 free와 locked를 더하고 0인 잔고를 버린다`() {
        val json = mapper.readTree(
            """[
                 {"asset":"BTC","free":"0.4","locked":"0.1"},
                 {"asset":"ETH","free":"0","locked":"0"}
               ]""",
        )

        assertEquals(
            listOf("BTC" to BigDecimal("0.5")),
            BinanceSyncAdapter(mapper).parseBalances(json),
        )
    }

    // ── OKX ──────────────────────────────────────────────────────

    @Test
    fun `OKX는 하이픈 붙은 시세 키를 쓰고 라벨은 USDT다`() {
        val assets = OkxSyncAdapter(mapper).toAssets(
            account  = account(AccountProvider.OKX),
            balances = listOf("ETH" to BigDecimal("2")),
            prices   = mapOf("ETH-USDT" to BigDecimal("3000")),
        )

        val eth = assets.single()
        assertEquals("USDT", eth.currency)
        assertEquals(0, BigDecimal("6000").compareTo(eth.currentValue))
    }

    @Test
    fun `OKX는 호가 없는 코인을 건너뛴다`() {
        val assets = OkxSyncAdapter(mapper).toAssets(
            account  = account(AccountProvider.OKX),
            // Binance 형식 키는 OKX에서 안 맞아야 한다 — 키 형식이 어댑터마다 다르다는 계약
            balances = listOf("ETH" to BigDecimal("2")),
            prices   = mapOf("ETHUSDT" to BigDecimal("3000")),
        )

        assertTrue(assets.isEmpty())
    }

    // ── Bybit ────────────────────────────────────────────────────

    @Test
    fun `Bybit는 붙여 쓴 시세 키를 쓰고 라벨은 USDT다`() {
        val assets = BybitSyncAdapter(mapper).toAssets(
            account  = account(AccountProvider.BYBIT),
            balances = listOf("SOL" to BigDecimal("10")),
            prices   = mapOf("SOLUSDT" to BigDecimal("150")),
        )

        val sol = assets.single()
        assertEquals("USDT", sol.currency)
        assertEquals(0, BigDecimal("1500").compareTo(sol.currentValue))
    }

    @Test
    fun `Bybit는 호가 없는 코인을 건너뛴다`() {
        val assets = BybitSyncAdapter(mapper).toAssets(
            account  = account(AccountProvider.BYBIT),
            balances = listOf("SOL" to BigDecimal("10")),
            prices   = emptyMap(),
        )

        assertTrue(assets.isEmpty())
    }

    // ── Wallet (의도적 예외) ──────────────────────────────────────

    @Test
    fun `지갑은 Moralis usd_value를 쓰므로 라벨이 USD다`() {
        val token = mapper.readTree(
            """{"symbol":"USDT","name":"Tether USD","decimals":6,
                "balance":"1000000000","usd_value":"1000.25"}""",
        )

        val asset = WalletSyncAdapter(mapper, "key").toAsset(account(AccountProvider.WALLET), token)

        assertNotNull(asset)
        // USDT 토큰을 들고 있어도 USD다 — 거래소 호가가 아니라 가격 오라클 값이기 때문이다.
        // 거래소 셋과 다른 건 의도다. 통일하지 말 것 (KDoc 참조).
        assertEquals("USD", asset!!.currency)
        assertEquals(0, BigDecimal("1000.25").compareTo(asset.currentValue))
        assertEquals(0, BigDecimal("1000").compareTo(asset.quantity))
        assertEquals(AssetSourceType.WALLET, asset.sourceType)
        assertEquals(ValuationMethod.BALANCE, asset.valuationMethod)
    }

    @Test
    fun `지갑은 잔고가 0인 토큰을 버린다`() {
        val token = mapper.readTree("""{"symbol":"SHIB","decimals":18,"balance":"0"}""")
        assertNull(WalletSyncAdapter(mapper, "key").toAsset(account(AccountProvider.WALLET), token))
    }

    // ── 화이트리스트 ──────────────────────────────────────────────

    @Test
    fun `USDT는 지원 통화라 Asset 생성에서 걸러지지 않는다`() {
        // Asset.create가 Currencies.normalize를 태운다. USDT가 빠지면 동기화가 통째로 실패한다.
        assertTrue(UsdtQuotedValuation.CURRENCY in Currencies.SUPPORTED)
        assertEquals("USDT", Currencies.normalize(" usdt "))
    }
}
