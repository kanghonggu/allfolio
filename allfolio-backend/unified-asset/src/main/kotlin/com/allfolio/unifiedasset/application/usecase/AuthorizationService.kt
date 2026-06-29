package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AccountRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class AuthorizationService(
    private val accountRepository: AccountRepository,
) {
    fun requireOwnedAccount(userId: UUID, accountId: UUID) {
        val account = accountRepository.findById(accountId)
        if (account?.userId != userId) {
            throw NoSuchElementException("Account not found: $accountId")
        }
    }
}
