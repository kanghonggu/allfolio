package com.allfolio.report.infrastructure.repository

import com.allfolio.report.infrastructure.entity.ReportArchiveEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.UUID

interface ReportArchiveJpaRepository : JpaRepository<ReportArchiveEntity, UUID> {
    fun findByUserIdAndReportTypeAndPeriodStartAndPeriodEnd(
        userId: UUID, reportType: String, periodStart: LocalDate, periodEnd: LocalDate,
    ): ReportArchiveEntity?

    fun findByUserIdOrderByPeriodEndDesc(userId: UUID): List<ReportArchiveEntity>
    fun findByUserIdAndReportTypeOrderByPeriodEndDesc(userId: UUID, reportType: String): List<ReportArchiveEntity>
}
