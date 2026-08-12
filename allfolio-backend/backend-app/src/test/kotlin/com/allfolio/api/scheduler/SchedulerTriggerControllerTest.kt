package com.allfolio.api.scheduler

import com.allfolio.api.admin.FxRateAdminController
import com.allfolio.fx.hana.HanaCollectSummary
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.server.ResponseStatusException
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.time.LocalDate

class SchedulerTriggerControllerTest {

    // JUnit5는 테스트마다 인스턴스를 새로 만들므로 목이 테스트 간에 새지 않는다.
    private val admin: FxRateAdminController = mock(FxRateAdminController::class.java)

    private val summary = HanaCollectSummary(
        requestedDate = LocalDate.of(2026, 8, 12),
        baseDate = LocalDate.of(2026, 8, 12),
        roundNo = 286,
        currencies = 58,
        inserted = 58,
        updated = 0,
        unchanged = 0,
        skipped = 0,
    )

    private fun mvc(token: String) = MockMvcBuilders
        .standaloneSetup(SchedulerTriggerController(admin, token))
        .build()

    @Test
    fun `토큰이 맞으면 수집을 실행하고 요약을 돌려준다`() {
        `when`(admin.collectHana(null, false)).thenReturn(ResponseEntity.ok(summary))

        mvc("secret").perform(
            post("/api/internal/scheduler/fx/hana-collect").header("X-Scheduler-Token", "secret")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.roundNo").value(286))
            .andExpect(jsonPath("$.currencies").value(58))

        // 스케줄 실행은 절대 force를 쓰지 않는다 — 2% 급변동 가드가 살아있어야 한다
        verify(admin).collectHana(null, false)
    }

    @Test
    fun `토큰이 틀리면 401이고 수집을 부르지 않는다`() {
        mvc("secret").perform(
            post("/api/internal/scheduler/fx/hana-collect").header("X-Scheduler-Token", "wrong")
        ).andExpect(status().isUnauthorized)

        verify(admin, never()).collectHana(any(), anyBoolean())
    }

    @Test
    fun `헤더가 없으면 401이고 수집을 부르지 않는다`() {
        mvc("secret").perform(post("/api/internal/scheduler/fx/hana-collect"))
            .andExpect(status().isUnauthorized)

        verify(admin, never()).collectHana(any(), anyBoolean())
    }

    // 이 테스트가 이 파일에서 가장 중요하다.
    // 빈 설정이 "토큰 불필요"로 해석되면 환경변수를 빠뜨린 순간 엔드포인트가 완전 공개된다.
    @Test
    fun `설정 토큰이 비어 있으면 요청 토큰이 무엇이든 503으로 닫는다`() {
        mvc("").perform(
            post("/api/internal/scheduler/fx/hana-collect").header("X-Scheduler-Token", "anything")
        ).andExpect(status().isServiceUnavailable)

        mvc("").perform(post("/api/internal/scheduler/fx/hana-collect"))
            .andExpect(status().isServiceUnavailable)

        verify(admin, never()).collectHana(any(), anyBoolean())
    }

    // 위임이 실제로 어드민 컨트롤러의 상태 매핑을 물려받는지 확인한다.
    // 이게 없으면 SchedulerTriggerController가 조용히 500을 뱉어도 테스트가 통과한다.
    @Test
    fun `수집이 안전장치에 걸리면 422가 그대로 전달된다`() {
        `when`(admin.collectHana(null, false))
            .thenThrow(ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "2% 초과 변동"))

        mvc("secret").perform(
            post("/api/internal/scheduler/fx/hana-collect").header("X-Scheduler-Token", "secret")
        ).andExpect(status().isUnprocessableEntity)
    }
}
