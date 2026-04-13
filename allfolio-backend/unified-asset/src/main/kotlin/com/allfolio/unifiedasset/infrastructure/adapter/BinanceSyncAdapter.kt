package com.allfolio.unifiedasset.infrastructure.adapter

import com.allfolio.unifiedasset.application.port.SyncAdapter
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.asset.*
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.math.BigDecimal

@Component
class BinanceSyncAdapter(private val objectMapper: ObjectMapper) : SyncAdapter {
    private val log = LoggerFactory.getLogger(javaClass)
    override val supportedProvider = AccountProvider.BINANCE

    override fun sync(account: Account): List<Asset> {
        if (account.apiKey.isNullOrBlank() || account.apiSecret.isNullOrBlank()) {
            log.warn("Binance API key not set for account ${account.id}")
            return emptyList()
        }
        return try {
            fetchBalances(account)
        } catch (e: Exception) {
            log.error("Binance sync failed for account ${account.id}: ${e.message}")
            emptyList()
        }
    }

    private fun fetchBalances(account: Account): List<Asset> {
        val timestamp = System.currentTimeMillis()
        val queryString = "timestamp=$timestamp"
        val signature = hmacSha256(account.apiSecret!!, queryString)

        val client = WebClient.builder()
            .baseUrl("https://api.binance.com")
            .defaultHeader("X-MBX-APIKEY", account.apiKey)
            .build()

        val json = client.get()
            .uri("/api/v3/account?$queryString&signature=$signature")
            .retrieve()
            .bodyToMono(String::class.java)
            .block() ?: return emptyList()

        val root: JsonNode = objectMapper.readTree(json)
        val balances = root["balances"] ?: return emptyList()

        // 가격 조회 (심볼별 USDT 가격)
        val prices = fetchPrices()

        return balances.mapNotNull { node ->
            val asset  = node["asset"]?.asText() ?: return@mapNotNull null
            val free   = node["free"]?.asText()?.let { BigDecimal(it) } ?: BigDecimal.ZERO
            val locked = node["locked"]?.asText()?.let { BigDecimal(it) } ?: BigDecimal.ZERO
            val total  = free + locked
            if (total <= BigDecimal.ZERO) return@mapNotNull null

            val usdPrice = when (asset) {
                "USDT", "BUSD", "USDC" -> BigDecimal.ONE
                else -> prices["${asset}USDT"] ?: BigDecimal.ZERO
            }
            val currentValue = total.multiply(usdPrice)

            Asset.create(
                userId          = account.userId,
                accountId       = account.id,
                category        = AssetCategory.FINANCIAL,
                type            = AssetType.CRYPTO,
                sourceType      = AssetSourceType.EXCHANGE_API,
                name            = asset,
                symbol          = asset,
                quantity        = total,
                purchasePrice   = BigDecimal.ZERO, // 평균단가 별도 조회 필요
                currentValue    = currentValue,
                currency        = "USD",
                valuationMethod = ValuationMethod.MARKET_PRICE,
            )
        }
    }

    private fun fetchPrices(): Map<String, BigDecimal> {
        return try {
            val client = WebClient.create("https://api.binance.com")
            val json = client.get().uri("/api/v3/ticker/price")
                .retrieve().bodyToMono(String::class.java).block() ?: return emptyMap()
            val arr = objectMapper.readTree(json)
            arr.associate {
                it["symbol"].asText() to BigDecimal(it["price"].asText())
            }
        } catch (e: Exception) { emptyMap() }
    }

    private fun hmacSha256(secret: String, data: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
