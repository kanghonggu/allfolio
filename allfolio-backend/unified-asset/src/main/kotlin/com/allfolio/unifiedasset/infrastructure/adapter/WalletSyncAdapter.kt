package com.allfolio.unifiedasset.infrastructure.adapter

import com.allfolio.unifiedasset.application.port.SyncAdapter
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.asset.*
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal
import java.math.BigInteger

@Component
class WalletSyncAdapter(
    private val objectMapper: ObjectMapper,
    @Value("\${unified-asset.moralis.api-key:}") private val moralisApiKey: String,
) : SyncAdapter {
    private val log = LoggerFactory.getLogger(javaClass)
    override val supportedProvider = AccountProvider.WALLET

    override fun sync(account: Account): List<Asset> {
        val address = account.walletAddress ?: return emptyList()
        val chain   = account.chain ?: "ETH"
        if (moralisApiKey.isBlank()) {
            log.warn("Moralis API key not configured — returning empty wallet")
            return emptyList()
        }
        return try {
            fetchTokenBalances(account, address, chain)
        } catch (e: Exception) {
            log.error("Wallet sync failed for ${address}: ${e.message}")
            emptyList()
        }
    }

    private fun fetchTokenBalances(account: Account, address: String, chain: String): List<Asset> {
        val chainParam = when (chain.uppercase()) {
            "ETH", "ETHEREUM" -> "eth"
            "BSC"             -> "bsc"
            "POLYGON"         -> "polygon"
            else              -> chain.lowercase()
        }

        val client = WebClient.builder()
            .baseUrl("https://deep-index.moralis.io")
            .defaultHeader("X-API-Key", moralisApiKey)
            .build()

        val json = client.get()
            .uri("/api/v2.2/$address/erc20?chain=$chainParam")
            .retrieve()
            .bodyToMono(String::class.java)
            .block() ?: return emptyList()

        val root = objectMapper.readTree(json)
        val tokens = root["result"] ?: return emptyList()

        return tokens.mapNotNull { token -> toAsset(account, token) }
    }

    /**
     * Moralis 토큰 항목 → 자산. HTTP와 분리해 둔 순수 함수다.
     *
     * **여기만 `"USD"`다 — 거래소 어댑터 셋([UsdtQuotedValuation])과 의도적으로 다르다.**
     * 저 셋은 거래소 오더북의 USDT 호가로 평가하지만, 여기는 Moralis 가격 오라클이 주는
     * `usd_value`를 그대로 받는다. USDT 호가를 조회하지도, 스테이블코인을 1로 두지도 않는다.
     *
     * 라벨을 USDT로 맞추면 안 된다. AF-99가 스테이블코인만 거래소 시세로 남기는 근거는
     * "그 거래소에 실제로 USDT를 들고 있는 사용자에게는 거래소 시세가 실현 가능한 값"인데,
     * 자가수탁 지갑에는 그런 계정이 없다. 김치 프리미엄이 낀 호가를 거치지 않으므로
     * 공식 고시 환율로 환산되는 쪽이 맞다.
     * (근거: `docs/superpowers/specs/2026-08-12-hana-fx-collector-design.md` "USDT를 분리하는 이유")
     */
    internal fun toAsset(account: Account, token: JsonNode): Asset? =
        try {
            val symbol   = token["symbol"]?.asText()
            val decimals = token["decimals"]?.asInt() ?: 18
            val rawBal   = token["balance"]?.asText() ?: "0"
            val balance  = BigDecimal(BigInteger(rawBal)).movePointLeft(decimals).stripTrailingZeros()
            val usdValue = token["usd_value"]?.asText()?.let { BigDecimal(it) } ?: BigDecimal.ZERO

            if (symbol == null || balance <= BigDecimal.ZERO) null
            else Asset.create(
                userId          = account.userId,
                accountId       = account.id,
                category        = AssetCategory.FINANCIAL,
                type            = AssetType.CRYPTO,
                sourceType      = AssetSourceType.WALLET,
                name            = token["name"]?.asText() ?: symbol,
                symbol          = symbol,
                quantity        = balance,
                purchasePrice   = BigDecimal.ZERO,
                currentValue    = usdValue,
                currency        = "USD",
                valuationMethod = ValuationMethod.BALANCE,
            )
        } catch (e: Exception) { null }
}
