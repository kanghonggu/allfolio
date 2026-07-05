package com.allfolio.broker.kiwoom

import com.allfolio.broker.BrokerAuthEntity
import com.allfolio.broker.BrokerAuthRepository
import com.allfolio.broker.BrokerType
import com.allfolio.common.crypto.SENSITIVE_DATA_RECONNECTION_REQUIRED_MESSAGE
import com.allfolio.common.crypto.requiresSensitiveDataReconnection
import com.allfolio.metrics.BrokerMetrics
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 키움증권 Open API 클라이언트
 *
 * 인증: OAuth2 Authorization Code
 *   1. /api/broker/kiwoom/authorize → 인가 URL 리다이렉트
 *   2. 콜백 수신 → POST /oauth2/token (code → accessToken)
 *   3. API 호출 시 Authorization: Bearer {accessToken}
 *
 * Token 계층: L1 Local(30s) → L2 Redis → L3 DB → Refresh
 */
@Component
class KiwoomApiClient(
    private val kiwoomProperties: KiwoomProperties,
    private val brokerAuthRepository: BrokerAuthRepository,
    private val redisTemplate: RedisTemplate<String, Any>,
    private val metrics: BrokerMetrics,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val webClient = WebClient.builder()
        .baseUrl(kiwoomProperties.baseUrl)
        .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .build()

    private val localCache = ConcurrentHashMap<String, Pair<String, Long>>()

    // ── OAuth ─────────────────────────────────────────────────────────────────

    fun authorizeUrl(): String =
        "${kiwoomProperties.baseUrl}/oauth2/authorize" +
            "?response_type=code" +
            "&client_id=${kiwoomProperties.appKey}" +
            "&redirect_uri=${kiwoomProperties.redirectUri}" +
            "&scope=openapi"

    fun exchangeCode(code: String): KiwoomTokenResponse {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type",    "authorization_code")
            add("code",          code)
            add("redirect_uri",  kiwoomProperties.redirectUri)
            add("client_id",     kiwoomProperties.appKey)
            add("client_secret", kiwoomProperties.appSecret)
        }
        return webClient.post()
            .uri("/oauth2/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue(form)
            .retrieve()
            .bodyToMono<KiwoomTokenResponse>()
            .block(Duration.ofSeconds(10))
            ?: throw KiwoomApiException("Kiwoom token exchange failed")
    }

    fun refreshToken(refreshToken: String): KiwoomTokenResponse {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type",    "refresh_token")
            add("refresh_token", refreshToken)
            add("client_id",     kiwoomProperties.appKey)
            add("client_secret", kiwoomProperties.appSecret)
        }
        return webClient.post()
            .uri("/oauth2/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue(form)
            .retrieve()
            .bodyToMono<KiwoomTokenResponse>()
            .block(Duration.ofSeconds(10))
            ?: throw KiwoomApiException("Kiwoom token refresh failed")
    }

    fun resolveAccessToken(userId: UUID): String {
        val cacheKey = "broker:token:$userId:${BrokerType.KIWOOM.name}"

        localCache[cacheKey]?.let { (token, expiry) ->
            if (System.currentTimeMillis() < expiry) return token
            localCache.remove(cacheKey)
        }

        runCatching { redisTemplate.opsForValue().get(cacheKey) as? String }.getOrNull()
            ?.let { token ->
                localCache[cacheKey] = token to (System.currentTimeMillis() + LOCAL_CACHE_TTL_MS)
                return token
            }

        val auth = findAuth(userId)
            ?: throw KiwoomApiException("No Kiwoom auth for user=$userId. OAuth2 consent required.")

        if (!auth.isAccessTokenExpired()) {
            cacheToken(cacheKey, auth.accessToken, auth.accessTokenExpiresAt)
            return auth.accessToken
        }

        val rt = auth.refreshToken ?: throw KiwoomApiException("No refresh token for user=$userId")
        if (auth.isRefreshTokenExpired()) throw KiwoomApiException("Refresh token expired for user=$userId")

        log.info("[Kiwoom] refreshing token for user={}", userId)
        metrics.tokenRefreshed("KIWOOM")
        val newToken  = refreshToken(rt)
        val expiresAt = LocalDateTime.now().plusSeconds(newToken.expiresIn)

        auth.accessToken          = newToken.accessToken
        auth.accessTokenExpiresAt = expiresAt
        auth.updatedAt            = LocalDateTime.now()
        brokerAuthRepository.save(auth)
        cacheToken(cacheKey, newToken.accessToken, expiresAt)
        return newToken.accessToken
    }

    fun saveAuth(userId: UUID, tokenResponse: KiwoomTokenResponse) {
        val expiresAt = LocalDateTime.now().plusSeconds(tokenResponse.expiresIn)
        brokerAuthRepository.deleteByUserIdAndBrokerType(userId, BrokerType.KIWOOM)
        val entity = BrokerAuthEntity(
            id                   = UUID.randomUUID(),
            userId               = userId,
            brokerType           = BrokerType.KIWOOM,
            accessToken          = tokenResponse.accessToken,
            refreshToken         = null,
            tokenType            = tokenResponse.tokenType,
            accessTokenExpiresAt = expiresAt,
        )
        brokerAuthRepository.save(entity)
        val cacheKey = "broker:token:$userId:${BrokerType.KIWOOM.name}"
        cacheToken(cacheKey, tokenResponse.accessToken, expiresAt)
    }

    private fun findAuth(userId: UUID): BrokerAuthEntity? =
        try {
            brokerAuthRepository.findByUserIdAndBrokerType(userId, BrokerType.KIWOOM)
        } catch (e: RuntimeException) {
            if (e.requiresSensitiveDataReconnection()) throw KiwoomApiException(SENSITIVE_DATA_RECONNECTION_REQUIRED_MESSAGE)
            throw e
        }

    // ── API ───────────────────────────────────────────────────────────────────

    fun getAccounts(accessToken: String): KiwoomAccountResponse =
        webClient.get()
            .uri("/v1/accounts")
            .header("Authorization", "Bearer $accessToken")
            .retrieve()
            .bodyToMono<KiwoomAccountResponse>()
            .block(Duration.ofSeconds(10))
            ?: KiwoomAccountResponse()

    fun getOrderHistory(
        accessToken: String,
        accountNo: String,
        fromDate: String,
        toDate: String,
        nextKey: String = "",
    ): KiwoomOrderHistoryResponse =
        webClient.get()
            .uri { ub ->
                ub.path("/v1/stock/trading/orders/history")
                    .queryParam("account_no", accountNo)
                    .queryParam("from_date", fromDate)
                    .queryParam("to_date", toDate)
                    .queryParam("order_type", "ALL")
                    .apply { if (nextKey.isNotBlank()) queryParam("next_key", nextKey) }
                    .build()
            }
            .header("Authorization", "Bearer $accessToken")
            .retrieve()
            .bodyToMono<KiwoomOrderHistoryResponse>()
            .block(Duration.ofSeconds(15))
            ?: KiwoomOrderHistoryResponse()

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun cacheToken(key: String, token: String, expiresAt: LocalDateTime) {
        val ttl = Duration.between(LocalDateTime.now(), expiresAt).minusSeconds(60)
        if (ttl.isPositive) {
            runCatching { redisTemplate.opsForValue().set(key, token, ttl) }
                .onFailure { log.warn("[Kiwoom] token cache failed: {}", it.message) }
        }
        localCache[key] = token to (System.currentTimeMillis() + LOCAL_CACHE_TTL_MS)
    }

    companion object {
        private const val LOCAL_CACHE_TTL_MS = 30_000L
    }
}

class KiwoomApiException(message: String) : RuntimeException(message)
