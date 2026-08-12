package com.allfolio.api.admin

import com.allfolio.auth.JwtTokenService
import com.allfolio.auth.UserEntity
import com.allfolio.auth.UserRole
import com.allfolio.config.GlobalExceptionHandler
import com.allfolio.config.JwtUserIdFilter
import com.allfolio.config.SecurityConfig
import com.allfolio.config.SseTokenFilter
import com.allfolio.fx.FxRateBackfillService
import com.allfolio.fx.FxRateService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.math.BigDecimal
import java.util.UUID

/**
 * 환율 조회 엔드포인트 — AF-99 USD·USDT 분리의 **관측 수단**.
 *
 * `usdtkrw`만 있으면 분리 후 자산 평가 경로가 실제로 무슨 값을 쓰는지 확인할 방법이 없다.
 * 두 엔드포인트가 서로 다른 값을 돌려주는지가 분리가 살아 있다는 유일한 운영 증거다.
 */
@WebMvcTest(controllers = [FxRateAdminController::class])
@ContextConfiguration(classes = [FxRateAdminRateLookupTest.TestApplication::class])
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
    ]
)
class FxRateAdminRateLookupTest {

    @SpringBootConfiguration
    class TestApplication

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jwtTokenService: JwtTokenService

    @MockBean private lateinit var fxRateService: FxRateService
    @MockBean private lateinit var backfillService: FxRateBackfillService

    private fun tokenFor(role: UserRole): String =
        jwtTokenService.issue(
            UserEntity(
                id = UUID.randomUUID(),
                email = "${UUID.randomUUID()}@example.com",
                passwordHash = "hash",
                displayName = "Test Admin",
                role = role,
            )
        ).first

    private fun callUsdKrw(role: UserRole? = UserRole.ADMIN) =
        mockMvc.get("/api/admin/fx/usdkrw") {
            if (role != null) header("Authorization", "Bearer ${tokenFor(role)}")
        }

    @Test
    fun `usdkrw는 ADMIN이 아니면 403`() {
        callUsdKrw(role = UserRole.USER).andExpect { status { isForbidden() } }
        callUsdKrw(role = null).andExpect { status { isForbidden() } }
    }

    @Test
    fun `usdkrw와 usdtkrw가 서로 다른 값을 돌려준다`() {
        // 분리가 살아 있으면 두 값이 갈린다. 한쪽으로 접히면 여기서 잡힌다.
        `when`(fxRateService.getUsdToKrw()).thenReturn(BigDecimal("1380.50"))
        `when`(fxRateService.getUsdtToKrw()).thenReturn(BigDecimal("1400.00"))

        callUsdKrw().andExpect {
            status { isOk() }
            jsonPath("$.usdKrw") { value(1380.50) }
        }

        mockMvc.get("/api/admin/fx/usdtkrw") {
            header("Authorization", "Bearer ${tokenFor(UserRole.ADMIN)}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.usdtKrw") { value(1400.00) }
        }
    }
}
