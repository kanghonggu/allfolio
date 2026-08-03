package com.allfolio.config

import com.allfolio.api.admin.TaxRateAdminController
import com.allfolio.auth.JwtTokenService
import com.allfolio.auth.UserEntity
import com.allfolio.auth.UserRole
import com.allfolio.unifiedasset.application.usecase.TaxRateService
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
import org.springframework.test.web.servlet.post
import java.util.UUID

// QA P0 #4 — admin 엔드포인트 서버사이드 인가 검증.
// 모든 admin 컨트롤러는 /api/admin 하위 경로에 있고(SecurityConfig가 hasRole("ADMIN") 강제),
// 이 테스트는 그 규칙이 실제로 일반 유저·무토큰 요청을 차단하는지 회귀 방지한다.
@WebMvcTest(controllers = [TaxRateAdminController::class])
@ContextConfiguration(classes = [AdminSecurityConfigTest.TestApplication::class])
@Import(
    TaxRateAdminController::class,
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
class AdminSecurityConfigTest {

    @SpringBootConfiguration
    class TestApplication

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jwtTokenService: JwtTokenService

    @MockBean
    private lateinit var taxRateService: TaxRateService

    @Test
    fun `무토큰 admin API 호출은 403`() {
        mockMvc.get("/api/admin/tax-rates")
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `일반 유저 토큰으로 admin API 조회는 403`() {
        mockMvc.get("/api/admin/tax-rates") {
            header("Authorization", "Bearer ${tokenFor(UserRole.USER)}")
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `일반 유저 토큰으로 admin API 쓰기는 403`() {
        mockMvc.post("/api/admin/tax-rates") {
            header("Authorization", "Bearer ${tokenFor(UserRole.USER)}")
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"country":"KR","incomeType":"DIVIDEND","rate":0.154,"effectiveStart":"2026-01-01"}"""
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `관리자 토큰은 admin API 접근 가능`() {
        `when`(taxRateService.list()).thenReturn(emptyList())

        mockMvc.get("/api/admin/tax-rates") {
            header("Authorization", "Bearer ${tokenFor(UserRole.ADMIN)}")
        }.andExpect { status { isOk() } }
    }

    private fun tokenFor(role: UserRole): String =
        jwtTokenService.issue(
            UserEntity(
                id = UUID.randomUUID(),
                email = "${UUID.randomUUID()}@example.com",
                passwordHash = "hash",
                displayName = "Test User",
                role = role,
            )
        ).first
}
