package com.allfolio.broker.toss

import com.allfolio.broker.BrokerSyncStateEntity
import com.allfolio.broker.BrokerSyncStateId
import com.allfolio.broker.BrokerSyncStateRepository
import com.allfolio.broker.BrokerType
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Toss 증권 OAuth2 Authorization Code Callback
 *
 * 흐름:
 * 1. 프론트 → GET /api/broker/toss/authorize?userId=...&portfolioId=... → Toss 인가 페이지로 리다이렉트
 * 2. Toss → GET /api/broker/toss/callback?code=...&state=... → 토큰 교환 + 계좌 조회 + SyncState 등록
 */
@RestController
@RequestMapping("/api/broker/toss")
@ConditionalOnProperty(prefix = "toss", name = ["client-id"], matchIfMissing = false)
class TossOAuthController(
    private val tossApiClient: TossApiClient,
    private val tossProperties: TossProperties,
    private val syncStateRepository: BrokerSyncStateRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Toss 인가 페이지로 리다이렉트 URL 반환
     * state = "{userId}:{portfolioId}" 로 인코딩 (간략 구현)
     */
    @GetMapping("/authorize")
    fun authorize(
        @RequestParam userId: UUID,
        @RequestParam portfolioId: UUID,
    ): ResponseEntity<Map<String, String>> {
        val state       = "$userId:$portfolioId"
        val authorizeUrl = "${tossProperties.baseUrl}/v1/oauth2/authorize" +
            "?response_type=code" +
            "&client_id=${tossProperties.clientId}" +
            "&redirect_uri=${tossProperties.redirectUri}" +
            "&scope=account%20trade" +
            "&state=$state"

        return ResponseEntity.ok(mapOf("authorizeUrl" to authorizeUrl))
    }

    /**
     * Authorization Code 수신 → Token 교환 → BrokerAuth 저장 → 계좌 SyncState 등록
     */
    @GetMapping("/callback")
    fun callback(
        @RequestParam code: String,
        @RequestParam(required = false) state: String?,
    ): ResponseEntity<Map<String, Any>> {
        val (userId, portfolioId) = parseState(state)
            ?: return ResponseEntity.badRequest().body(mapOf("error" to "invalid state"))

        log.info("[TossOAuth] callback userId={} portfolioId={}", userId, portfolioId)

        // 1. Code → Token
        val tokenResponse = tossApiClient.exchangeCodeForToken(code, tossProperties.redirectUri)

        // 2. DB + Redis에 토큰 저장
        tossApiClient.saveAuth(userId, tokenResponse)

        // 3. 계좌 목록 조회 → SyncState 등록
        val accounts = tossApiClient.getAccounts(tokenResponse.accessToken).accounts
        accounts.forEach { account ->
            val stateId = BrokerSyncStateId(portfolioId, BrokerType.TOSS.name, account.accountNumber)
            if (!syncStateRepository.existsById(stateId)) {
                syncStateRepository.save(BrokerSyncStateEntity(id = stateId))
                log.info("[TossOAuth] registered account={} portfolio={}", account.accountNumber, portfolioId)
            }
        }

        return ResponseEntity.ok(mapOf(
            "status"    to "ok",
            "accounts"  to accounts.size,
            "userId"    to userId.toString(),
        ))
    }

    private fun parseState(state: String?): Pair<UUID, UUID>? {
        if (state.isNullOrBlank()) return null
        return runCatching {
            val parts = state.split(":")
            UUID.fromString(parts[0]) to UUID.fromString(parts[1])
        }.getOrNull()
    }
}
