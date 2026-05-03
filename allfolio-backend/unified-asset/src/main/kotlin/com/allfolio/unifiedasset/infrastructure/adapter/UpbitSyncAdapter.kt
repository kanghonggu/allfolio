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
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class UpbitSyncAdapter(private val objectMapper: ObjectMapper) : SyncAdapter {
    private val log = LoggerFactory.getLogger(javaClass)
    override val supportedProvider = AccountProvider.UPBIT
    private val BASE = "https://api.upbit.com"

    override fun sync(account: Account): List<Asset> {
        if (account.apiKey.isNullOrBlank() || account.apiSecret.isNullOrBlank()) return emptyList()
        return try {
            val balances = fetchBalances(account)
            val prices   = fetchPrices(balances.map { it.first }.filter { it != "KRW" })
            balances.mapNotNull { (currency, total) ->
                val currentValue = if (currency == "KRW") total
                else total.multiply(prices["KRW-$currency"] ?: return@mapNotNull null)
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
            log.error("Upbit sync failed: ${e.message}", e)
            emptyList()
        }
    }

    override fun testConnection(account: Account): ConnectionTestResult {
        if (account.apiKey.isNullOrBlank() || account.apiSecret.isNullOrBlank())
            return ConnectionTestResult(false, "API Key와 Secret을 입력하세요.")
        return try {
            val token = buildJwt(account.apiKey!!, account.apiSecret!!)
            val json = WebClient.create(BASE).get().uri("/v1/accounts")
                .header("Authorization", "Bearer $token")
                .retrieve()
                .onStatus({ it.value() == 401 }) { throw RuntimeException("Access Key 또는 Secret Key가 올바르지 않습니다.") }
                .onStatus({ it.is4xxClientError || it.is5xxServerError }) { throw RuntimeException("API 오류") }
                .bodyToMono(String::class.java).block() ?: throw RuntimeException("응답 없음")
            val arr = objectMapper.readTree(json)
            val count = arr.count { node ->
                val balance = node["balance"]?.asText()?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
                val locked  = node["locked"]?.asText()?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
                (balance + locked) > java.math.BigDecimal.ZERO
            }
            ConnectionTestResult(true, "연결 성공! ${count}개 코인 잔고 확인", count)
        } catch (e: Exception) {
            ConnectionTestResult(false, e.message ?: "연결 실패")
        }
    }

    private fun fetchBalances(account: Account): List<Pair<String, BigDecimal>> {
        val token = buildJwt(account.apiKey!!, account.apiSecret!!)
        val client = WebClient.create(BASE)
        val json = client.get().uri("/v1/accounts")
            .header("Authorization", "Bearer $token")
            .retrieve().bodyToMono(String::class.java).block() ?: return emptyList()
        val arr = objectMapper.readTree(json)
        return arr.mapNotNull { node ->
            val currency = node["currency"]?.asText() ?: return@mapNotNull null
            val balance  = BigDecimal(node["balance"]?.asText() ?: "0")
            val locked   = BigDecimal(node["locked"]?.asText() ?: "0")
            val total    = balance + locked
            if (total <= BigDecimal.ZERO) null else currency to total
        }
    }

    private fun fetchPrices(currencies: List<String>): Map<String, BigDecimal> {
        if (currencies.isEmpty()) return emptyMap()
        val markets = currencies.joinToString(",") { "KRW-$it" }
        return try {
            val client = WebClient.create(BASE)
            val json = client.get().uri("/v1/ticker?markets=$markets")
                .retrieve().bodyToMono(String::class.java).block() ?: return emptyMap()
            val arr = objectMapper.readTree(json)
            arr.associate { it["market"].asText() to BigDecimal(it["trade_price"].asText()) }
        } catch (e: Exception) { emptyMap() }
    }

    private fun buildJwt(accessKey: String, secretKey: String): String {
        val header  = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"access_key":"$accessKey","nonce":"${UUID.randomUUID()}"}""".toByteArray())
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretKey.toByteArray(), "HmacSHA256"))
        val sig = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal("$header.$payload".toByteArray()))
        return "$header.$payload.$sig"
    }
}
