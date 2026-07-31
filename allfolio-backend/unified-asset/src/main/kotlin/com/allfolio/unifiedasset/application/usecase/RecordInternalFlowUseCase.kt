package com.allfolio.unifiedasset.application.usecase

import com.allfolio.unifiedasset.application.port.CashFlowRepository
import com.allfolio.unifiedasset.application.port.FxConverter
import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@Service
class RecordInternalFlowUseCase(
    private val repository: CashFlowRepository,
    private val fx: FxConverter,
) {
    @Transactional
    fun recordTransfer(
        userId: UUID, fromAccountId: UUID, toAccountId: UUID, flowDate: LocalDate,
        amount: BigDecimal, currency: String, memo: String?,
    ): List<CashFlow> {
        require(amount > BigDecimal.ZERO) { "이체 금액은 양수여야 합니다" }
        val krw = fx.toKrw(amount, currency)
        val (out, inn) = CashFlow.transferPair(userId, fromAccountId, toAccountId, flowDate, amount, currency, krw, memo)
        return listOf(repository.save(out), repository.save(inn))
    }

    @Transactional
    fun recordFx(
        userId: UUID, accountId: UUID?, flowDate: LocalDate,
        fromAmount: BigDecimal, fromCurrency: String,
        toAmount: BigDecimal, toCurrency: String, memo: String?,
    ): List<CashFlow> {
        require(fromAmount > BigDecimal.ZERO && toAmount > BigDecimal.ZERO) { "환전 금액은 양수여야 합니다" }
        val (out, inn) = CashFlow.fxPair(
            userId, accountId, flowDate,
            fromAmount, fromCurrency, fx.toKrw(fromAmount, fromCurrency),
            toAmount, toCurrency, fx.toKrw(toAmount, toCurrency), memo,
        )
        return listOf(repository.save(out), repository.save(inn))
    }
}
