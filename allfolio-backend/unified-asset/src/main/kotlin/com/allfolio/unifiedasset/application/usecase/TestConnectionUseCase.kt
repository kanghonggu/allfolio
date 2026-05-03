package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.SyncAdapter
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.account.AccountType
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class TestConnectionUseCase(adapters: List<SyncAdapter>) {
    private val adapterMap = adapters.associateBy { it.supportedProvider }

    fun execute(
        provider:   AccountProvider,
        apiKey:     String,
        apiSecret:  String,
        passphrase: String? = null,
    ): ConnectionTestResult {
        val adapter = adapterMap[provider]
            ?: return ConnectionTestResult(false, "지원하지 않는 거래소입니다.")

        val tempAccount = Account.create(
            userId      = UUID.randomUUID(),
            provider    = provider,
            accountType = AccountType.EXCHANGE,
            accountName = "test",
            apiKey      = apiKey.trim(),
            apiSecret   = apiSecret.trim(),
            chain       = passphrase?.trim(),
        )
        return adapter.testConnection(tempAccount)
    }
}
