package com.allfolio.unifiedasset.infrastructure.adapter

import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.SyncAdapter
import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.asset.Asset
import org.springframework.stereotype.Component

/**
 * 수동 계좌는 사용자가 직접 Asset을 생성하므로 sync 시 기존 데이터 유지
 */
@Component
class ManualSyncAdapter(private val assetRepository: AssetRepository) : SyncAdapter {
    override val supportedProvider = AccountProvider.MANUAL

    override fun sync(account: Account): List<Asset> {
        // 수동 계좌는 sync 불필요 → 기존 자산 그대로 반환
        return assetRepository.findByAccountId(account.id)
    }
}
