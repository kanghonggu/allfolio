package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.domain.cashflow.FlowType
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@Service
class RecordCashFlowUseCase(
    private val repository: CashFlowRepository,
    private val fxConverter: FxConverter,
) {
    fun record(
        userId: UUID, accountId: UUID?, flowDate: LocalDate, type: FlowType,
        amount: BigDecimal, currency: String, memo: String?,
    ): CashFlow {
        require(amount > BigDecimal.ZERO) { "입출금 금액은 양수여야 합니다" }
        val amountKrw = fxConverter.toKrw(amount, currency)
        return repository.save(
            CashFlow.create(userId, accountId, flowDate, type, amount, currency, amountKrw, memo)
        )
    }
}
