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
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class OkxSyncAdapter(private val objectMapper: ObjectMapper) : SyncAdapter {
    private val log = LoggerFactory.getLogger(javaClass)
    override val supportedProvider = AccountProvider.OKX
    private val BASE = "https://www.okx.com"

    // OKX는 Passphrase가 필요 → account.chain 필드에 저장
    override fun sync(account: Account): List<Asset> {
        if (account.apiKey.isNullOrBlank() || account.apiSecret.isNullOrBlank()) return emptyList()
        val passphrase = account.chain ?: ""
        return try {
            val balances = fetchBalances(account, passphrase)
            val prices   = fetchPrices()
            balances.mapNotNull { (currency, total) ->
                val usdPrice = when (currency) {
                    "USDT", "USDC", "BUSD" -> BigDecimal.ONE
                    else -> prices["${currency}-USDT"] ?: return@mapNotNull null
                }
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
                    currentValue    = total.multiply(usdPrice),
                    currency        = "USD",
                    valuationMethod = ValuationMethod.MARKET_PRICE,
                )
            }
        } catch (e: Exception) {
            log.error("OKX sync failed: ${e.message}", e)
            emptyList()
        }
    }

    override fun testConnection(account: Account): ConnectionTestResult {
        if (account.apiKey.isNullOrBlank() || account.apiSecret.isNullOrBlank())
            return ConnectionTestResult(false, "API Key와 Secret을 입력하세요.")
        val passphrase = account.chain ?: ""
        return try {
            val path      = "/api/v5/account/config"
            val timestamp = Instant.now().toString()
            val signature = Base64.getEncoder().encodeToString(hmacSha256(account.apiSecret!!, timestamp + "GET" + path))
            val json = WebClient.builder().baseUrl(BASE)
                .defaultHeader("OK-ACCESS-KEY", account.apiKey)
                .defaultHeader("OK-ACCESS-SIGN", signature)
                .defaultHeader("OK-ACCESS-TIMESTAMP", timestamp)
                .defaultHeader("OK-ACCESS-PASSPHRASE", passphrase)
                .build()
                .get().uri(path)
                .retrieve().bodyToMono(String::class.java).block() ?: throw RuntimeException("응답 없음")
            val root = objectMapper.readTree(json)
            val code = root["code"]?.asText()
            if (code != "0") {
                val msg = root["msg"]?.asText() ?: "인증 실패"
                throw RuntimeException(msg)
            }
            ConnectionTestResult(true, "연결 성공! OKX 계정 확인 완료")
        } catch (e: Exception) {
            ConnectionTestResult(false, e.message ?: "연결 실패")
        }
    }

    private fun fetchBalances(account: Account, passphrase: String): List<Pair<String, BigDecimal>> {
        val path      = "/api/v5/asset/balances"
        val timestamp = Instant.now().toString()
        val signInput = timestamp + "GET" + path
        val signature = Base64.getEncoder().encodeToString(
            hmacSha256(account.apiSecret!!, signInput)
        )

        val client = WebClient.builder().baseUrl(BASE)
            .defaultHeader("OK-ACCESS-KEY", account.apiKey)
            .defaultHeader("OK-ACCESS-SIGN", signature)
            .defaultHeader("OK-ACCESS-TIMESTAMP", timestamp)
            .defaultHeader("OK-ACCESS-PASSPHRASE", passphrase)
            .build()

        val json = client.get().uri(path)
            .retrieve().bodyToMono(String::class.java).block() ?: return emptyList()

        val root = objectMapper.readTree(json)
        if (root["code"]?.asText() != "0") return emptyList()

        val data = root["data"] ?: return emptyList()
        return data.mapNotNull { node ->
            val currency = node["ccy"]?.asText() ?: return@mapNotNull null
            val total    = BigDecimal(node["bal"]?.asText() ?: "0")
            if (total <= BigDecimal.ZERO) null else currency to total
        }
    }

    private fun fetchPrices(): Map<String, BigDecimal> {
        return try {
            val json = WebClient.create(BASE).get()
                .uri("/api/v5/market/tickers?instType=SPOT")
                .retrieve().bodyToMono(String::class.java).block() ?: return emptyMap()
            val data = objectMapper.readTree(json)["data"] ?: return emptyMap()
            data.mapNotNull { node ->
                val instId = node["instId"]?.asText() ?: return@mapNotNull null
                val price  = node["last"]?.asText()?.toBigDecimalOrNull() ?: return@mapNotNull null
                instId to price
            }.toMap()
        } catch (e: Exception) { emptyMap() }
    }

    private fun hmacSha256(secret: String, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(data.toByteArray())
    }
}
