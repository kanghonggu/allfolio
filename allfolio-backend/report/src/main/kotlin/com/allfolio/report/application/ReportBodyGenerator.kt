package com.allfolio.report.application

import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.report.domain.archive.ReportType
import java.time.LocalDate
import java.util.UUID

/** 리포트 엔진(#33 수익률, #36 월간 등)이 구현하는 생성기 포트. 스프링 빈으로 등록만 하면 프레임에 꽂힌다. */
interface ReportBodyGenerator {
    val type: ReportType
    fun generate(userId: UUID, period: ReportPeriod): GeneratedReport
}

data class GeneratedReport(
    val asOfDate: LocalDate,   // 생성에 사용된 스냅샷 최종일 — 아카이브에 고정
    val bodyJson: String,      // 구조화 본문 (웹/PDF 공용)
)
