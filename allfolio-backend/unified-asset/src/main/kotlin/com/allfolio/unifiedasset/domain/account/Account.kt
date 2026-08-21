package com.allfolio.unifiedasset.domain.account

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

class Account private constructor(
    val id: UUID,
    val userId: UUID,
    val provider: AccountProvider,
    val accountType: AccountType,
    val accountName: String,
    val externalId: String?,
    val currency: String,
    val status: AccountStatus,
    val lastSyncedAt: LocalDateTime?,
    val createdAt: LocalDateTime,
    // API 계좌용 자격증명 (암호화 저장 권장)
    val apiKey: String?,
    val apiSecret: String?,
    // Wallet 계좌용
    val walletAddress: String?,
    val chain: String?,
) {
    fun startSync(): Account = copy(status = AccountStatus.SYNCING)
    /**
     * **저장 시각은 UTC다.** `last_synced_at`은 존 없는 `TIMESTAMP`라 값 자체가 어느 존의
     * 벽시계인지 말해주지 않는다. `LocalDateTime.now()`는 호스트 타임존을 따라가므로 운영
     * 컨테이너(UTC)에선 맞고 KST 데스크톱에선 9시간 미래가 들어간다. 존을 명시해 그 우연을
     * 없앤다 — 읽는 쪽 `atOffset(ZoneOffset.UTC)`가 이 전제 위에 서 있다.
     */
    fun completeSync(): Account = copy(status = AccountStatus.ACTIVE, lastSyncedAt = LocalDateTime.now(ZoneOffset.UTC))
    fun failSync(reason: String): Account = copy(status = AccountStatus.ERROR)

    private fun copy(
        status: AccountStatus = this.status,
        lastSyncedAt: LocalDateTime? = this.lastSyncedAt,
    ) = Account(
        id, userId, provider, accountType, accountName, externalId,
        currency, status, lastSyncedAt, createdAt, apiKey, apiSecret,
        walletAddress, chain
    )

    companion object {
        fun create(
            userId: UUID,
            provider: AccountProvider,
            accountType: AccountType,
            accountName: String,
            externalId: String? = null,
            currency: String = "KRW",
            apiKey: String? = null,
            apiSecret: String? = null,
            walletAddress: String? = null,
            chain: String? = null,
        ): Account {
            // QA P2: 계좌명 서버 사이드 산티타이징 + 통화 화이트리스트 검증
            val safeName = com.allfolio.unifiedasset.domain.common.sanitizeUserText(accountName)
            require(safeName.isNotBlank()) { "계좌명은 필수입니다" }
            return Account(
                id            = UUID.randomUUID(),
                userId        = userId,
                provider      = provider,
                accountType   = accountType,
                accountName   = safeName,
                externalId    = externalId?.trim(),
                currency      = com.allfolio.unifiedasset.domain.common.Currencies.normalize(currency),
                status        = AccountStatus.ACTIVE,
                lastSyncedAt  = null,
                createdAt     = LocalDateTime.now(ZoneOffset.UTC),
                apiKey        = apiKey,
                apiSecret     = apiSecret,
                walletAddress = walletAddress?.trim(),
                chain         = chain?.uppercase(),
            )
        }

        fun reconstruct(
            id: UUID, userId: UUID, provider: AccountProvider, accountType: AccountType,
            accountName: String, externalId: String?, currency: String, status: AccountStatus,
            lastSyncedAt: LocalDateTime?, createdAt: LocalDateTime,
            apiKey: String?, apiSecret: String?, walletAddress: String?, chain: String?,
        ) = Account(id, userId, provider, accountType, accountName, externalId, currency,
            status, lastSyncedAt, createdAt, apiKey, apiSecret, walletAddress, chain)
    }
}
