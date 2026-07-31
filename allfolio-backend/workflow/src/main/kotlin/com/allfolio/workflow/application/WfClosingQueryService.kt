package com.allfolio.workflow.application

import com.allfolio.workflow.domain.WfJobStatus
import com.allfolio.workflow.infrastructure.entity.WfJobLogEntity
import com.allfolio.workflow.infrastructure.jpa.WfHolidayJpaRepository
import com.allfolio.workflow.infrastructure.jpa.WfJobLogJpaRepository
import com.allfolio.workflow.infrastructure.jpa.WfStepJpaRepository
import com.allfolio.workflow.infrastructure.jpa.WfSubStepJpaRepository
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.YearMonth

data class WfStepCard(
    val stepCd: String,
    val stepName: String,
    val stepGroup: String?,
    val rollup: String,
    val errorCnt: Int,
    val pendingCnt: Int,
    val url: String?,
    val cutoffEnd: String?,
)

data class WfDayView(val ymd: String, val isHoliday: Boolean, val steps: List<WfStepCard>)

data class WfMonthView(val month: String, val days: List<WfDayView>)

data class WfJobLogView(
    val id: String,
    val ymd: String,
    val stepCd: String,
    val subStepCd: String,
    val execSeq: Int,
    val status: String,
    val startedAt: String?,
    val finishedAt: String?,
    val autoManual: String,
    val executor: String,
    val remark: String?,
    val errorDetail: String?,
)

data class WfSubStepView(
    val subStepCd: String,
    val subStepName: String,
    val actionType: String,
    val actionRef: String?,
    val autoManual: String,
    val closingCheckYn: Boolean,
    val scheduledToday: Boolean,
    val latest: WfJobLogView?,
    val history: List<WfJobLogView>,
)

data class WfDayDetail(
    val ymd: String,
    val isHoliday: Boolean,
    val steps: List<WfStepDetail>,
)

data class WfStepDetail(
    val stepCd: String,
    val stepName: String,
    val rollup: String,
    val essentialStepCd: String?,
    val cutoffStart: String?,
    val cutoffEnd: String?,
    val subSteps: List<WfSubStepView>,
)

/** 마감 관제 조회 조립 (SCR-DASH-01/02/04/05 데이터 소스). */
@Service
class WfClosingQueryService(
    private val executor: WfStepExecutor,
    private val stepRepo: WfStepJpaRepository,
    private val subStepRepo: WfSubStepJpaRepository,
    private val jobLogRepo: WfJobLogJpaRepository,
    private val holidayRepo: WfHolidayJpaRepository,
) {
    fun monthView(month: YearMonth): WfMonthView {
        val from = month.atDay(1)
        val to = month.atEndOfMonth()
        val holidays = holidayRepo.findByIdDayBetween(from, to).map { it.id.day }.toSet()
        val logs = jobLogRepo.findByYmdBetween(from, to)
            .groupBy { Triple(it.ymd, it.stepCd, it.subStepCd) }
            .mapValues { (_, v) -> v.maxBy { it.execSeq } }
        val steps = stepRepo.findByUseYnTrueOrderByStepSeq()

        val days = (1..month.lengthOfMonth()).map { d ->
            val date = month.atDay(d)
            val judge = executor.judgeFor(date)
            val cards = steps.mapNotNull { step ->
                val subs = executor.scheduledSubs(step, date, judge)
                if (subs.isEmpty()) return@mapNotNull null
                val latest = subs.map { logs[Triple(date, step.stepCd, it.id.subStepCd)] }
                WfStepCard(
                    stepCd = step.stepCd, stepName = step.stepName, stepGroup = step.stepGroup,
                    rollup = com.allfolio.workflow.domain.WfRollup.rollup(
                        subs.filter { it.closingCheckYn }
                            .map { logs[Triple(date, step.stepCd, it.id.subStepCd)]?.status }
                    ).name,
                    errorCnt = latest.count { it?.status == WfJobStatus.ERROR },
                    pendingCnt = latest.count { it == null || it.status == WfJobStatus.PENDING },
                    url = step.url, cutoffEnd = step.cutoffEnd,
                )
            }
            WfDayView(ymd = date.toString(), isHoliday = date in holidays, steps = cards)
        }
        return WfMonthView(month = month.toString(), days = days)
    }

    fun dayDetail(ymd: LocalDate): WfDayDetail {
        val judge = executor.judgeFor(ymd)
        val holiday = holidayRepo.findByIdDayBetween(ymd, ymd).isNotEmpty()
        val steps = stepRepo.findByUseYnTrueOrderByStepSeq().map { step ->
            val allSubs = subStepRepo.findByIdStepCdAndUseYnTrueOrderBySubStepSeq(step.stepCd)
            val scheduled = executor.scheduledSubs(step, ymd, judge).map { it.id.subStepCd }.toSet()
            WfStepDetail(
                stepCd = step.stepCd, stepName = step.stepName,
                rollup = executor.rollupOf(ymd, step, judge).name,
                essentialStepCd = step.essentialStepCd,
                cutoffStart = step.cutoffStart, cutoffEnd = step.cutoffEnd,
                subSteps = allSubs.map { sub ->
                    val history = jobLogRepo
                        .findByYmdAndStepCdAndSubStepCdOrderByExecSeqDesc(ymd, step.stepCd, sub.id.subStepCd)
                        .map { it.toView() }
                    WfSubStepView(
                        subStepCd = sub.id.subStepCd, subStepName = sub.subStepName,
                        actionType = sub.actionType.name, actionRef = sub.actionRef,
                        autoManual = sub.autoManual, closingCheckYn = sub.closingCheckYn,
                        scheduledToday = sub.id.subStepCd in scheduled,
                        latest = history.firstOrNull(), history = history,
                    )
                },
            )
        }
        return WfDayDetail(ymd = ymd.toString(), isHoliday = holiday, steps = steps)
    }

    /** 재작업 로그 (SCR-DASH-05) — 차수 2 이상. */
    fun reworkLogs(ymd: LocalDate): List<WfJobLogView> =
        jobLogRepo.findByYmdAndExecSeqGreaterThanOrderByStartedAtDesc(ymd, 1).map { it.toView() }

    fun holidays(year: Int): Map<String, String?> =
        holidayRepo.findByIdDayBetween(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))
            .associate { it.id.day.toString() to it.name }

    private fun WfJobLogEntity.toView() = WfJobLogView(
        id = id.toString(), ymd = ymd.toString(), stepCd = stepCd, subStepCd = subStepCd,
        execSeq = execSeq, status = status.name,
        startedAt = startedAt?.toString(), finishedAt = finishedAt?.toString(),
        autoManual = autoManual, executor = executor, remark = remark, errorDetail = errorDetail,
    )
}
