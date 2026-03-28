package com.allfolio.snapshot.infrastructure.entity

import jakarta.persistence.Embeddable
import java.io.Serializable
import java.time.LocalDate
import java.util.UUID

@Embeddable
data class PositionDailyId(
    val tenantId: UUID,
    val portfolioId: UUID,
    val assetId: UUID,
    val date: LocalDate,
) : Serializable
