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
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class BybitSyncAdapter(private val objectMapper: ObjectMapper) : SyncAdapter {
    private val log = LoggerFactory.getLogger(javaClass)
    override val supportedProvider = AccountProvider.BYBIT
    private val BASE = "https://api.bybit.com"
    private val RECV_WINDOW = "5000"

    override fun sync(account: Account): List<Asset> {
        if (account.apiKey.isNullOrBlank() || account.apiSecret.isNullOrBlank()) return emptyList()
        return try {
            toAssets(account, fetchBalances(account), fetchPrices())
        } catch (e: Exception) {
            log.error("Bybit sync failed: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * 잔고 × USDT 호가 → 자산. HTTP와 분리해 둔 순수 함수다 —
     * 시세 키 형식(`BTCUSDT`)과 통화 라벨을 네트워크 없이 테스트로 고정한다.
     *
     * 평가액 단위는 USD가 아니라 **USDT**다. 왜 라벨이 그런지는 [UsdtQuotedValuation] KDoc.
     *
     * **호가가 없는 코인은 건너뛴다** (Binance는 평가액 0으로 남긴다).
     */
    internal fun toAssets(
        account: Account,
        balances: List<Pair<String, BigDecimal>>,
        prices: Map<String, BigDecimal>,
    ): List<Asset> = balances.mapNotNull { (coin, total) ->
        val usdtPrice = when (coin) {
            in UsdtQuotedValuation.STABLECOINS -> BigDecimal.ONE
            else -> prices["${coin}USDT"] ?: return@mapNotNull null
        }
        UsdtQuotedValuation.asset(account, coin, total, usdtPrice)
    }

    override fun testConnection(account: Account): ConnectionTestResult {
        if (account.apiKey.isNullOrBlank() || account.apiSecret.isNullOrBlank())
            return ConnectionTestResult(false, "API Key와 Secret을 입력하세요.")
        return try {
            val timestamp   = System.currentTimeMillis().toString()
            val queryString = "accountType=SPOT"
            val signInput   = timestamp + account.apiKey + RECV_WINDOW + queryString
            val signature   = hmacSha256(account.apiSecret!!, signInput)
            val json = WebClient.builder().baseUrl(BASE)
                .defaultHeader("X-BAPI-API-KEY", account.apiKey)
                .defaultHeader("X-BAPI-TIMESTAMP", timestamp)
                .defaultHeader("X-BAPI-SIGN", signature)
                .defaultHeader("X-BAPI-RECV-WINDOW", RECV_WINDOW)
                .build()
                .get().uri("/v5/account/wallet-balance?$queryString")
                .retrieve().bodyToMono(String::class.java).block() ?: throw RuntimeException("응답 없음")
            val root    = objectMapper.readTree(json)
            val retCode = root["retCode"]?.asInt() ?: -1
            if (retCode != 0) {
                val msg = root["retMsg"]?.asText() ?: "인증 실패"
                throw RuntimeException(msg)
            }
            val coins = root["result"]?.get("list")?.get(0)?.get("coin")
            val count = coins?.count { node ->
                (node["walletBalance"]?.asText()?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO) > java.math.BigDecimal.ZERO
            } ?: 0
            ConnectionTestResult(true, "연결 성공! ${count}개 코인 잔고 확인", count)
        } catch (e: Exception) {
            ConnectionTestResult(false, e.message ?: "연결 실패")
        }
    }

    private fun fetchBalances(account: Account): List<Pair<String, BigDecimal>> {
        val timestamp   = System.currentTimeMillis().toString()
        val queryString = "accountType=SPOT"
        val signInput   = timestamp + account.apiKey + RECV_WINDOW + queryString
        val signature   = hmacSha256(account.apiSecret!!, signInput)

        val client = WebClient.builder().baseUrl(BASE)
            .defaultHeader("X-BAPI-API-KEY", account.apiKey)
            .defaultHeader("X-BAPI-TIMESTAMP", timestamp)
            .defaultHeader("X-BAPI-SIGN", signature)
            .defaultHeader("X-BAPI-RECV-WINDOW", RECV_WINDOW)
            .build()

        val json = client.get()
            .uri("/v5/account/wallet-balance?$queryString")
            .retrieve().bodyToMono(String::class.java).block() ?: return emptyList()

        val root = objectMapper.readTree(json)
        if (root["retCode"]?.asInt() != 0) return emptyList()

        val coins = root["result"]?.get("list")?.get(0)?.get("coin") ?: return emptyList()
        return coins.mapNotNull { node ->
            val coin  = node["coin"]?.asText() ?: return@mapNotNull null
            val total = BigDecimal(node["walletBalance"]?.asText() ?: "0")
            if (total <= BigDecimal.ZERO) null else coin to total
        }
    }

    private fun fetchPrices(): Map<String, BigDecimal> {
        return try {
            val json = WebClient.create(BASE).get()
                .uri("/v5/market/tickers?category=spot")
                .retrieve().bodyToMono(String::class.java).block() ?: return emptyMap()
            val list = objectMapper.readTree(json)["result"]?.get("list") ?: return emptyMap()
            list.mapNotNull { node ->
                val symbol = node["symbol"]?.asText() ?: return@mapNotNull null
                val price  = node["lastPrice"]?.asText()?.toBigDecimalOrNull() ?: return@mapNotNull null
                symbol to price
            }.toMap()
        } catch (e: Exception) { emptyMap() }
    }

    private fun hmacSha256(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(data.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
