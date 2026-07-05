package com.allfolio.broker.kis

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
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 한국투자증권 Open API 클라이언트
 *
 * 인증: Client Credentials (appkey + appsecret → access_token)
 *   - OAuth2 Authorization Code 플로우 없음
 *   - access_token TTL: 24시간
 *
 * Token 계층: L1 Local(30s) → L2 Redis → L3 갱신
 *
 * tr_id 구분:
 *   실전: TTTC8001R  (kis.mock=false)
 *   모의: VTTC8001R  (kis.mock=true)
 */
@Component
class KisApiClient(
    private val kisProperties: KisProperties,
    private val brokerAuthRepository: BrokerAuthRepository,
    private val redisTemplate: RedisTemplate<String, Any>,
    private val metrics: BrokerMetrics,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val webClient = WebClient.builder()
        .baseUrl(kisProperties.baseUrl)
        .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .build()

    private val localCache = ConcurrentHashMap<String, Pair<String, Long>>()

    // ── Token ────────────────────────────────────────────────────────────────

    /** Client Credentials 방식으로 access_token 발급 */
    fun issueToken(): KisTokenResponse =
        webClient.post()
            .uri("/oauth2/tokenP")
            .bodyValue(mapOf(
                "grant_type" to "client_credentials",
                "appkey"     to kisProperties.appKey,
                "appsecret"  to kisProperties.appSecret,
            ))
            .retrieve()
            .bodyToMono<KisTokenResponse>()
            .block(Duration.ofSeconds(10))
            ?: throw KisApiException("KIS token issuance failed")

    /**
     * WebSocket 연결용 approval_key 발급
     * 실시간 시세 구독 시 헤더에 사용
     */
    fun issueApprovalKey(): String {
        val response = webClient.post()
            .uri("/oauth2/Approval")
            .bodyValue(mapOf(
                "grant_type" to "client_credentials",
                "appkey"     to kisProperties.appKey,
                "secretkey"  to kisProperties.appSecret,
            ))
            .retrieve()
            .bodyToMono<KisApprovalKeyResponse>()
            .block(Duration.ofSeconds(10))
            ?: throw KisApiException("KIS approval_key issuance failed")
        return response.approvalKey
    }

    /**
     * 3-tier token 해석: L1 Local → L2 Redis → L3 DB/발급
     * KIS는 OAuth2 Authorization Code 없이 appkey/appsecret만으로 토큰 발급
     * userId 별 토큰을 관리하지 않고 앱 단위(싱글 토큰)로 관리한다.
     */
    fun resolveAccessToken(userId: UUID): String {
        val cacheKey = "broker:token:$userId:${BrokerType.KIS.name}"

        localCache[cacheKey]?.let { (token, expiry) ->
            if (System.currentTimeMillis() < expiry) return token
            localCache.remove(cacheKey)
        }

        runCatching { redisTemplate.opsForValue().get(cacheKey) as? String }.getOrNull()
            ?.let { token ->
                localCache[cacheKey] = token to (System.currentTimeMillis() + LOCAL_CACHE_TTL_MS)
                return token
            }

        return refreshAndCache(userId, cacheKey)
    }

    private fun refreshAndCache(userId: UUID, cacheKey: String): String {
        val existing = findAuth(userId)

        if (existing != null && !existing.isAccessTokenExpired()) {
            cacheToken(cacheKey, existing.accessToken, existing.accessTokenExpiresAt)
            return existing.accessToken
        }

        log.info("[KIS] issuing new access token for user={}", userId)
        metrics.tokenRefreshed("KIS")
        val resp      = issueToken()
        val expiresAt = LocalDateTime.now().plusSeconds(resp.expiresIn)

        val entity = existing?.apply {
            accessToken          = resp.accessToken
            accessTokenExpiresAt = expiresAt
            updatedAt            = LocalDateTime.now()
        } ?: BrokerAuthEntity(
            id                   = UUID.randomUUID(),
            userId               = userId,
            brokerType           = BrokerType.KIS,
            accessToken          = resp.accessToken,
            refreshToken         = null,
            tokenType            = resp.tokenType,
            accessTokenExpiresAt = expiresAt,
        )
        brokerAuthRepository.save(entity)
        cacheToken(cacheKey, resp.accessToken, expiresAt)
        return resp.accessToken
    }

    fun saveAuth(userId: UUID, tokenResponse: KisTokenResponse) {
        val expiresAt = LocalDateTime.now().plusSeconds(tokenResponse.expiresIn)
        brokerAuthRepository.deleteByUserIdAndBrokerType(userId, BrokerType.KIS)
        val entity = BrokerAuthEntity(
            id                   = UUID.randomUUID(),
            userId               = userId,
            brokerType           = BrokerType.KIS,
            accessToken          = tokenResponse.accessToken,
            refreshToken         = null,
            tokenType            = tokenResponse.tokenType,
            accessTokenExpiresAt = expiresAt,
        )
        brokerAuthRepository.save(entity)
        val cacheKey = "broker:token:$userId:${BrokerType.KIS.name}"
        cacheToken(cacheKey, tokenResponse.accessToken, expiresAt)
    }

    private fun findAuth(userId: UUID): BrokerAuthEntity? =
        try {
            brokerAuthRepository.findByUserIdAndBrokerType(userId, BrokerType.KIS)
        } catch (e: RuntimeException) {
            if (e.requiresSensitiveDataReconnection()) throw KisApiException(SENSITIVE_DATA_RECONNECTION_REQUIRED_MESSAGE)
            throw e
        }

    // ── API 호출 ──────────────────────────────────────────────────────────────

    /**
     * 일별 주문 체결 조회 (TTTC8001R / VTTC8001R)
     *
     * @param accountNo    계좌번호 앞 8자리 (CANO)
     * @param productCode  계좌상품코드 2자리 (ACNT_PRDT_CD)  ex) "01"
     * @param fromDate     조회시작일 YYYYMMDD
     * @param toDate       조회종료일 YYYYMMDD
     * @param ctxAreaFk100 연속조회 커서 (빈 문자열 = 첫 페이지)
     */
    fun getDailyOrders(
        accessToken: String,
        accountNo: String,
        productCode: String,
        fromDate: String,
        toDate: String,
        ctxAreaFk100: String = "",
    ): KisOrderHistoryResponse {
        return webClient.get()
            .uri { ub ->
                ub.path("/uapi/domestic-stock/v1/trading/inquire-daily-ccld")
                    .queryParam("CANO", accountNo)
                    .queryParam("ACNT_PRDT_CD", productCode)
                    .queryParam("INQR_STRT_DT", fromDate)
                    .queryParam("INQR_END_DT", toDate)
                    .queryParam("SLL_BUY_DVSN_CD", "00")   // 00=전체
                    .queryParam("INQR_DVSN", "00")
                    .queryParam("PDNO", "")
                    .queryParam("CCLD_DVSN", "01")         // 01=체결
                    .queryParam("ORD_GNO_BRNO", "")
                    .queryParam("ODNO", "")
                    .queryParam("INQR_DVSN_3", "00")
                    .queryParam("INQR_DVSN_1", "")
                    .queryParam("CTX_AREA_FK100", ctxAreaFk100)
                    .queryParam("CTX_AREA_NK100", "")
                    .build()
            }
            .header("Authorization", "Bearer $accessToken")
            .header("appkey", kisProperties.appKey)
            .header("appsecret", kisProperties.appSecret)
            .header("tr_id", kisProperties.trIdDailyOrder())
            .header("custtype", "P")
            .retrieve()
            .bodyToMono<KisOrderHistoryResponse>()
            .block(Duration.ofSeconds(15))
            ?: KisOrderHistoryResponse()
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun cacheToken(key: String, token: String, expiresAt: LocalDateTime) {
        val ttl = Duration.between(LocalDateTime.now(), expiresAt).minusSeconds(120)
        if (ttl.isPositive) {
            runCatching { redisTemplate.opsForValue().set(key, token, ttl) }
                .onFailure { log.warn("[KIS] token cache failed: {}", it.message) }
        }
        localCache[key] = token to (System.currentTimeMillis() + LOCAL_CACHE_TTL_MS)
    }

    companion object {
        private const val LOCAL_CACHE_TTL_MS = 30_000L
    }
}

class KisApiException(message: String) : RuntimeException(message)
