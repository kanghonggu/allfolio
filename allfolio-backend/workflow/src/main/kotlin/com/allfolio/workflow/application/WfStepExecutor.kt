package com.allfolio.workflow.application

import com.allfolio.workflow.domain.BizDayCalculator
import com.allfolio.workflow.domain.WfActionType
import com.allfolio.workflow.domain.WfJobStatus
import com.allfolio.workflow.domain.WfRollup
import com.allfolio.workflow.domain.WfScheduleJudge
import com.allfolio.workflow.domain.WfStepRollup
import com.allfolio.workflow.infrastructure.entity.WfJobLogEntity
import com.allfolio.workflow.infrastructure.entity.WfStepEntity
import com.allfolio.workflow.infrastructure.entity.WfSubStepEntity
import com.allfolio.workflow.infrastructure.jpa.WfHolidayJpaRepository
import com.allfolio.workflow.infrastructure.jpa.WfJobLogJpaRepository
import com.allfolio.workflow.infrastructure.jpa.WfStepJpaRepository
import com.allfolio.workflow.infrastructure.jpa.WfSubStepJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime

data class WfRunSummary(
    val ymd: LocalDate,
    val executedSteps: List<String>,
    val gateSkippedSteps: List<String>,
    val notScheduledSteps: List<String>,
)

/**
 * 마감 단계 실행기 (P3 #23, FR-STEP-001~008).
 *
 * - runDaily: 일자 워크플로우 실행 — wf:lock:{ymd}로 중복 방지, 단계 seq 순.
 * - 선행단계 게이트: essential_step_cd 당일 롤업 FINISH 아니면 단계 SKIP(다음 트리거에서 재시도).
 * - CHAIN 동기 실행 / POLL 간격 폴링·타임아웃 / MANUAL은 PENDING 로그만 만들고 수동 처리 대기.
 * - 하위단계 ERROR 시 같은 단계의 후속 하위단계 중단.
 * - 최신 차수 SUCCESS인 하위단계는 재실행하지 않음(멱등) — 명시적 재실행은 runSubStep(차수+1).
 */
@Service
class WfStepExecutor(
    private val actions: List<WfAction>,
    private val stepRepo: WfStepJpaRepository,
    private val subStepRepo: WfSubStepJpaRepository,
    private val jobLogRepo: WfJobLogJpaRepository,
    private val holidayRepo: WfHolidayJpaRepository,
    private val lock: WfLockPort,
    private val eventPublisher: WfEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val actionMap: Map<String, WfAction> by lazy { actions.associateBy { it.ref } }

    fun runDaily(ymd: LocalDate, executor: String = SYSTEM_EXECUTOR): WfRunSummary {
        val token = lock.tryAcquire(ymd)
            ?: throw ClosingInProgressException("해당 일자 마감 워크플로우가 이미 실행 중입니다: $ymd")
        try {
            return doRun(ymd, executor)
        } finally {
            lock.release(ymd, token)
        }
    }

    private fun doRun(ymd: LocalDate, executor: String): WfRunSummary {
        val judge = judgeFor(ymd)
        val executed = mutableListOf<String>()
        val gateSkipped = mutableListOf<String>()
        val notScheduled = mutableListOf<String>()

        stepRepo.findByUseYnTrueOrderByStepSeq().forEach { step ->
            val subs = scheduledSubs(step, ymd, judge)
            if (subs.isEmpty()) {
                notScheduled += step.stepCd
                return@forEach
            }
            if (!gatePassed(step, ymd, judge)) {
                gateSkipped += step.stepCd
                return@forEach
            }
            executed += step.stepCd
            runSubsInOrder(ymd, step, subs, executor)
        }
        return WfRunSummary(ymd, executed, gateSkipped, notScheduled)
    }

    /** 단계 내 하위단계 순차 실행 — ERROR/RUNNING/MANUAL 대기에서 중단. */
    private fun runSubsInOrder(ymd: LocalDate, step: WfStepEntity, subs: List<WfSubStepEntity>, executor: String) {
        for (sub in subs) {
            val latest = latestLog(ymd, sub)?.status
            when {
                latest == WfJobStatus.SUCCESS -> continue                   // 완료 — 멱등
                latest == WfJobStatus.RUNNING -> return                     // 이미 실행 중
                sub.actionType == WfActionType.MANUAL -> {
                    if (latest == null) createPendingLog(ymd, sub, executor)  // 수동 대기 로그 1회 생성
                    return                                                    // 수동 처리 전까지 후속 중단
                }
                else -> {
                    val result = runSub(ymd, sub, executor, autoManual = "A")
                    if (result != WfJobStatus.SUCCESS) return               // 오류 — 후속 중단
                }
            }
        }
    }

    /** 개별 하위단계 (재)실행 — 재작업 차수 +1 (FR-STEP-008). 운영자 수동 트리거용. */
    fun runSubStep(ymd: LocalDate, stepCd: String, subStepCd: String, executor: String): WfJobStatus {
        val sub = subStepRepo.findByIdStepCd(stepCd).find { it.id.subStepCd == subStepCd && it.useYn }
            ?: throw NoSuchElementException("하위단계 없음: $stepCd/$subStepCd")
        if (latestLog(ymd, sub)?.status == WfJobStatus.RUNNING) {
            throw ClosingInProgressException("해당 하위단계가 이미 실행 중입니다")
        }
        if (sub.actionType == WfActionType.MANUAL) {
            throw IllegalArgumentException("수동확인 단계는 수동 처리(manual)로만 완료할 수 있습니다")
        }
        return runSub(ymd, sub, executor, autoManual = "M")
    }

    /** 수동 성공/실패 처리 (FR-STEP-005·FR-AUTH-005) — 사유 필수, 새 차수 로그. */
    fun manualComplete(ymd: LocalDate, stepCd: String, subStepCd: String, success: Boolean, remark: String, executor: String) {
        require(remark.isNotBlank()) { "수동 처리 사유는 필수입니다" }
        val sub = subStepRepo.findByIdStepCd(stepCd).find { it.id.subStepCd == subStepCd }
            ?: throw NoSuchElementException("하위단계 없음: $stepCd/$subStepCd")
        val now = LocalDateTime.now()
        val saved = jobLogRepo.save(
            WfJobLogEntity(
                ymd = ymd, stepCd = stepCd, subStepCd = sub.id.subStepCd,
                execSeq = nextExecSeq(ymd, sub),
                status = if (success) WfJobStatus.SUCCESS else WfJobStatus.ERROR,
                startedAt = now, finishedAt = now,
                autoManual = "M", executor = executor, remark = remark.take(500),
            )
        )
        notify(saved)
    }

    /** 당일 단계 롤업 — 마감판정 대상·당일 실행 대상 하위단계의 최신 차수 상태 기준. */
    fun rollupOf(ymd: LocalDate, step: WfStepEntity, judge: WfScheduleJudge = judgeFor(ymd)): WfStepRollup {
        val statuses = scheduledSubs(step, ymd, judge)
            .filter { it.closingCheckYn }
            .map { latestLog(ymd, it)?.status }
        return WfRollup.rollup(statuses)
    }

    fun judgeFor(ymd: LocalDate): WfScheduleJudge {
        val holidays = holidayRepo.findByIdDayBetween(ymd.minusDays(370), ymd.plusDays(370))
            .map { it.id.day }.toSet()
        return WfScheduleJudge(BizDayCalculator(holidays))
    }

    fun scheduledSubs(step: WfStepEntity, ymd: LocalDate, judge: WfScheduleJudge): List<WfSubStepEntity> =
        subStepRepo.findByIdStepCdAndUseYnTrueOrderBySubStepSeq(step.stepCd)
            .filter { judge.shouldRun(step.termGb, step.holidayExceptYn, it.dateTerm, it.dateGb, ymd) }

    private fun gatePassed(step: WfStepEntity, ymd: LocalDate, judge: WfScheduleJudge): Boolean {
        val essentialCd = step.essentialStepCd ?: return true
        val essential = stepRepo.findById(essentialCd).orElse(null) ?: return true
        return rollupOf(ymd, essential, judge) == WfStepRollup.FINISH
    }

    private fun runSub(ymd: LocalDate, sub: WfSubStepEntity, executor: String, autoManual: String): WfJobStatus {
        val jobLog = jobLogRepo.save(
            WfJobLogEntity(
                ymd = ymd, stepCd = sub.id.stepCd, subStepCd = sub.id.subStepCd,
                execSeq = nextExecSeq(ymd, sub), status = WfJobStatus.RUNNING,
                startedAt = LocalDateTime.now(), autoManual = autoManual, executor = executor,
            )
        )
        runCatching {
            val action = actionMap[sub.actionRef]
                ?: error("액션 빈 없음: ${sub.actionRef} (PR C에서 구현되는 액션인지 확인)")
            if (sub.actionType == WfActionType.POLL && action is WfPollAction) {
                executePoll(action, sub, WfContext(ymd))
            } else {
                action.execute(WfContext(ymd))
            }
        }.onSuccess { result ->
            jobLog.status = WfJobStatus.SUCCESS
            jobLog.remark = result.summary?.take(500)
        }.onFailure { e ->
            log.error("[Closing] sub-step failed {}/{} ymd={}", sub.id.stepCd, sub.id.subStepCd, ymd, e)
            jobLog.status = WfJobStatus.ERROR
            jobLog.remark = e.message?.take(500)
            jobLog.errorDetail = e.stackTraceToString().take(4000)
        }
        jobLog.finishedAt = LocalDateTime.now()
        jobLogRepo.save(jobLog)
        notify(jobLog)
        return jobLog.status
    }

    /** SSE 등 이벤트 발행 — 실패해도 실행 흐름에 영향 없음 (FR-DASH-001). */
    private fun notify(jobLog: WfJobLogEntity) {
        runCatching {
            eventPublisher.publish(
                WfStepEvent(
                    ymd = jobLog.ymd, stepCd = jobLog.stepCd, subStepCd = jobLog.subStepCd,
                    execSeq = jobLog.execSeq, status = jobLog.status, remark = jobLog.remark,
                )
            )
        }.onFailure { e -> log.warn("[Closing] event publish failed", e) }
    }

    /** POLL: 시작 후 간격 폴링, 타임아웃 초과 시 오류 (FR-STEP-004, 기본 5분·10초). */
    private fun executePoll(action: WfPollAction, sub: WfSubStepEntity, ctx: WfContext): WfActionResult {
        val started = action.execute(ctx)
        val deadline = System.currentTimeMillis() + sub.timeoutSec * 1000L
        while (true) {
            when (action.poll(ctx)) {
                WfPollStatus.DONE -> return started
                WfPollStatus.FAILED -> error("폴링 대상 작업 실패: ${sub.actionRef}")
                WfPollStatus.IN_PROGRESS -> {
                    if (System.currentTimeMillis() >= deadline) error("폴링 타임아웃(${sub.timeoutSec}s): ${sub.actionRef}")
                    Thread.sleep(sub.pollIntervalSec * 1000L)
                }
            }
        }
    }

    private fun latestLog(ymd: LocalDate, sub: WfSubStepEntity): WfJobLogEntity? =
        jobLogRepo.findByYmdAndStepCdAndSubStepCdOrderByExecSeqDesc(ymd, sub.id.stepCd, sub.id.subStepCd).firstOrNull()

    private fun nextExecSeq(ymd: LocalDate, sub: WfSubStepEntity): Int =
        (latestLog(ymd, sub)?.execSeq ?: 0) + 1

    private fun createPendingLog(ymd: LocalDate, sub: WfSubStepEntity, executor: String) {
        jobLogRepo.save(
            WfJobLogEntity(
                ymd = ymd, stepCd = sub.id.stepCd, subStepCd = sub.id.subStepCd,
                execSeq = 1, status = WfJobStatus.PENDING, autoManual = "M", executor = executor,
            )
        )
    }

    companion object {
        const val SYSTEM_EXECUTOR = "SYSTEM"
    }
}
