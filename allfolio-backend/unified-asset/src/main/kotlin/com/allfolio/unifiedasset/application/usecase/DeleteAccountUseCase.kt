package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.port.StockTradeRepository
import com.allfolio.unifiedasset.application.port.SyncLogRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 계좌 1건 삭제 오케스트레이터.
 * 자식 레코드(자산·거래내역·현금흐름·동기화 로그)를 먼저 지우고 계좌를 마지막에 지운다.
 * 전체를 한 트랜잭션으로 묶어 중간 실패 시 부분 삭제가 남지 않게 한다.
 * (소유권 검증은 호출부에서 끝낸 뒤 진입한다.)
 */
@Service
class DeleteAccountUseCase(
    private val accountRepository: AccountRepository,
    private val assetRepository: AssetRepository,
    private val stockTradeRepository: StockTradeRepository,
    private val cashFlowRepository: CashFlowRepository,
    private val syncLogRepository: SyncLogRepository,
) {
    @Transactional
    fun execute(accountId: UUID) {
        assetRepository.deleteByAccountId(accountId)
        stockTradeRepository.deleteByAccountId(accountId)
        cashFlowRepository.deleteByAccountId(accountId)
        syncLogRepository.deleteByAccountId(accountId)
        accountRepository.delete(accountId)
    }
}
