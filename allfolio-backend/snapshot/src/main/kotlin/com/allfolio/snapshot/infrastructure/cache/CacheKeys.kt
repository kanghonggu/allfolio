package com.allfolio.snapshot.infrastructure.cache

import java.time.LocalDate
import java.util.UUID

object CacheKeys {
    private const val NS = "snapshot"

    /** snapshot:{tenantId}:{portfolioId}:{date} */
    fun snapshot(tenantId: UUID, portfolioId: UUID, date: LocalDate): String =
        "$NS:$tenantId:$portfolioId:$date"

    /** snapshot:{tenantId}:{portfolioId}:latest */
    fun latest(tenantId: UUID, portfolioId: UUID): String =
        "$NS:$tenantId:$portfolioId:latest"
}
