package com.allfolio.api.admin

import com.allfolio.auth.JwtTokenService
import com.allfolio.auth.UserEntity
import com.allfolio.auth.UserRole
import com.allfolio.config.GlobalExceptionHandler
import com.allfolio.config.JwtUserIdFilter
import com.allfolio.config.SecurityConfig
import com.allfolio.config.SseTokenFilter
import com.allfolio.fx.CashFlowRecomputeService
import com.allfolio.fx.FxRateBackfillService
import com.allfolio.fx.FxRateService
import com.allfolio.fx.RecomputeSummary
import com.allfolio.fx.hana.HanaFxCollectService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.util.UUID

/**
 * POST /api/admin/fx/cashflow-recompute (AF-100 2단계).
 *
 * **이 엔드포인트에서 지켜야 하는 것은 기본값이 드라이런이라는 사실 하나다.**
 * `apply`를 빠뜨린 호출이 금융 이력을 바꾸면, 사용자는 보고서를 보기도 전에
 * `cash_flow.amount_krw`가 전부 다시 쓰인 뒤에야 알게 된다. 되돌릴 감사 테이블도 없다.
 */
@WebMvcTest(controllers = [FxRateAdminController::class])
@ContextConfiguration(classes = [FxRateAdminRecomputeControllerTest.TestApplication::class])
@Import(
    FxRateAdminController::class,
    SecurityConfig::class,
    SseTokenFilter::class,
    JwtUserIdFilter::class,
    JwtTokenService::class,
    GlobalExceptionHandler::class,
)
@TestPropertySource(
    properties = [
        "allfolio.cors.allowed-origins=http://localhost:3000",
        "allfolio.auth.jwt-secret=01234567890123456789012345678901",
        "allfolio.auth.access-token-minutes=60",
    ],
)
class FxRateAdminRecomputeControllerTest {

    @SpringBootConfiguration
    class TestApplication

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jwtTokenService: JwtTokenService

    @MockBean private lateinit var recomputeService: CashFlowRecomputeService

    // 재계산만 보는 테스트라 호출되지 않는다. FxRateAdminController가 요구하므로 자리만 채운다.
    @MockBean private lateinit var fxRateService: FxRateService
    @MockBean private lateinit var backfillService: FxRateBackfillService
    @MockBean private lateinit var hanaCollectService: HanaFxCollectService

    @Test
    fun `재계산은 ADMIN이 아니면 403`() {
        call(role = UserRole.USER).andExpect { status { isForbidden() } }
        call(role = null).andExpect { status { isForbidden() } }
    }

    // **이 테스트가 이 엔드포인트의 안전장치 전부다.**
    // apply를 빼먹은 호출이 곧바로 금융 이력을 다시 쓰면 되돌릴 방법이 없다.
    @Test
    fun `apply를 생략하면 드라이런으로 부른다`() {
        `when`(recomputeService.recompute(false)).thenReturn(summary(applied = false))

        call().andExpect { status { isOk() } }

        verify(recomputeService).recompute(false)
    }

    @Test
    fun `apply true를 명시해야 적용된다`() {
        `when`(recomputeService.recompute(true)).thenReturn(summary(applied = true))

        call(apply = "true").andExpect { status { isOk() } }

        verify(recomputeService).recompute(true)
    }

    // 보고서를 보고 진행 여부를 정하는 구조라, 요약이 그대로 실려 나가야 한다.
    // stillEstimated가 빠지면 "다 고쳤다"고 착각하게 된다
    @Test
    fun `요약을 그대로 돌려준다`() {
        `when`(recomputeService.recompute(false)).thenReturn(summary(applied = false))

        call().andExpect {
            status { isOk() }
            jsonPath("$.applied") { value(false) }
            jsonPath("$.scanned") { value(12) }
            jsonPath("$.changed") { value(7) }
            jsonPath("$.unchanged") { value(3) }
            jsonPath("$.stillEstimated") { value(2) }
        }
    }

    // ── helpers ──────────────────────────────────────────────

    private fun call(apply: String? = null, role: UserRole? = UserRole.ADMIN) =
        mockMvc.post("/api/admin/fx/cashflow-recompute") {
            if (role != null) header("Authorization", "Bearer ${tokenFor(role)}")
            if (apply != null) param("apply", apply)
        }

    private fun summary(applied: Boolean) = RecomputeSummary(
        applied = applied,
        scanned = 12,
        changed = 7,
        unchanged = 3,
        stillEstimated = 2,
        totalDelta = BigDecimal("-110000"),
        byCurrency = emptyMap(),
        topChanges = emptyList(),
    )

    private fun tokenFor(role: UserRole): String =
        jwtTokenService.issue(
            UserEntity(
                id = UUID.randomUUID(),
                email = "${UUID.randomUUID()}@example.com",
                passwordHash = "hash",
                displayName = "Test Admin",
                role = role,
            ),
        ).first
}
