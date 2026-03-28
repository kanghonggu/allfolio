package com.allfolio.snapshot.infrastructure.entity

import jakarta.persistence.Embeddable
import java.io.Serializable
import java.time.LocalDate
import java.util.UUID

/**
 * Performance / Risk Daily Entity 공통 복합 PK
 * (tenantId, portfolioId, date)
 */
@Embeddable
data class SnapshotDailyId(
    val tenantId: UUID,
    val portfolioId: UUID,
    val date: LocalDate,
) : Serializable
