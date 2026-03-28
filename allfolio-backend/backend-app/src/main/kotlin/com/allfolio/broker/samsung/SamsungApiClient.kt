package com.allfolio.broker.samsung

import com.allfolio.broker.BrokerAuthEntity
import com.allfolio.broker.BrokerAuthRepository
import com.allfolio.broker.BrokerType
import com.allfolio.metrics.BrokerMetrics
import com.fasterxml.jackson.databind.ObjectMapper
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
 * 삼성증권 API WebClient
 *
 * TossApiClient와 동일한 패턴:
 * - 3-tier token cache: Local(30s) → Redis → DB
 * - SETNX 분산락 refresh race condition 방지
 * - Non-blocking rate limit (BrokerFacade에서 처리)
 *
 * Samsung 특이사항:
 * - appKey/appSecret (clientId/clientSecret 아님)
 * - OAuth2 token endpoint: /oauth2/tokenP
 * - 체결 내역: /v1/domestic/trade-history (커서 기반 페이징)
 */
@Component
class SamsungApiClient(
    private val samsungProperties: SamsungProperties,
    private val brokerAuthRepository: BrokerAuthRepository,
    private val redisTemplate: RedisTemplate<String, Any>,
    private val objectMapper: ObjectMapper,
    private val metrics: BrokerMetrics,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val webClient: WebClient = WebClient.builder()
        .baseUrl(samsungProperties.baseUrl)
        .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .build()

    /** L1 Local Token Cache: key → (token, expiryMs) */
    private val localCache = ConcurrentHashMap<String, Pair<String, Long>>()

    // ──────────────────────────────────────────────
    // OAuth2 Token 관리
    // ──────────────────────────────────────────────

    fun exchangeCodeForToken(code: String, redirectUri: String): SamsungTokenResponse {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type",    "authorization_code")
            add("code",          code)
            add("redirect_uri",  redirectUri)
            add("appkey",        samsungProperties.appKey)
            add("secretkey",     samsungProperties.appSecret)
        }
        return webClient.post()
            .uri("/oauth2/tokenP")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue(form)
            .retrieve()
            .bodyToMono<SamsungTokenResponse>()
            .block(Duration.ofSeconds(10))
            ?: throw SamsungApiException("Failed to exchange code for token")
    }

    fun refreshAccessToken(refreshToken: String): SamsungTokenResponse {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type",    "refresh_token")
            add("refresh_token", refreshToken)
            add("appkey",        samsungProperties.appKey)
            add("secretkey",     samsungProperties.appSecret)
        }
        return webClient.post()
            .uri("/oauth2/tokenP")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue(form)
            .retrieve()
            .bodyToMono<SamsungTokenResponse>()
            .block(Duration.ofSeconds(10))
            ?: throw SamsungApiException("Failed to refresh token")
    }

    // ──────────────────────────────────────────────
    // Token 해석: L1 Local → L2 Redis → L3 DB → Refresh
    // ──────────────────────────────────────────────

    fun resolveAccessToken(userId: UUID): String {
        val cacheKey = "broker:token:$userId:${BrokerType.SAMSUNG.name}"

        localCache[cacheKey]?.let { (token, expiry) ->
            if (System.currentTimeMillis() < expiry) return token
            localCache.remove(cacheKey)
        }

        runCatching { redisTemplate.opsForValue().get(cacheKey) as? String }.getOrNull()
            ?.let { token ->
                localCache[cacheKey] = token to (System.currentTimeMillis() + LOCAL_CACHE_TTL_MS)
                return token
            }

        return resolveWithLock(userId, cacheKey)
    }

    private fun resolveWithLock(userId: UUID, cacheKey: String): String {
        val lockKey   = "lock:token:refresh:$userId:SAMSUNG"
        val lockValue = UUID.randomUUID().toString()

        val acquired = runCatching {
            redisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, Duration.ofSeconds(10)) == true
        }.getOrDefault(false)

        if (!acquired) {
            val auth = brokerAuthRepository.findByUserIdAndBrokerType(userId, BrokerType.SAMSUNG)
                ?: throw SamsungApiException("No Samsung auth for user=$userId")
            if (!auth.isAccessTokenExpired()) return auth.accessToken
            throw SamsungApiException("Token refresh in progress for user=$userId, retry shortly")
        }

        return try {
            val auth = brokerAuthRepository.findByUserIdAndBrokerType(userId, BrokerType.SAMSUNG)
                ?: throw SamsungApiException("No Samsung auth for user=$userId. OAuth2 consent required.")

            if (!auth.isAccessTokenExpired()) {
                cacheToken(cacheKey, auth.accessToken, auth.accessTokenExpiresAt)
                return auth.accessToken
            }

            val rt = auth.refreshToken ?: throw SamsungApiException("No refresh token for user=$userId")
            if (auth.isRefreshTokenExpired()) throw SamsungApiException("Refresh token expired for user=$userId")

            log.info("[Samsung] refreshing access token for user={}", userId)
            metrics.tokenRefreshed("SAMSUNG")
            val newToken  = refreshAccessToken(rt)
            val expiresAt = LocalDateTime.now().plusSeconds(newToken.expiresIn.toLong())

            auth.accessToken          = newToken.accessToken
            auth.accessTokenExpiresAt = expiresAt
            newToken.refreshToken?.let { auth.refreshToken = it }
            auth.updatedAt = LocalDateTime.now()
            brokerAuthRepository.save(auth)

            cacheToken(cacheKey, newToken.accessToken, expiresAt)
            log.info("[Samsung] token refreshed userId={}", userId)
            newToken.accessToken
        } finally {
            releaseLock(lockKey, lockValue)
        }
    }

    fun saveAuth(userId: UUID, tokenResponse: SamsungTokenResponse) {
        val expiresAt = LocalDateTime.now().plusSeconds(tokenResponse.expiresIn.toLong())
        val existing  = brokerAuthRepository.findByUserIdAndBrokerType(userId, BrokerType.SAMSUNG)

        val entity = existing?.apply {
            accessToken          = tokenResponse.accessToken
            accessTokenExpiresAt = expiresAt
            tokenResponse.refreshToken?.let { refreshToken = it }
            updatedAt = LocalDateTime.now()
        } ?: BrokerAuthEntity(
            id                   = UUID.randomUUID(),
            userId               = userId,
            brokerType           = BrokerType.SAMSUNG,
            accessToken          = tokenResponse.accessToken,
            refreshToken         = tokenResponse.refreshToken,
            tokenType            = tokenResponse.tokenType,
            accessTokenExpiresAt = expiresAt,
        )
        brokerAuthRepository.save(entity)

        val cacheKey = "broker:token:$userId:${BrokerType.SAMSUNG.name}"
        cacheToken(cacheKey, tokenResponse.accessToken, expiresAt)
    }

    // ──────────────────────────────────────────────
    // API 호출
    // ──────────────────────────────────────────────

    fun getAccounts(accessToken: String): SamsungAccountListResponse =
        webClient.get()
            .uri("/v1/accounts")
            .header("Authorization", "Bearer $accessToken")
            .header("appkey", samsungProperties.appKey)
            .retrieve()
            .bodyToMono<SamsungAccountListResponse>()
            .block(Duration.ofSeconds(10))
            ?: SamsungAccountListResponse(emptyList())

    fun getTrades(
        accessToken: String,
        accountNo: String,
        fromDate: String,
        toDate: String,
        cursor: String = "",
    ): SamsungTradePageResponse {
        val uri = buildString {
            append("/v1/domestic/trade-history?account_no=$accountNo")
            append("&from_dt=$fromDate&to_dt=$toDate")
            if (cursor.isNotBlank()) append("&cursor=$cursor")
        }
        return webClient.get()
            .uri(uri)
            .header("Authorization", "Bearer $accessToken")
            .header("appkey", samsungProperties.appKey)
            .retrieve()
            .bodyToMono<SamsungTradePageResponse>()
            .block(Duration.ofSeconds(10))
            ?: SamsungTradePageResponse(emptyList())
    }

    // ──────────────────────────────────────────────
    // Internal
    // ──────────────────────────────────────────────

    private fun cacheToken(key: String, token: String, expiresAt: LocalDateTime) {
        val ttl = Duration.between(LocalDateTime.now(), expiresAt).minusSeconds(60)
        if (ttl.isPositive) {
            runCatching { redisTemplate.opsForValue().set(key, token, ttl) }
                .onFailure { log.warn("[Samsung] token cache failed key={}", key, it) }
        }
        localCache[key] = token to (System.currentTimeMillis() + LOCAL_CACHE_TTL_MS)
    }

    private fun releaseLock(lockKey: String, lockValue: String) {
        runCatching {
            val script = """
                if redis.call("get", KEYS[1]) == ARGV[1] then
                    return redis.call("del", KEYS[1])
                else return 0 end
            """.trimIndent()
            redisTemplate.execute(
                org.springframework.data.redis.core.script.RedisScript.of(script, Long::class.java),
                listOf(lockKey),
                lockValue,
            )
        }.onFailure { log.warn("[Samsung] lock release failed key={}", lockKey, it) }
    }

    companion object {
        private const val LOCAL_CACHE_TTL_MS = 30_000L
    }
}

class SamsungApiException(message: String) : RuntimeException(message)
