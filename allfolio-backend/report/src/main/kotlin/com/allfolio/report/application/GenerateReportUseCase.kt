package com.allfolio.report.application

import com.allfolio.report.domain.archive.ReportArchive
import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.report.domain.archive.ReportType
import org.springframework.stereotype.Service
import java.util.UUID

class UnsupportedReportTypeException(type: ReportType) :
    RuntimeException("생성기가 등록되지 않은 리포트 타입입니다: $type")

@Service
class GenerateReportUseCase(
    generators: List<ReportBodyGenerator>,
    private val gate: ReportValidationGate,
    private val repository: ReportArchiveRepository,
) {
    private val generatorsByType: Map<ReportType, ReportBodyGenerator> =
        generators.associateBy { it.type }.also {
            require(it.size == generators.size) { "리포트 타입당 생성기는 하나여야 합니다" }
        }

    fun generate(userId: UUID, type: ReportType, period: ReportPeriod): ReportArchive {
        val generator = generatorsByType[type] ?: throw UnsupportedReportTypeException(type)
        val warnings = gate.check(userId, period)
        val generated = generator.generate(userId, period)
        return repository.upsert(
            ReportArchive.create(
                userId   = userId,
                type     = type,
                period   = period,
                asOfDate = generated.asOfDate,
                warnings = warnings,
                bodyJson = generated.bodyJson,
            )
        )
    }
}
