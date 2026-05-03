package com.allfolio.broker.kis

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * KIS 토큰 수동 발급 엔드포인트
 *
 * KIS는 Authorization Code 플로우 없이 appkey/appsecret으로 직접 발급.
 * 관리자가 수동으로 트리거하거나 스케줄러에서 호출.
 *
 * POST /api/broker/kis/token  → access_token 발급 + 저장
 * GET  /api/broker/kis/status → 토큰 상태 확인
 */
@RestController
@RequestMapping("/api/broker/kis")
@ConditionalOnProperty(prefix = "kis", name = ["app-key"], matchIfMissing = false)
class KisOAuthController(
    private val kisApiClient: KisApiClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/token")
    fun issueToken(
        @RequestHeader("X-User-Id") userId: UUID,
    ): ResponseEntity<Map<String, Any>> {
        return runCatching {
            val resp = kisApiClient.issueToken()
            kisApiClient.saveAuth(userId, resp)
            log.info("[KIS] token issued for user={}", userId)
            @Suppress("UNCHECKED_CAST")
            ResponseEntity.ok(mapOf<String, Any>(
                "success"   to true,
                "tokenType" to resp.tokenType,
                "expiresIn" to resp.expiresIn,
            ))
        }.getOrElse { e ->
            log.error("[KIS] token issuance failed user={}: {}", userId, e.message)
            @Suppress("UNCHECKED_CAST")
            ResponseEntity.internalServerError().body(mapOf<String, Any>(
                "success" to false,
                "message" to (e.message ?: "token issuance failed"),
            ))
        }
    }

    @GetMapping("/status")
    fun status(
        @RequestHeader("X-User-Id") userId: UUID,
    ): ResponseEntity<Map<String, Any>> {
        val token = runCatching { kisApiClient.resolveAccessToken(userId) }.getOrNull()
        return ResponseEntity.ok(mapOf<String, Any>(
            "configured" to (token != null),
            "userId"     to userId.toString(),
        ))
    }
}
