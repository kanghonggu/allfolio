package com.allfolio.unifiedasset.application.port

import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountStatus
import java.util.UUID

interface AccountRepository {
    fun save(account: Account): Account
    fun findById(id: UUID): Account?
    fun findByUserId(userId: UUID): List<Account>
    fun delete(id: UUID)
    fun updateStatus(id: UUID, status: AccountStatus)
}
