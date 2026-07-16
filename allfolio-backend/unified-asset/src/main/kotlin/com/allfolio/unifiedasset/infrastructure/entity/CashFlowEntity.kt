package com.allfolio.unifiedasset.infrastructure.entity

import com.allfolio.unifiedasset.domain.cashflow.CashFlow
import com.allfolio.unifiedasset.domain.cashflow.FlowType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "cash_flow")
class CashFlowEntity(
    @Id val id: UUID,
    @Column(name = "user_id", nullable = false) val userId: UUID,
    @Column(name = "account_id") val accountId: UUID?,
    @Column(name = "flow_date", nullable = false) val flowDate: LocalDate,
    @Enumerated(EnumType.STRING)
    @Column(name = "flow_type", nullable = false, length = 20) val flowType: FlowType,
    @Column(name = "amount", nullable = false, precision = 30, scale = 10) val amount: BigDecimal,
    @Column(name = "currency", nullable = false, length = 10) val currency: String,
    @Column(name = "amount_krw", nullable = false, precision = 30, scale = 10) val amountKrw: BigDecimal,
    @Column(name = "memo", length = 500) val memo: String?,
    @Column(name = "created_at", nullable = false) val createdAt: LocalDateTime,
) {
    fun toDomain(): CashFlow = CashFlow.reconstruct(
        id, userId, accountId, flowDate, flowType, amount, currency, amountKrw, memo, createdAt,
    )

    companion object {
        fun from(domain: CashFlow) = CashFlowEntity(
            id = domain.id, userId = domain.userId, accountId = domain.accountId,
            flowDate = domain.flowDate, flowType = domain.type, amount = domain.amount,
            currency = domain.currency, amountKrw = domain.amountKrw,
            memo = domain.memo, createdAt = domain.createdAt,
        )
    }
}
