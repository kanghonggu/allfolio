package com.allfolio.broker.toss

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
 * Toss 증권 API WebClient
 *
 * 인증 흐름 (OAuth2 Authorization Code):
 * 1. 사용자 → Toss 인가 페이지 → code 발급
 * 2. POST /v1/oauth2/token (code → accessToken + refreshToken)
 * 3. API 호출 시 Authorization: Bearer {accessToken}
 * 4. accessToken 만료 → refreshToken으로 갱신 (분산락으로 race condition 방지)
 *
 * Token 계층:
 *   L1 Local cache (30s TTL) → L2 Redis (expiresIn-60s) → L3 DB → Refresh
 *
 * Redis key: broker:token:{userId}:{brokerType}
 * Lock key:  lock:token:refresh:{userId}:TOSS  TTL 10s
 */
@Component
class TossApiClient(
    private val tossProperties: TossProperties,
    private val brokerAuthRepository: BrokerAuthRepository,
    private val redisTemplate: RedisTemplate<String, Any>,
    private val objectMapper: ObjectMapper,
    private val metrics: BrokerMetrics,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val webClient: WebClient = WebClient.builder()
        .baseUrl(tossProperties.baseUrl)
        .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .build()

    /**
     * L1 Local Token Cache
     * key → (accessToken, expiryEpochMs)
     * 30초 TTL: Redis 왕복 90% 절감, GC-friendly (소수 entry 유지)
     */
    private val localCache = ConcurrentHashMap<String, Pair<String, Long>>()

    // ──────────────────────────────────────────────
    // OAuth2 Token 관리
    // ──────────────────────────────────────────────

    /** Authorization Code → Token 교환 */
    fun exchangeCodeForToken(code: String, redirectUri: String): TossTokenResponse {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type",    "authorization_code")
            add("code",          code)
            add("redirect_uri",  redirectUri)
            add("client_id",     tossProperties.clientId)
            add("client_secret", tossProperties.clientSecret)
        }
        return webClient.post()
            .uri("/v1/oauth2/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue(form)
            .retrieve()
            .bodyToMono<TossTokenResponse>()
            .block(Duration.ofSeconds(10))
            ?: throw TossApiException("Failed to exchange code for token")
    }

    /** RefreshToken → 새 AccessToken */
    fun refreshAccessToken(refreshToken: String): TossTokenResponse {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type",    "refresh_token")
            add("refresh_token", refreshToken)
            add("client_id",     tossProperties.clientId)
            add("client_secret", tossProperties.clientSecret)
        }
        return webClient.post()
            .uri("/v1/oauth2/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .bodyValue(form)
            .retrieve()
            .bodyToMono<TossTokenResponse>()
            .block(Duration.ofSeconds(10))
            ?: throw TossApiException("Failed to refresh token")
    }

    // ──────────────────────────────────────────────
    // Token 해석: L1 Local → L2 Redis → L3 DB → Refresh
    // ──────────────────────────────────────────────

    /**
     * 3-tier cache + SETNX 분산락 refresh
     *
     * L1 Local (30s) → L2 Redis → L3 DB
     * Refresh 필요 시 SETNX lock 획득 → 갱신 → lock 해제
     * lock 경합 시: DB에서 직접 읽기 (non-blocking fallback)
     *
     * @throws TossApiException 인증 없거나 갱신 불가
     */
    fun resolveAccessToken(userId: UUID): String {
        val cacheKey = "broker:token:$userId:${BrokerType.TOSS.name}"

        // L1 Local cache hit (30s TTL — Redis 왕복 절감)
        localCache[cacheKey]?.let { (token, expiry) ->
            if (System.currentTimeMillis() < expiry) return token
            localCache.remove(cacheKey)
        }

        // L2 Redis hit
        runCatching { redisTemplate.opsForValue().get(cacheKey) as? String }.getOrNull()
            ?.let { token ->
                localCache[cacheKey] = token to (System.currentTimeMillis() + LOCAL_CACHE_TTL_MS)
                return token
            }

        // L3 DB + Refresh (분산락)
        return resolveWithLock(userId, cacheKey)
    }

    /**
     * SETNX 분산락으로 token refresh race condition 방지
     * lock 미획득 시: DB 직접 조회 (non-blocking, 짧은 대기 없음)
     */
    private fun resolveWithLock(userId: UUID, cacheKey: String): String {
        val lockKey   = "lock:token:refresh:$userId:TOSS"
        val lockValue = UUID.randomUUID().toString()

        val acquired = runCatching {
            redisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, Duration.ofSeconds(10)) == true
        }.getOrDefault(false)

        if (!acquired) {
            // 다른 스레드가 refresh 중 — DB에서 직접 읽기 (non-blocking)
            val auth = brokerAuthRepository.findByUserIdAndBrokerType(userId, BrokerType.TOSS)
                ?: throw TossApiException("No Toss auth for user=$userId")
            if (!auth.isAccessTokenExpired()) return auth.accessToken
            throw TossApiException("Token refresh in progress for user=$userId, retry shortly")
        }

        return try {
            val auth = brokerAuthRepository.findByUserIdAndBrokerType(userId, BrokerType.TOSS)
                ?: throw TossApiException("No Toss auth for user=$userId. OAuth2 consent required.")

            if (!auth.isAccessTokenExpired()) {
                cacheToken(cacheKey, auth.accessToken, auth.accessTokenExpiresAt)
                return auth.accessToken
            }

            val rt = auth.refreshToken ?: throw TossApiException("No refresh token for user=$userId")
            if (auth.isRefreshTokenExpired()) throw TossApiException("Refresh token expired for user=$userId")

            log.info("[Toss] refreshing access token for user={}", userId)
            metrics.tokenRefreshed("TOSS")
            val newToken  = refreshAccessToken(rt)
            val expiresAt = LocalDateTime.now().plusSeconds(newToken.expiresIn.toLong())

            auth.accessToken          = newToken.accessToken
            auth.accessTokenExpiresAt = expiresAt
            newToken.refreshToken?.let { auth.refreshToken = it }
            auth.updatedAt = LocalDateTime.now()
            brokerAuthRepository.save(auth)

            cacheToken(cacheKey, newToken.accessToken, expiresAt)
            log.info("[Toss] token refreshed userId={}", userId)
            newToken.accessToken
        } finally {
            releaseLock(lockKey, lockValue)
        }
    }

    /** BrokerAuth 신규 저장 또는 업데이트 (OAuth2 callback handler에서 호출) */
    fun saveAuth(userId: UUID, tokenResponse: TossTokenResponse) {
        val expiresAt = LocalDateTime.now().plusSeconds(tokenResponse.expiresIn.toLong())
        val existing  = brokerAuthRepository.findByUserIdAndBrokerType(userId, BrokerType.TOSS)

        val entity = existing?.apply {
            accessToken          = tokenResponse.accessToken
            accessTokenExpiresAt = expiresAt
            tokenResponse.refreshToken?.let { refreshToken = it }
            updatedAt = LocalDateTime.now()
        } ?: BrokerAuthEntity(
            id                   = UUID.randomUUID(),
            userId               = userId,
            brokerType           = BrokerType.TOSS,
            accessToken          = tokenResponse.accessToken,
            refreshToken         = tokenResponse.refreshToken,
            tokenType            = tokenResponse.tokenType,
            accessTokenExpiresAt = expiresAt,
        )
        brokerAuthRepository.save(entity)

        val cacheKey = "broker:token:$userId:${BrokerType.TOSS.name}"
        cacheToken(cacheKey, tokenResponse.accessToken, expiresAt)
    }

    // ──────────────────────────────────────────────
    // API 호출
    // ──────────────────────────────────────────────

    /** 계좌 목록 */
    fun getAccounts(accessToken: String): TossAccountListResponse =
        webClient.get()
            .uri("/v1/accounts")
            .header("Authorization", "Bearer $accessToken")
            .retrieve()
            .bodyToMono<TossAccountListResponse>()
            .block(Duration.ofSeconds(10))
            ?: TossAccountListResponse(emptyList())

    /**
     * 체결 내역 조회
     * @param cursor 페이지 커서 (빈 문자열 = 첫 페이지)
     */
    fun getTrades(
        accessToken: String,
        accountNumber: String,
        fromDate: String,
        toDate: String,
        cursor: String = "",
    ): TossTradePageResponse {
        val uri = buildString {
            append("/v1/domestic/trade-history?accountNumber=$accountNumber")
            append("&fromDate=$fromDate&toDate=$toDate")
            if (cursor.isNotBlank()) append("&cursor=$cursor")
        }
        return webClient.get()
            .uri(uri)
            .header("Authorization", "Bearer $accessToken")
            .retrieve()
            .bodyToMono<TossTradePageResponse>()
            .block(Duration.ofSeconds(10))
            ?: TossTradePageResponse(emptyList())
    }

    // ──────────────────────────────────────────────
    // Internal
    // ──────────────────────────────────────────────

    private fun cacheToken(key: String, token: String, expiresAt: LocalDateTime) {
        // L2 Redis: TTL = expiresIn - 60s (여유 60s)
        val ttl = Duration.between(LocalDateTime.now(), expiresAt).minusSeconds(60)
        if (ttl.isPositive) {
            runCatching { redisTemplate.opsForValue().set(key, token, ttl) }
                .onFailure { log.warn("[Toss] token cache failed key={}", key, it) }
        }
        // L1 Local cache 갱신
        localCache[key] = token to (System.currentTimeMillis() + LOCAL_CACHE_TTL_MS)
    }

    /** Lua 스크립트로 안전한 lock 해제 (본인 소유 확인 후 삭제) */
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
        }.onFailure { log.warn("[Toss] lock release failed key={}", lockKey, it) }
    }

    companion object {
        private const val LOCAL_CACHE_TTL_MS = 30_000L  // 30초
    }
}

class TossApiException(message: String) : RuntimeException(message)
