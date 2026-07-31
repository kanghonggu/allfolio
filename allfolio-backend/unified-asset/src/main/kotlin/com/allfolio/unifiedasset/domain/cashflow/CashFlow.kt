package com.allfolio.unifiedasset.domain.cashflow

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

enum class FlowType {
    DEPOSIT, WITHDRAWAL, TRANSFER_IN, TRANSFER_OUT, FX_IN, FX_OUT;

    fun isInternal(): Boolean = this in setOf(TRANSFER_IN, TRANSFER_OUT, FX_IN, FX_OUT)
    fun isInflow(): Boolean = this in setOf(DEPOSIT, TRANSFER_IN, FX_IN)
    fun isOutflow(): Boolean = this in setOf(WITHDRAWAL, TRANSFER_OUT, FX_OUT)
}

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
    val linkId: UUID?,
) {
    /** TWR/MWR 입력용 부호 금액: 입금 +, 출금 −, 내부이동은 0(외부 기여 아님) */
    fun signedKrw(): BigDecimal = when {
        type == FlowType.DEPOSIT -> amountKrw
        type == FlowType.WITHDRAWAL -> amountKrw.negate()
        else -> BigDecimal.ZERO
    }

    companion object {
        fun create(
            userId: UUID, accountId: UUID?, flowDate: LocalDate, type: FlowType,
            amount: BigDecimal, currency: String, amountKrw: BigDecimal, memo: String?,
            linkId: UUID? = null,
        ): CashFlow {
            require(amount > BigDecimal.ZERO) { "입출금 금액은 양수여야 합니다" }
            return CashFlow(
                id = UUID.randomUUID(), userId = userId, accountId = accountId,
                flowDate = flowDate, type = type, amount = amount,
                currency = currency.uppercase(), amountKrw = amountKrw,
                memo = memo?.trim(), createdAt = LocalDateTime.now(),
                linkId = linkId,
            )
        }

        fun reconstruct(
            id: UUID, userId: UUID, accountId: UUID?, flowDate: LocalDate, type: FlowType,
            amount: BigDecimal, currency: String, amountKrw: BigDecimal, memo: String?, createdAt: LocalDateTime,
            linkId: UUID? = null,
        ) = CashFlow(id, userId, accountId, flowDate, type, amount, currency, amountKrw, memo, createdAt, linkId)

        fun transferPair(
            userId: UUID, fromAccountId: UUID, toAccountId: UUID, flowDate: LocalDate,
            amount: BigDecimal, currency: String, amountKrw: BigDecimal, memo: String?,
        ): Pair<CashFlow, CashFlow> {
            require(fromAccountId != toAccountId) { "이체 출발·도착 계좌가 같을 수 없습니다" }
            val link = UUID.randomUUID()
            return create(userId, fromAccountId, flowDate, FlowType.TRANSFER_OUT, amount, currency, amountKrw, memo, link) to
                create(userId, toAccountId, flowDate, FlowType.TRANSFER_IN, amount, currency, amountKrw, memo, link)
        }

        fun fxPair(
            userId: UUID, accountId: UUID?, flowDate: LocalDate,
            fromAmount: BigDecimal, fromCurrency: String, fromAmountKrw: BigDecimal,
            toAmount: BigDecimal, toCurrency: String, toAmountKrw: BigDecimal, memo: String?,
        ): Pair<CashFlow, CashFlow> {
            require(fromCurrency.uppercase() != toCurrency.uppercase()) { "환전 통화가 같을 수 없습니다" }
            val link = UUID.randomUUID()
            return create(userId, accountId, flowDate, FlowType.FX_OUT, fromAmount, fromCurrency, fromAmountKrw, memo, link) to
                create(userId, accountId, flowDate, FlowType.FX_IN, toAmount, toCurrency, toAmountKrw, memo, link)
        }
    }
}
