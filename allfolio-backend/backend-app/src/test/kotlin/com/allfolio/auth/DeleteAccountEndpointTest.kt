package com.allfolio.auth

import com.allfolio.config.JwtUserIdFilter
import com.allfolio.config.SecurityConfig
import com.allfolio.config.SseTokenFilter
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import java.util.UUID

@SpringBootTest(
    classes = [
        DeleteAccountEndpointTest.TestApplication::class,
        DeleteAccountEndpointTest.TestBeans::class,
        SecurityConfig::class,
        JwtUserIdFilter::class,
        SseTokenFilter::class,
        JwtTokenService::class,
        AuthController::class,
    ],
    properties = [
        "allfolio.auth.jwt-secret=test-secret-test-secret-test-secret-1234",
        "allfolio.auth.access-token-minutes=15",
        "allfolio.cors.allowed-origins=http://localhost:3000",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
    ],
)
@AutoConfigureMockMvc
class DeleteAccountEndpointTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jwtTokenService: JwtTokenService
    @Autowired private lateinit var authService: AuthService

    @Test
    fun `delete me without token is rejected`() {
        mockMvc.delete("/api/auth/me") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"password":"pw"}"""
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `delete me with token returns 204`() {
        val user = UserEntity(id = UUID.randomUUID(), email = "u@example.com", passwordHash = "hash", displayName = null)
        val (token, _) = jwtTokenService.issue(user)

        mockMvc.delete("/api/auth/me") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"password":"pw"}"""
        }.andExpect { status { isNoContent() } }

        // 요청 body의 password가 실제로 읽혀 서비스로 전달되는지 검증
        verify(authService).deleteAccount(eqx(user.id), eqx("pw"))
    }

    // Kotlin non-null 파라미터에 Mockito eq 매처를 쓰기 위한 헬퍼 (eq는 null을 반환)
    private fun <T> eqx(value: T): T = eq(value) ?: value

    @SpringBootConfiguration
    @EnableAutoConfiguration
    class TestApplication

    @TestConfiguration
    class TestBeans {
        @Bean fun authService(): AuthService = mock(AuthService::class.java)
    }
}
