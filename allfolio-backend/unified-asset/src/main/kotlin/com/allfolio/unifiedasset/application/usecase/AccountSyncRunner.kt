package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.domain.sync.SyncTrigger
import java.util.UUID

/**
 * 단일 계좌 동기화 실행 seam.
 * DailyAccountSyncer가 concrete SyncAccountUseCase 대신 이 인터페이스에 의존해
 * 테스트에서 fake로 대체 가능하게 한다. 유일 구현체는 SyncAccountUseCase.
 */
interface AccountSyncRunner {
    fun execute(accountId: UUID, trigger: SyncTrigger = SyncTrigger.MANUAL): SyncResult
}
