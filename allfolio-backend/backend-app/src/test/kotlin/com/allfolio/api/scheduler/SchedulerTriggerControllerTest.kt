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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
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

    // 빈 설정이 "토큰 불필요"로 해석되면 환경변수를 빠뜨린 순간 엔드포인트가 완전 공개된다.
    @Test
    fun `설정 토큰이 비어 있으면 토큰을 제시해도 503으로 닫는다`() {
        mvc("").perform(
            post("/api/internal/scheduler/fx/hana-collect").header("X-Scheduler-Token", "anything")
        ).andExpect(status().isServiceUnavailable)

        verify(admin, never()).collectHana(any(), anyBoolean())
    }

    // 이 케이스가 이 파일에서 가장 위험하다. 빈 토큰 가드가 없으면 헤더 없는 요청이
    // ByteArray(0) 대 ByteArray(0) 비교가 되어 MessageDigest.isEqual이 true를 돌려주고,
    // 인증을 "통과"해 완전 공개된 엔드포인트에서 수집이 실제로 돈다.
    // 가드가 장식이 아니라 하중을 받는 지점이라 독립된 테스트로 둔다.
    @Test
    fun `설정 토큰이 비어 있고 헤더도 없으면 503으로 닫는다`() {
        mvc("").perform(post("/api/internal/scheduler/fx/hana-collect"))
            .andExpect(status().isServiceUnavailable)

        verify(admin, never()).collectHana(any(), anyBoolean())
    }

    // isBlank()을 isEmpty()로 바꿔도 기존 테스트가 전부 통과했다(변이 테스트).
    // 환경변수에 공백이 섞여 들어오면 그걸 진짜 비밀값으로 받아들이게 된다 —
    // "설정 누락의 기본값은 닫힘"이라는 불변식이 공백 입력에서만 조용히 뒤집힌다.
    @Test
    fun `설정 토큰이 공백뿐이어도 503으로 닫는다`() {
        mvc("   ").perform(
            post("/api/internal/scheduler/fx/hana-collect").header("X-Scheduler-Token", "   ")
        ).andExpect(status().isServiceUnavailable)

        verify(admin, never()).collectHana(any(), anyBoolean())
    }

    // 이 경로는 SecurityConfig에서 permitAll이고 상태를 바꾸는 작업이다.
    // GET으로도 열리면 크롤러·링크 프리페처가 수집을 돌릴 수 있다.
    @Test
    fun `GET으로는 트리거되지 않는다`() {
        mvc("secret").perform(
            get("/api/internal/scheduler/fx/hana-collect").header("X-Scheduler-Token", "secret")
        ).andExpect(status().isMethodNotAllowed)

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
