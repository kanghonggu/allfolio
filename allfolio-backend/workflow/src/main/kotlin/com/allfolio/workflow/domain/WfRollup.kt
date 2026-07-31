package com.allfolio.workflow.domain

/**
 * 단계 상태 롤업 (기능명세서 5.3, 우선순위 순) — 순수 로직.
 * 입력: 마감판정(closing_check_yn) 대상 하위단계들의 최신 차수 상태(null=로그 없음).
 */
object WfRollup {
    fun rollup(statuses: List<WfJobStatus?>): WfStepRollup = when {
        statuses.isEmpty() -> WfStepRollup.FINISH
        statuses.all { it == null || it == WfJobStatus.PENDING } -> WfStepRollup.STANDBY
        statuses.all { it == WfJobStatus.SUCCESS } -> WfStepRollup.FINISH
        statuses.any { it == WfJobStatus.ERROR } -> WfStepRollup.ERROR
        statuses.any { it == WfJobStatus.RUNNING } -> WfStepRollup.RUNNING
        else -> WfStepRollup.PAUSED
    }
}
