package com.allfolio.api.scheduler

import com.allfolio.api.admin.FxRateAdminController
import com.allfolio.fx.hana.HanaCollectSummary
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 외부 스케줄러(GitHub Actions) 전용 트리거 (AF-103).
 *
 * Render 무료 플랜에는 크론 잡이 없고, 무료 웹 서비스는 15분 유휴 시 잠들어
 * 인스턴스 안의 `@Scheduled`만으로는 주기 실행이 성립하지 않는다.
 * 외부에서 깨워야 하므로, 그 신호를 곧 트리거로 쓴다.
 *
 * **어드민 JWT를 안 쓰는 이유**: 15분 만료라 CI가 들고 있을 수 없다.
 * CI가 매번 로그인하게 하면 어드민 비밀번호가 시크릿에 들어가고, 유출 시 전권이 넘어간다.
 * 수집 트리거만 가능한 토큰은 유출돼도 할 수 있는 일이 "멱등한 수집을 여러 번 도는 것"뿐이다.
 *
 * 이 경로는 SecurityConfig에서 permitAll이다 — 인증은 여기서 한다.
 */
@RestController
@RequestMapping("/api/internal/scheduler")
class SchedulerTriggerController(
    private val fxAdmin: FxRateAdminController,
    @Value("\${scheduler.trigger-token:}") private val configuredToken: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * POST /api/internal/scheduler/fx/hana-collect — 하나은행 고시환율 수집 트리거
     *
     * **`force`를 노출하지 않는다.** 스케줄 실행은 항상 `force = false`여야 한다.
     * AF-99의 2% 급변동 가드가 걸리면 422가 나가고 워크플로 잡이 실패하는데, 그게 의도한 동작이다 —
     * 진짜 크게 움직인 날은 사람이 값을 보고 판단해야 하고 Actions의 실패 표시가 그 신호다.
     * 스케줄러가 조용히 force로 뚫으면 파싱 오류로 튄 값이 그대로 저장된다.
     *
     * **날짜도 노출하지 않는다.** [FxRateAdminController.collectHana]가 null을 KST 오늘로 해석한다.
     * Render 컨테이너는 UTC라 이 기본값 처리가 없으면 09:10 KST 실행이 "어제"를 조회한다.
     *
     * 어드민 컨트롤러에 위임하는 이유는 그쪽의 예외→상태 매핑(422 안전장치 / 502 은행 응답 이상 /
     * 409 경합)이 Actions 로그를 읽는 사람에게 그대로 필요해서다. 복제하면 두 벌이 갈라지고,
     * 공용 헬퍼로 뽑으면 "이 엔드포인트에서만 이렇게 하는 이유"를 적은 주석들이 근거를 잃는다.
     * 컨트롤러가 컨트롤러를 주입받는 게 낯설다는 건 알지만 대안 둘 다 이보다 나쁘다.
     * **이 위임을 "정리"하지 말 것.**
     */
    @PostMapping("/fx/hana-collect")
    fun collectHanaFx(
        @RequestHeader(name = TOKEN_HEADER, required = false) token: String?,
    ): ResponseEntity<HanaCollectSummary> {
        authorize(token)
        return fxAdmin.collectHana(null, false)
    }

    /**
     * 설정 토큰이 비어 있으면 503으로 닫는다 — 이 메서드에서 가장 중요한 분기다.
     * 빈 값을 "토큰 불필요"로 해석하면 SCHEDULER_TOKEN을 빠뜨린 순간 엔드포인트가 완전 공개된다.
     * 설정 누락의 기본값은 "열림"이 아니라 "닫힘"이어야 한다.
     */
    private fun authorize(token: String?) {
        if (configuredToken.isBlank()) {
            log.warn("[Scheduler] scheduler.trigger-token 미설정 — 트리거 엔드포인트를 닫는다")
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "스케줄러 토큰이 설정되지 않았습니다.",
            )
        }
        // 상수 시간 비교. 길이는 새지만 내용은 새지 않는다.
        val presented = token?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        if (!MessageDigest.isEqual(presented, configuredToken.toByteArray(StandardCharsets.UTF_8))) {
            log.warn("[Scheduler] 트리거 토큰 불일치 — 거부")
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증에 실패했습니다.")
        }
    }

    companion object {
        private const val TOKEN_HEADER = "X-Scheduler-Token"
    }
}
