package com.allfolio.api.admin

import com.allfolio.workflow.application.WfClosingQueryService
import com.allfolio.workflow.application.WfDayDetail
import com.allfolio.workflow.application.WfJobLogView
import com.allfolio.workflow.application.WfMonthView
import com.allfolio.workflow.application.WfRunSummary
import com.allfolio.workflow.application.WfStepExecutor
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * 마감 관제 API (P3 #23, SCR-DASH-01/02/05) — /api/admin 하위 전체 hasRole(ADMIN) 게이트.
 * 자정 자동 트리거 편입(#24)·SSE(#31)는 PR C.
 */
@RestController
@RequestMapping("/api/admin/closing")
class ClosingAdminController(
    private val stepExecutor: WfStepExecutor,
    private val queryService: WfClosingQueryService,
) {
    @GetMapping("/dashboard")
    fun dashboard(@RequestParam month: String): WfMonthView =
        queryService.monthView(YearMonth.parse(month))

    @GetMapping("/days/{ymd}")
    fun dayDetail(
        @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) ymd: LocalDate,
    ): WfDayDetail = queryService.dayDetail(ymd)

    /** 당일 워크플로우 (재)실행 — 락 경합 시 409. */
    @PostMapping("/days/{ymd}/run")
    fun runDay(
        @RequestHeader("X-User-Id") adminId: UUID,
        @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) ymd: LocalDate,
    ): WfRunSummary = stepExecutor.runDaily(ymd, executor = adminId.toString())

    /** 개별 하위단계 재실행(재작업 차수 증가). */
    @PostMapping("/days/{ymd}/steps/{stepCd}/substeps/{subStepCd}/run")
    fun runSubStep(
        @RequestHeader("X-User-Id") adminId: UUID,
        @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) ymd: LocalDate,
        @PathVariable stepCd: String,
        @PathVariable subStepCd: String,
    ): Map<String, String> =
        mapOf("status" to stepExecutor.runSubStep(ymd, stepCd, subStepCd, executor = adminId.toString()).name)

    /** 수동 성공/실패 처리 — 사유 필수. */
    @PostMapping("/days/{ymd}/steps/{stepCd}/substeps/{subStepCd}/manual")
    fun manualComplete(
        @RequestHeader("X-User-Id") adminId: UUID,
        @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) ymd: LocalDate,
        @PathVariable stepCd: String,
        @PathVariable subStepCd: String,
        @RequestBody req: ManualCompleteRequest,
    ) {
        stepExecutor.manualComplete(
            ymd, stepCd, subStepCd,
            success = req.result == "SUCCESS", remark = req.remark, executor = adminId.toString(),
        )
    }

    /** 재작업 로그 (차수≥2). */
    @GetMapping("/jobs/rework")
    fun reworkLogs(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) ymd: LocalDate,
    ): List<WfJobLogView> = queryService.reworkLogs(ymd)

    @GetMapping("/holidays")
    fun holidays(@RequestParam year: Int): Map<String, String?> = queryService.holidays(year)
}

data class ManualCompleteRequest(val result: String, val remark: String)
