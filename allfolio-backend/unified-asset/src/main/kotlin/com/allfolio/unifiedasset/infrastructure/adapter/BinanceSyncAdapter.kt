package com.allfolio.unifiedasset.infrastructure.adapter

import com.allfolio.unifiedasset.application.port.SyncAdapter
import com.allfolio.unifiedasset.application.usecase.ConnectionTestResult
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

        return toAssets(account, parseBalances(balances), prices)
    }

    /** free + locked 합계가 양수인 잔고만. */
    internal fun parseBalances(balances: JsonNode): List<Pair<String, BigDecimal>> =
        balances.mapNotNull { node ->
            val asset  = node["asset"]?.asText() ?: return@mapNotNull null
            val free   = node["free"]?.asText()?.let { BigDecimal(it) } ?: BigDecimal.ZERO
            val locked = node["locked"]?.asText()?.let { BigDecimal(it) } ?: BigDecimal.ZERO
            val total  = free + locked
            if (total <= BigDecimal.ZERO) null else asset to total
        }

    /**
     * 잔고 × USDT 호가 → 자산. HTTP와 분리해 둔 순수 함수다 —
     * 시세 키 형식(`BTCUSDT`)과 통화 라벨을 네트워크 없이 테스트로 고정한다.
     *
     * 평가액 단위는 USD가 아니라 **USDT**다. 왜 라벨이 그런지는 [UsdtQuotedValuation] KDoc.
     *
     * **미상장 코인은 평가액 0으로 남긴다** (OKX·Bybit는 건너뛴다). 보유 사실 자체는
     * 보여 주되 값을 지어내지 않는 쪽이고, 잔고 목록에서 코인이 통째로 사라지는 것보다 낫다.
     */
    internal fun toAssets(
        account: Account,
        balances: List<Pair<String, BigDecimal>>,
        prices: Map<String, BigDecimal>,
    ): List<Asset> = balances.map { (asset, total) ->
        val usdtPrice = when (asset) {
            in UsdtQuotedValuation.STABLECOINS -> BigDecimal.ONE
            else -> prices["${asset}USDT"] ?: BigDecimal.ZERO
        }
        UsdtQuotedValuation.asset(account, asset, total, usdtPrice)
    }

    override fun testConnection(account: Account): ConnectionTestResult {
        if (account.apiKey.isNullOrBlank() || account.apiSecret.isNullOrBlank())
            return ConnectionTestResult(false, "API Key와 Secret을 입력하세요.")
        return try {
            val timestamp   = System.currentTimeMillis()
            val queryString = "timestamp=$timestamp"
            val signature   = hmacSha256(account.apiSecret!!, queryString)
            val client = WebClient.builder().baseUrl("https://api.binance.com")
                .defaultHeader("X-MBX-APIKEY", account.apiKey).build()
            val json = client.get()
                .uri("/api/v3/account?$queryString&signature=$signature")
                .retrieve()
                .onStatus({ it.value() == 401 }) { throw RuntimeException("API Key 또는 Secret이 올바르지 않습니다.") }
                .onStatus({ it.value() == 403 }) { throw RuntimeException("API 권한이 부족합니다. 읽기 권한을 확인하세요.") }
                .onStatus({ it.is5xxServerError }) { throw RuntimeException("Binance 서버 오류") }
                .bodyToMono(String::class.java).block() ?: throw RuntimeException("응답 없음")
            val root = objectMapper.readTree(json)
            val code = root["code"]?.asInt()
            if (code != null && code < 0) throw RuntimeException(root["msg"]?.asText() ?: "인증 실패")
            val nonZero = root["balances"]?.count { node ->
                val free   = node["free"]?.asText()?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
                val locked = node["locked"]?.asText()?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
                (free + locked) > java.math.BigDecimal.ZERO
            } ?: 0
            ConnectionTestResult(true, "연결 성공! ${nonZero}개 코인 잔고 확인", nonZero)
        } catch (e: Exception) {
            ConnectionTestResult(false, e.message ?: "연결 실패")
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
