package com.allfolio.workflow.application

import java.time.LocalDate

data class WfContext(val ymd: LocalDate)

/** 액션 실행 결과 — summary는 job_log.remark에 기록(예: "synced=3 failed=0"). */
data class WfActionResult(val summary: String? = null)

enum class WfPollStatus { IN_PROGRESS, DONE, FAILED }

/**
 * 마감 하위단계 실행 액션 계약 (P3 #23, 스펙 §실행기) — 액션은 코드(Spring 빈).
 * wf_sub_step.action_ref 문자열로 매칭(ReconRule·SyncAdapter와 동일 OCP 패턴).
 * 멱등성은 각 액션이 보장한다(재작업 차수 재실행 대비).
 */
interface WfAction {
    val ref: String

    /** CHAIN: 동기 실행·완료. POLL: 작업 시작만 하고 poll()로 상태 확인. 예외 = ERROR. */
    fun execute(ctx: WfContext): WfActionResult
}

/** POLL형 액션 — execute()로 시작 후 poll_interval 간격으로 상태 확인, timeout 초과 시 ERROR. */
interface WfPollAction : WfAction {
    fun poll(ctx: WfContext): WfPollStatus
}

/** 워크플로우 실행 락 포트 — wf:lock:{ymd}. backend-app이 Redis로 구현. */
interface WfLockPort {
    fun tryAcquire(ymd: LocalDate): String?
    fun release(ymd: LocalDate, token: String)
}

/** 같은 일자 워크플로우가 이미 실행 중 — API에서 409로 매핑. */
class ClosingInProgressException(message: String) : RuntimeException(message)
