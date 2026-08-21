package com.allfolio.report.domain.archive

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
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
                // 컬럼이 존 없는 `TIMESTAMP`라 여기 적히는 벽시계가 곧 DB에 앉는 값이다. 읽는 쪽은
                // 그 값을 UTC로 전제하고 오프셋을 다는데, 존을 안 쓰면 그 전제가 호스트 TZ에 기댄
                // 우연이 된다 — 운영(Render) 컨테이너가 UTC라 맞았을 뿐, KST 호스트에서 돌리면
                // 9시간 미래가 UTC인 척 저장된다. 존을 명시해 우연을 규칙으로 바꾼다.
                // 운영이 이미 UTC라 저장값 자체는 안 변한다 — 마이그레이션 불필요.
                createdAt = LocalDateTime.now(ZoneOffset.UTC),
            )
        }

        fun reconstruct(
            id: UUID, userId: UUID, type: ReportType, period: ReportPeriod, asOfDate: LocalDate,
            status: ReportStatus, warnings: List<ReportWarning>, bodyJson: String, createdAt: LocalDateTime,
        ) = ReportArchive(id, userId, type, period, asOfDate, status, warnings, bodyJson, createdAt)
    }
}
