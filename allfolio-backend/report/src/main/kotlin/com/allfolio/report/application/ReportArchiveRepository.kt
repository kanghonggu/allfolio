package com.allfolio.report.application

import com.allfolio.report.domain.archive.ReportArchive
import com.allfolio.report.domain.archive.ReportType
import java.util.UUID

interface ReportArchiveRepository {
    /** (userId, type, period) 유니크 — 재생성 시 덮어쓴다 */
    fun upsert(archive: ReportArchive): ReportArchive
    fun findById(id: UUID): ReportArchive?
    fun findAll(userId: UUID, type: ReportType? = null): List<ReportArchive>
}
