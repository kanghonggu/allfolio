package com.allfolio.unifiedasset.infrastructure.adapter

import com.allfolio.unifiedasset.application.port.SyncAdapter
import com.allfolio.unifiedasset.application.usecase.ConnectionTestResult
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.asset.*
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.math.BigDecimal
import java.net.URLEncoder
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class BithumbSyncAdapter(private val objectMapper: ObjectMapper) : SyncAdapter {
    private val log = LoggerFactory.getLogger(javaClass)
    override val supportedProvider = AccountProvider.BITHUMB
    private val BASE = "https://api.bithumb.com"

    override fun sync(account: Account): List<Asset> {
        if (account.apiKey.isNullOrBlank() || account.apiSecret.isNullOrBlank()) return emptyList()
        return try {
            val balances = fetchBalances(account)
            val prices   = fetchPrices()
            balances.mapNotNull { (currency, total) ->
                val currentValue = if (currency == "KRW") total
                else total.multiply(prices[currency] ?: return@mapNotNull null)
                Asset.create(
                    userId          = account.userId,
                    accountId       = account.id,
                    category        = AssetCategory.FINANCIAL,
                    type            = AssetType.CRYPTO,
                    sourceType      = AssetSourceType.EXCHANGE_API,
                    name            = currency,
                    symbol          = currency,
                    quantity        = total,
                    purchasePrice   = BigDecimal.ZERO,
                    currentValue    = currentValue,
                    currency        = "KRW",
                    valuationMethod = ValuationMethod.MARKET_PRICE,
                )
            }
        } catch (e: Exception) {
            log.error("Bithumb sync failed: ${e.message}", e)
            emptyList()
        }
    }

    override fun testConnection(account: Account): ConnectionTestResult {
        if (account.apiKey.isNullOrBlank() || account.apiSecret.isNullOrBlank())
            return ConnectionTestResult(false, "API Key와 Secret을 입력하세요.")
        return try {
            val balances = fetchBalances(account)
            if (balances.isEmpty()) ConnectionTestResult(true, "연결 성공! (잔고 없음)", 0)
            else ConnectionTestResult(true, "연결 성공! ${balances.size}개 코인 잔고 확인", balances.size)
        } catch (e: Exception) {
            ConnectionTestResult(false, e.message ?: "연결 실패")
        }
    }

    private fun fetchBalances(account: Account): List<Pair<String, BigDecimal>> {
        val endpoint = "/info/balance"
        val nonce    = System.currentTimeMillis().toString()
        val params   = "currency=ALL"
        val encodedParams = URLEncoder.encode("endpoint=$endpoint&$params", "UTF-8")
        val signData  = "$endpoint\u0000$encodedParams\u0000$nonce"
        val signature = Base64.getEncoder().encodeToString(hmacSha512(account.apiSecret!!, signData))

        val client = WebClient.builder().baseUrl(BASE)
            .defaultHeader("Api-Key", account.apiKey)
            .defaultHeader("Api-Sign", signature)
            .defaultHeader("Api-Nonce", nonce)
            .defaultHeader("Content-Type", "application/x-www-form-urlencoded")
            .build()

        val json = client.post().uri(endpoint)
            .bodyValue("endpoint=${URLEncoder.encode(endpoint, "UTF-8")}&$params")
            .retrieve().bodyToMono(String::class.java).block() ?: return emptyList()

        val root = objectMapper.readTree(json)
        val data = root["data"] ?: return emptyList()
        return data.fields().asSequence()
            .filter { it.key.startsWith("available_") }
            .mapNotNull { (key, value) ->
                val currency = key.removePrefix("available_").uppercase()
                val amount   = value.asText().toBigDecimalOrNull() ?: return@mapNotNull null
                if (amount <= BigDecimal.ZERO) null else currency to amount
            }.toList()
    }

    private fun fetchPrices(): Map<String, BigDecimal> {
        return try {
            val client = WebClient.create(BASE)
            val json = client.get().uri("/public/ticker/ALL_KRW")
                .retrieve().bodyToMono(String::class.java).block() ?: return emptyMap()
            val data = objectMapper.readTree(json)["data"] ?: return emptyMap()
            data.fields().asSequence()
                .filter { it.key != "date" }
                .mapNotNull { (currency, node) ->
                    val price = node["closing_price"]?.asText()?.toBigDecimalOrNull() ?: return@mapNotNull null
                    currency to price
                }.toMap()
        } catch (e: Exception) { emptyMap() }
    }

    private fun hmacSha512(secret: String, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA512"))
        return mac.doFinal(data.toByteArray())
    }
}
