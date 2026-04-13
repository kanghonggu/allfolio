package com.allfolio.unifiedasset.domain.account

import java.time.LocalDateTime
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
    fun completeSync(): Account = copy(status = AccountStatus.ACTIVE, lastSyncedAt = LocalDateTime.now())
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
            currency: String = "USD",
            apiKey: String? = null,
            apiSecret: String? = null,
            walletAddress: String? = null,
            chain: String? = null,
        ): Account {
            require(accountName.isNotBlank()) { "계좌명은 필수입니다" }
            return Account(
                id            = UUID.randomUUID(),
                userId        = userId,
                provider      = provider,
                accountType   = accountType,
                accountName   = accountName.trim(),
                externalId    = externalId?.trim(),
                currency      = currency.uppercase(),
                status        = AccountStatus.ACTIVE,
                lastSyncedAt  = null,
                createdAt     = LocalDateTime.now(),
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
