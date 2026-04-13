package com.allfolio.unifiedasset.infrastructure.adapter

import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.SyncAdapter
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.asset.Asset
import org.springframework.stereotype.Component

/**
 * CSV 계좌는 ImportCsvUseCase를 통해 데이터를 적재하므로 sync 시 기존 데이터 유지
 */
@Component
class CsvSyncAdapter(private val assetRepository: AssetRepository) : SyncAdapter {
    override val supportedProvider = AccountProvider.CSV

    override fun sync(account: Account): List<Asset> =
        assetRepository.findByAccountId(account.id)
}
