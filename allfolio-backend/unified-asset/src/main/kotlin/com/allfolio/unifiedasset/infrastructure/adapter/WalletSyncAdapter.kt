package com.allfolio.unifiedasset.infrastructure.adapter

import com.allfolio.unifiedasset.application.port.SyncAdapter
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.asset.*
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

        return tokens.mapNotNull { token ->
            try {
                val symbol   = token["symbol"]?.asText() ?: return@mapNotNull null
                val name     = token["name"]?.asText() ?: symbol
                val decimals = token["decimals"]?.asInt() ?: 18
                val rawBal   = token["balance"]?.asText() ?: "0"
                val balance  = BigDecimal(BigInteger(rawBal)).movePointLeft(decimals).stripTrailingZeros()
                val usdValue = token["usd_value"]?.asText()?.let { BigDecimal(it) } ?: BigDecimal.ZERO
                if (balance <= BigDecimal.ZERO) return@mapNotNull null

                Asset.create(
                    userId          = account.userId,
                    accountId       = account.id,
                    category        = AssetCategory.FINANCIAL,
                    type            = AssetType.CRYPTO,
                    sourceType      = AssetSourceType.WALLET,
                    name            = name,
                    symbol          = symbol,
                    quantity        = balance,
                    purchasePrice   = BigDecimal.ZERO,
                    currentValue    = usdValue,
                    currency        = "USD",
                    valuationMethod = ValuationMethod.BALANCE,
                )
            } catch (e: Exception) { null }
        }
    }
}
