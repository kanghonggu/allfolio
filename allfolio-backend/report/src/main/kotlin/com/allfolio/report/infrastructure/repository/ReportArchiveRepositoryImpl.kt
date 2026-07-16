package com.allfolio.report.infrastructure.repository

import com.allfolio.report.application.ReportArchiveRepository
import com.allfolio.report.domain.archive.ReportArchive
import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.report.domain.archive.ReportStatus
import com.allfolio.report.domain.archive.ReportType
import com.allfolio.report.infrastructure.entity.ReportArchiveEntity
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Repository
class ReportArchiveRepositoryImpl(
    private val jpa: ReportArchiveJpaRepository,
) : ReportArchiveRepository {

    private val mapper = jacksonObjectMapper()

    @Transactional
    override fun upsert(archive: ReportArchive): ReportArchive {
        // (userId, type, period) 유니크 — 기존 행이 있으면 같은 id로 덮어쓴다
        val existing = jpa.findByUserIdAndReportTypeAndPeriodStartAndPeriodEnd(
            archive.userId, archive.type.name, archive.period.start, archive.period.end,
        )
        val entity = ReportArchiveEntity(
            id          = existing?.id ?: archive.id,
            userId      = archive.userId,
            reportType  = archive.type.name,
            periodStart = archive.period.start,
            periodEnd   = archive.period.end,
            asOfDate    = archive.asOfDate,
            status      = archive.status.name,
            warnings    = mapper.writeValueAsString(archive.warnings),
            body        = archive.bodyJson,
            createdAt   = archive.createdAt,
        )
        return jpa.save(entity).toDomain()
    }

    @Transactional(readOnly = true)
    override fun findById(id: UUID): ReportArchive? =
        jpa.findById(id).orElse(null)?.toDomain()

    @Transactional(readOnly = true)
    override fun findAll(userId: UUID, type: ReportType?): List<ReportArchive> =
        (if (type == null) jpa.findByUserIdOrderByPeriodEndDesc(userId)
         else jpa.findByUserIdAndReportTypeOrderByPeriodEndDesc(userId, type.name))
            .map { it.toDomain() }

    private fun ReportArchiveEntity.toDomain() = ReportArchive.reconstruct(
        id        = id,
        userId    = userId,
        type      = ReportType.valueOf(reportType),
        period    = ReportPeriod(periodStart, periodEnd),
        asOfDate  = asOfDate,
        status    = ReportStatus.valueOf(status),
        warnings  = mapper.readValue(warnings),
        bodyJson  = body,
        createdAt = createdAt,
    )
}
