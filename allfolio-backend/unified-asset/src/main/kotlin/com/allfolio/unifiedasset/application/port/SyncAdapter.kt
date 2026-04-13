package com.allfolio.unifiedasset.application.port

import com.allfolio.unifiedasset.domain.account.Account
import com.allfolio.unifiedasset.domain.account.AccountProvider
import com.allfolio.unifiedasset.domain.asset.Asset

/**
 * 각 provider별 Adapter가 구현해야 할 포트
 * Account 정보를 받아 Asset 목록을 반환
 */
interface SyncAdapter {
    val supportedProvider: AccountProvider
    fun sync(account: Account): List<Asset>
}
