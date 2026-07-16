package com.allfolio.unifiedasset.domain.cashflow

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

enum class FlowType { DEPOSIT, WITHDRAWAL }

class CashFlow private constructor(
    val id: UUID,
    val userId: UUID,
    val accountId: UUID?,
    val flowDate: LocalDate,
    val type: FlowType,
    val amount: BigDecimal,      // 원통화, 양수
    val currency: String,
    val amountKrw: BigDecimal,   // 기록 시점 환율 고정 (as-of 재현성)
    val memo: String?,
    val createdAt: LocalDateTime,
) {
    /** TWR/MWR 입력용 부호 금액: 입금 +, 출금 − */
    fun signedKrw(): BigDecimal = if (type == FlowType.DEPOSIT) amountKrw else amountKrw.negate()

    companion object {
        fun create(
            userId: UUID, accountId: UUID?, flowDate: LocalDate, type: FlowType,
            amount: BigDecimal, currency: String, amountKrw: BigDecimal, memo: String?,
        ): CashFlow {
            require(amount > BigDecimal.ZERO) { "입출금 금액은 양수여야 합니다" }
            return CashFlow(
                id = UUID.randomUUID(), userId = userId, accountId = accountId,
                flowDate = flowDate, type = type, amount = amount,
                currency = currency.uppercase(), amountKrw = amountKrw,
                memo = memo?.trim(), createdAt = LocalDateTime.now(),
            )
        }

        fun reconstruct(
            id: UUID, userId: UUID, accountId: UUID?, flowDate: LocalDate, type: FlowType,
            amount: BigDecimal, currency: String, amountKrw: BigDecimal, memo: String?, createdAt: LocalDateTime,
        ) = CashFlow(id, userId, accountId, flowDate, type, amount, currency, amountKrw, memo, createdAt)
    }
}
