package com.allfolio.closing

import com.allfolio.workflow.application.ClosingInProgressException
import com.allfolio.workflow.application.WfStepExecutor
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneId

/**
 * 마감 워크플로우 자정 트리거 (P3 #24) — 구 DailyNavScheduler @Scheduled의 대체.
 * 실제 배치 로직은 전부 WfAction으로 이동, 여기는 runDaily 호출만 하는 얇은 트리거.
 * 게이트 SKIP된 단계 재시도를 위해 오전 재트리거 1회(01:30) 포함.
 *
 * **기본 off다 (`closing.scheduler.enabled`).** Render 무료 웹 서비스는 15분 유휴 시 잠들고,
 * 자정 KST에는 아무도 앱을 안 쓰므로 인스턴스가 자고 있다. 잠든 인스턴스에서는 @Scheduled가
 * 뛰지 않는다 — 운영 로그 확인 결과 6일 중 4일은 자정·01:30 두 트리거가 아예 안 떴다.
 * 트리거는 GitHub Actions 크론이 `POST /api/internal/scheduler/closing`으로 대신한다
 * (`.github/workflows/closing.yml`).
 *
 * 코드를 지우지 않는 이유는 유료 플랜에서는 이쪽이 더 단순하기 때문이고, 켜 두지 않는 이유는
 * 인스턴스가 우연히 깨어 있을 때 외부 트리거와 겹쳐 도는 게 헷갈리기 때문이다.
 * Redis 락(WfLockPort)이 있어 위험하지는 않다 — 순수하게 관측 가능성 문제다.
 */
@Component
@ConditionalOnProperty(name = ["closing.scheduler.enabled"], havingValue = "true")
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
