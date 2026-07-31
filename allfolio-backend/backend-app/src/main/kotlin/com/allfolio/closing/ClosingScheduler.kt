package com.allfolio.closing

import com.allfolio.workflow.application.ClosingInProgressException
import com.allfolio.workflow.application.WfStepExecutor
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneId

/**
 * 마감 워크플로우 자정 트리거 (P3 #24) — 구 DailyNavScheduler @Scheduled의 대체.
 * 실제 배치 로직은 전부 WfAction으로 이동, 여기는 runDaily 호출만 하는 얇은 트리거.
 * 게이트 SKIP된 단계 재시도를 위해 오전 재트리거 1회(01:30) 포함.
 */
@Component
class ClosingScheduler(private val stepExecutor: WfStepExecutor) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    fun midnight() = trigger()

    /** 오류 후 수동 복구·게이트 재시도 여지 — 이미 성공한 하위단계는 멱등 스킵. */
    @Scheduled(cron = "0 30 1 * * *", zone = "Asia/Seoul")
    fun retry() = trigger()

    private fun trigger() {
        val today = LocalDate.now(KST)
        runCatching { stepExecutor.runDaily(today) }
            .onSuccess { s ->
                log.info("[Closing] runDaily ymd={} executed={} gateSkipped={} notScheduled={}",
                    today, s.executedSteps, s.gateSkippedSteps, s.notScheduledSteps)
            }
            .onFailure { e ->
                when (e) {
                    is ClosingInProgressException -> log.info("[Closing] 이미 실행 중 ymd={}", today)
                    else -> log.error("[Closing] runDaily 실패 ymd={}", today, e)
                }
            }
    }

    companion object {
        private val KST = ZoneId.of("Asia/Seoul")
    }
}
