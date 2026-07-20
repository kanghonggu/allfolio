package com.allfolio.report.domain.archive

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

enum class ReportStatus { FINAL, WARNING }

data class ReportWarning(val code: String, val message: String)

class ReportArchive private constructor(
    val id: UUID,
    val userId: UUID,
    val type: ReportType,
    val period: ReportPeriod,
    val asOfDate: LocalDate,
    val status: ReportStatus,
    val warnings: List<ReportWarning>,
    val bodyJson: String,
    val createdAt: LocalDateTime,
) {
    companion object {
        fun create(
            userId: UUID,
            type: ReportType,
            period: ReportPeriod,
            asOfDate: LocalDate,
            warnings: List<ReportWarning>,
            bodyJson: String,
        ): ReportArchive {
            require(bodyJson.isNotBlank()) { "리포트 본문은 비어 있을 수 없습니다" }
            return ReportArchive(
                id        = UUID.randomUUID(),
                userId    = userId,
                type      = type,
                period    = period,
                asOfDate  = asOfDate,
                status    = if (warnings.isEmpty()) ReportStatus.FINAL else ReportStatus.WARNING,
                warnings  = warnings,
                bodyJson  = bodyJson,
                createdAt = LocalDateTime.now(),
            )
        }

        fun reconstruct(
            id: UUID, userId: UUID, type: ReportType, period: ReportPeriod, asOfDate: LocalDate,
            status: ReportStatus, warnings: List<ReportWarning>, bodyJson: String, createdAt: LocalDateTime,
        ) = ReportArchive(id, userId, type, period, asOfDate, status, warnings, bodyJson, createdAt)
    }
}
