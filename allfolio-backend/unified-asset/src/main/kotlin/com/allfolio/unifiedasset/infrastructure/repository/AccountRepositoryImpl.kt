package com.allfolio.unifiedasset.infrastructure.repository

import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountStatus
import com.allfolio.unifiedasset.infrastructure.entity.AccountEntity
import com.allfolio.unifiedasset.infrastructure.jpa.AccountJpaRepository
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.UUID

@Repository
class AccountRepositoryImpl(private val jpa: AccountJpaRepository) : AccountRepository {
    override fun save(account: Account): Account =
        jpa.save(AccountEntity.fromDomain(account)).toDomain()

    override fun findById(id: UUID): Account? =
        jpa.findById(id).orElse(null)?.toDomain()

    override fun findByUserId(userId: UUID): List<Account> =
        jpa.findByUserId(userId).map { it.toDomain() }

    override fun delete(id: UUID) = jpa.deleteById(id)

    override fun updateStatus(id: UUID, status: AccountStatus) {
        if (status == AccountStatus.ACTIVE) {
            jpa.updateStatusAndSyncedAt(id, status, LocalDateTime.now())
        } else {
            jpa.updateStatus(id, status)
        }
    }
}
