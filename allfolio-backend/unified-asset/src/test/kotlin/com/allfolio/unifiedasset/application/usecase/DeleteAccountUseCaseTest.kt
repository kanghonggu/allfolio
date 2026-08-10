package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.AccountRepository
import com.allfolio.unifiedasset.application.port.AssetRepository
import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.port.StockTradeRepository
import com.allfolio.unifiedasset.application.port.SyncLogRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertNotNull
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

class DeleteAccountUseCaseTest {

    private val accountRepository = mock(AccountRepository::class.java)
    private val assetRepository = mock(AssetRepository::class.java)
    private val stockTradeRepository = mock(StockTradeRepository::class.java)
    private val cashFlowRepository = mock(CashFlowRepository::class.java)
    private val syncLogRepository = mock(SyncLogRepository::class.java)

    private val useCase = DeleteAccountUseCase(
        accountRepository,
        assetRepository,
        stockTradeRepository,
        cashFlowRepository,
        syncLogRepository,
    )

    @Test
    fun `계좌 삭제는 자산 거래내역 현금흐름 동기화로그를 모두 지운다`() {
        val accountId = UUID.randomUUID()

        useCase.execute(accountId)

        verify(assetRepository).deleteByAccountId(accountId)
        verify(stockTradeRepository).deleteByAccountId(accountId)
        verify(cashFlowRepository).deleteByAccountId(accountId)
        verify(syncLogRepository).deleteByAccountId(accountId)
        verify(accountRepository).delete(accountId)
    }

    @Test
    fun `계좌 레코드는 자식 레코드를 모두 지운 뒤 마지막에 삭제한다`() {
        val accountId = UUID.randomUUID()

        useCase.execute(accountId)

        val ordered = inOrder(
            assetRepository,
            stockTradeRepository,
            cashFlowRepository,
            syncLogRepository,
            accountRepository,
        )
        ordered.verify(assetRepository).deleteByAccountId(accountId)
        ordered.verify(stockTradeRepository).deleteByAccountId(accountId)
        ordered.verify(cashFlowRepository).deleteByAccountId(accountId)
        ordered.verify(syncLogRepository).deleteByAccountId(accountId)
        ordered.verify(accountRepository).delete(accountId)
    }

    @Test
    fun `execute 는 단일 트랜잭션으로 실행된다 (중간 실패 시 부분 삭제 방지)`() {
        val method = DeleteAccountUseCase::class.java.getDeclaredMethod("execute", UUID::class.java)

        assertNotNull(method.getAnnotation(Transactional::class.java))
    }
}
