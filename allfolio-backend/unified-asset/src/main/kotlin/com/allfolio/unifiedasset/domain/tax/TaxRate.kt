package com.allfolio.unifiedasset.domain.tax

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/** 원천징수 세율 1버전. effectiveEnd == null 이면 현행(open). rate는 퍼센트값(예 15.315). */
data class TaxRate(
    val id: UUID,
    val country: String,          // ISO alpha-2
    val incomeType: IncomeType,
    val rate: BigDecimal,
    val effectiveStart: LocalDate,
    val effectiveEnd: LocalDate?,
    val updatedBy: UUID?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)
