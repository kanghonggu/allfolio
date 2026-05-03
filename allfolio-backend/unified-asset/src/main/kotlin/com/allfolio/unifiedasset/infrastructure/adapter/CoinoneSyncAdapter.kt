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
import java.math.BigDecimal
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class CoinoneSyncAdapter(private val objectMapper: ObjectMapper) : SyncAdapter {
    private val log = LoggerFactory.getLogger(javaClass)
    override val supportedProvider = AccountProvider.COINONE
    private val BASE = "https://api.coinone.co.kr"

    override fun sync(account: Account): List<Asset> {
        if (account.apiKey.isNullOrBlank() || account.apiSecret.isNullOrBlank()) return emptyList()
        return try {
            val balances = fetchBalances(account)
            val prices   = fetchPrices()
            balances.mapNotNull { (currency, total) ->
                val currentValue = if (currency == "KRW") total
                else total.multiply(prices[currency.lowercase()] ?: return@mapNotNull null)
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
            log.error("Coinone sync failed: ${e.message}", e)
            emptyList()
        }
    }

    override fun testConnection(account: Account): ConnectionTestResult {
        if (account.apiKey.isNullOrBlank() || account.apiSecret.isNullOrBlank())
            return ConnectionTestResult(false, "API Key와 Secret을 입력하세요.")
        return try {
            val nonce   = System.currentTimeMillis()
            val payload = objectMapper.writeValueAsString(mapOf("access_token" to account.apiKey, "nonce" to nonce))
            val encodedPayload = java.util.Base64.getEncoder().encodeToString(payload.toByteArray())
            val signature = hmacSha512(account.apiSecret!!.uppercase(), encodedPayload)
                .joinToString("") { "%02x".format(it) }
            val json = WebClient.create(BASE).post().uri("/v2/account/user_info/")
                .header("X-COINONE-PAYLOAD", encodedPayload)
                .header("X-COINONE-SIGNATURE", signature)
                .header("Content-Type", "application/json")
                .retrieve().bodyToMono(String::class.java).block() ?: throw RuntimeException("응답 없음")
            val root = objectMapper.readTree(json)
            val result = root["result"]?.asText()
            if (result != "success") {
                val errCode = root["error_code"]?.asText() ?: "unknown"
                throw RuntimeException("인증 실패 (error_code: $errCode)")
            }
            ConnectionTestResult(true, "연결 성공! 코인원 계정 확인 완료")
        } catch (e: Exception) {
            ConnectionTestResult(false, e.message ?: "연결 실패")
        }
    }

    private fun fetchBalances(account: Account): List<Pair<String, BigDecimal>> {
        val nonce   = System.currentTimeMillis()
        val payload = objectMapper.writeValueAsString(mapOf(
            "access_token" to account.apiKey,
            "nonce"        to nonce,
        ))
        val encodedPayload = Base64.getEncoder().encodeToString(payload.toByteArray())
        val signature = hmacSha512(account.apiSecret!!.uppercase(), encodedPayload)
            .joinToString("") { "%02x".format(it) }

        val client = WebClient.create(BASE)
        val json = client.post().uri("/v2/account/balance/")
            .header("X-COINONE-PAYLOAD", encodedPayload)
            .header("X-COINONE-SIGNATURE", signature)
            .header("Content-Type", "application/json")
            .retrieve().bodyToMono(String::class.java).block() ?: return emptyList()

        val root = objectMapper.readTree(json)
        if (root["result"]?.asText() != "success") return emptyList()

        return root.fields().asSequence()
            .filter { it.key !in setOf("result", "error_code", "timestamp", "server_time") }
            .mapNotNull { (currency, node) ->
                val avail  = node["avail"]?.asText()?.toBigDecimalOrNull() ?: return@mapNotNull null
                val balance = node["balance"]?.asText()?.toBigDecimalOrNull() ?: BigDecimal.ZERO
                val total   = avail + balance
                if (total <= BigDecimal.ZERO) null else currency.uppercase() to total
            }.toList()
    }

    private fun fetchPrices(): Map<String, BigDecimal> {
        return try {
            val client = WebClient.create(BASE)
            val json = client.get().uri("/public/v2/ticker/?currency=all")
                .retrieve().bodyToMono(String::class.java).block() ?: return emptyMap()
            val tickers = objectMapper.readTree(json)["tickers"] ?: return emptyMap()
            tickers.fields().asSequence().mapNotNull { (currency, node) ->
                val price = node["last"]?.asText()?.toBigDecimalOrNull() ?: return@mapNotNull null
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
