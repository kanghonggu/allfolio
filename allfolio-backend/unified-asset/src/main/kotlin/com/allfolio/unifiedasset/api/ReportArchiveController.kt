package com.allfolio.unifiedasset.api

import com.allfolio.report.application.GenerateReportUseCase
import com.allfolio.report.application.ReportArchiveRepository
import com.allfolio.report.application.UnsupportedReportTypeException
import com.allfolio.report.domain.archive.ReportArchive
import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.report.domain.archive.ReportType
import com.allfolio.report.domain.archive.ReportWarning
import com.allfolio.unifiedasset.application.usecase.InsufficientDataException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

@RestController
@RequestMapping("/api/reports/archive")
class ReportArchiveController(
    private val generateReport: GenerateReportUseCase,
    private val archiveRepository: ReportArchiveRepository,
) {

    data class GenerateRequest(val type: ReportType, val year: Int, val month: Int)

    data class ArchiveMetaResponse(
        val id: UUID,
        val type: ReportType,
        val periodStart: LocalDate,
        val periodEnd: LocalDate,
        val asOfDate: LocalDate,
        val status: String,
        val warnings: List<ReportWarning>,
        val createdAt: LocalDateTime,
    )

    data class ArchiveDetailResponse(
        val meta: ArchiveMetaResponse,
        val body: String,   // 구조화 JSON 문자열 — 프론트에서 parse
    )

    @PostMapping("/generate")
    fun generate(
        @RequestHeader("X-User-Id") userId: UUID,
        @RequestBody request: GenerateRequest,
    ): ArchiveMetaResponse =
        generateReport.generate(userId, request.type, ReportPeriod.monthly(request.year, request.month)).toMeta()

    @GetMapping
    fun list(
        @RequestHeader("X-User-Id") userId: UUID,
        @RequestParam(required = false) type: ReportType?,
    ): List<ArchiveMetaResponse> =
        archiveRepository.findAll(userId, type).map { it.toMeta() }

    @GetMapping("/{id}")
    fun detail(
        @RequestHeader("X-User-Id") userId: UUID,
        @PathVariable id: UUID,
    ): ResponseEntity<ArchiveDetailResponse> {
        val archive = archiveRepository.findById(id)
        // 소유권 검증: 남의 아카이브는 존재 여부도 노출하지 않는다
        if (archive == null || archive.userId != userId) return ResponseEntity.notFound().build()
        return ResponseEntity.ok(ArchiveDetailResponse(meta = archive.toMeta(), body = archive.bodyJson))
    }

    @ExceptionHandler(UnsupportedReportTypeException::class)
    fun unsupportedType(e: UnsupportedReportTypeException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to (e.message ?: "unsupported type")))

    @ExceptionHandler(InsufficientDataException::class)
    fun insufficientData(e: InsufficientDataException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to (e.message ?: "insufficient data")))

    private fun ReportArchive.toMeta() = ArchiveMetaResponse(
        id          = id,
        type        = type,
        periodStart = period.start,
        periodEnd   = period.end,
        asOfDate    = asOfDate,
        status      = status.name,
        warnings    = warnings,
        createdAt   = createdAt,
    )
}
