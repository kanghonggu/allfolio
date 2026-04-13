package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.domain.account.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class CreateAccountCommand(
    val userId: UUID,
    val provider: AccountProvider,
    val accountType: AccountType,
    val accountName: String,
    val externalId: String? = null,
    val currency: String = "USD",
    val apiKey: String? = null,
    val apiSecret: String? = null,
    val walletAddress: String? = null,
    val chain: String? = null,
)

@Service
class CreateAccountUseCase(private val repository: AccountRepository) {
    @Transactional
    fun execute(cmd: CreateAccountCommand): Account =
        repository.save(
            Account.create(
                userId        = cmd.userId,
                provider      = cmd.provider,
                accountType   = cmd.accountType,
                accountName   = cmd.accountName,
                externalId    = cmd.externalId,
                currency      = cmd.currency,
                apiKey        = cmd.apiKey,
                apiSecret     = cmd.apiSecret,
                walletAddress = cmd.walletAddress,
                chain         = cmd.chain,
            )
        )
}
