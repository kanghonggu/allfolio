package com.allfolio.unifiedasset.infrastructure.entity

import com.allfolio.unifiedasset.domain.account.*
import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "ua_accounts")
class AccountEntity(
    @Id @Column(columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    val userId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val provider: AccountProvider,

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 20)
    val accountType: AccountType,

    @Column(name = "account_name", nullable = false)
    val accountName: String,

    @Column(name = "external_id")
    val externalId: String?,

    @Column(nullable = false, length = 10)
    val currency: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: AccountStatus,

    @Column(name = "last_synced_at")
    var lastSyncedAt: LocalDateTime?,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime,

    @Column(name = "api_key", length = 500)
    val apiKey: String?,

    @Column(name = "api_secret", length = 500)
    val apiSecret: String?,

    @Column(name = "wallet_address", length = 200)
    val walletAddress: String?,

    @Column(length = 20)
    val chain: String?,
) {
    fun toDomain() = Account.reconstruct(
        id, userId, provider, accountType, accountName, externalId, currency,
        status, lastSyncedAt, createdAt, apiKey, apiSecret, walletAddress, chain
    )

    companion object {
        fun fromDomain(a: Account) = AccountEntity(
            a.id, a.userId, a.provider, a.accountType, a.accountName, a.externalId,
            a.currency, a.status, a.lastSyncedAt, a.createdAt, a.apiKey, a.apiSecret,
            a.walletAddress, a.chain
        )
    }
}
