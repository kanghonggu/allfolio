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
        requireNotFuture(flowDate)
        val cur = com.allfolio.unifiedasset.domain.common.Currencies.normalize(currency)
        val krw = fx.toKrw(amount, cur)
        val (out, inn) = CashFlow.transferPair(userId, fromAccountId, toAccountId, flowDate, amount, cur, krw, memo)
        return listOf(repository.save(out), repository.save(inn))
    }

    @Transactional
    fun recordFx(
        userId: UUID, accountId: UUID?, flowDate: LocalDate,
        fromAmount: BigDecimal, fromCurrency: String,
        toAmount: BigDecimal, toCurrency: String, memo: String?,
        toAccountId: UUID? = null,   // null이면 동일 계좌, 지정 시 계좌간 환전
    ): List<CashFlow> {
        require(fromAmount > BigDecimal.ZERO && toAmount > BigDecimal.ZERO) { "환전 금액은 양수여야 합니다" }
        requireNotFuture(flowDate)
        val fromCur = com.allfolio.unifiedasset.domain.common.Currencies.normalize(fromCurrency)
        val toCur = com.allfolio.unifiedasset.domain.common.Currencies.normalize(toCurrency)
        val (out, inn) = CashFlow.fxPair(
            userId, accountId, flowDate,
            fromAmount, fromCur, fx.toKrw(fromAmount, fromCur),
            toAmount, toCur, fx.toKrw(toAmount, toCur), memo,
            toAccountId = toAccountId,
        )
        return listOf(repository.save(out), repository.save(inn))
    }

    private fun requireNotFuture(flowDate: LocalDate) =
        require(!flowDate.isAfter(LocalDate.now(java.time.ZoneId.of("Asia/Seoul")))) { "미래 날짜는 등록할 수 없습니다" }
}
