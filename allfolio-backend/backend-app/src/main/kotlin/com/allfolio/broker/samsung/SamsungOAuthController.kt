package com.allfolio.broker.samsung

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

@RestController
@RequestMapping("/api/broker/samsung")
@ConditionalOnProperty(prefix = "samsung", name = ["app-key"], matchIfMissing = false)
class SamsungOAuthController(
    private val samsungApiClient: SamsungApiClient,
    private val samsungProperties: SamsungProperties,
    private val syncStateRepository: BrokerSyncStateRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/authorize")
    fun authorize(
        @RequestParam userId: UUID,
        @RequestParam portfolioId: UUID,
    ): ResponseEntity<Map<String, String>> {
        val state        = "$userId:$portfolioId"
        val authorizeUrl = "${samsungProperties.baseUrl}/oauth2/authorize" +
            "?response_type=code" +
            "&appkey=${samsungProperties.appKey}" +
            "&redirect_uri=${samsungProperties.redirectUri}" +
            "&scope=account%20trade" +
            "&state=$state"
        return ResponseEntity.ok(mapOf("authorizeUrl" to authorizeUrl))
    }

    @GetMapping("/callback")
    fun callback(
        @RequestParam code: String,
        @RequestParam(required = false) state: String?,
    ): ResponseEntity<Map<String, Any>> {
        val (userId, portfolioId) = parseState(state)
            ?: return ResponseEntity.badRequest().body(mapOf("error" to "invalid state"))

        log.info("[SamsungOAuth] callback userId={} portfolioId={}", userId, portfolioId)

        val tokenResponse = samsungApiClient.exchangeCodeForToken(code, samsungProperties.redirectUri)
        samsungApiClient.saveAuth(userId, tokenResponse)

        val accounts = samsungApiClient.getAccounts(tokenResponse.accessToken).accounts
        accounts.forEach { account ->
            val stateId = BrokerSyncStateId(portfolioId, BrokerType.SAMSUNG.name, account.accountNo)
            if (!syncStateRepository.existsById(stateId)) {
                syncStateRepository.save(BrokerSyncStateEntity(id = stateId))
                log.info("[SamsungOAuth] registered account={} portfolio={}", account.accountNo, portfolioId)
            }
        }

        return ResponseEntity.ok(mapOf(
            "status"   to "ok",
            "accounts" to accounts.size,
            "userId"   to userId.toString(),
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
