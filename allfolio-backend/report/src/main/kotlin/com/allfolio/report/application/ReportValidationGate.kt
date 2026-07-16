package com.allfolio.report.application

import com.allfolio.report.domain.archive.ReportPeriod
import com.allfolio.report.domain.archive.ReportWarning
import java.util.UUID

/** 검증 게이트 (명세 §0): 신뢰할 수 없는 데이터로 생성되는 보고서에 경고를 부여한다. */
interface ReportValidationGate {
    fun check(userId: UUID, period: ReportPeriod): List<ReportWarning>
}
