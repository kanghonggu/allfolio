package com.allfolio.broker.kiwoom

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

/**
 * 키움증권 OAuth2 콜백 컨트롤러
 *
 * GET /api/broker/kiwoom/authorize  → 인가 URL로 리다이렉트
 * GET /api/broker/kiwoom/callback   → code 수신 → token 교환 → 저장
 */
@RestController
@RequestMapping("/api/broker/kiwoom")
@ConditionalOnProperty(prefix = "kiwoom", name = ["app-key"], matchIfMissing = false)
class KiwoomOAuthController(
    private val kiwoomApiClient: KiwoomApiClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/authorize")
    fun authorize(
        @RequestHeader("X-User-Id") userId: UUID,
    ): ResponseEntity<Void> {
        val url = kiwoomApiClient.authorizeUrl()
        return ResponseEntity.status(302).location(URI.create(url)).build()
    }

    @GetMapping("/callback")
    fun callback(
        @RequestParam("code")  code: String,
        @RequestParam("state", required = false) state: String?,
        @RequestHeader("X-User-Id") userId: UUID,
    ): ResponseEntity<Map<String, Any>> {
        return runCatching {
            val token = kiwoomApiClient.exchangeCode(code)
            kiwoomApiClient.saveAuth(userId, token)
            log.info("[Kiwoom] OAuth2 callback success user={}", userId)
            ResponseEntity.ok(mapOf<String, Any>("success" to true, "message" to "Kiwoom 연동 완료"))
        }.getOrElse { e ->
            log.error("[Kiwoom] OAuth2 callback failed user={}: {}", userId, e.message)
            ResponseEntity.internalServerError().body(mapOf<String, Any>(
                "success" to false,
                "message" to (e.message ?: "token exchange failed"),
            ))
        }
    }
}
