package com.allfolio.auth

import com.allfolio.config.GlobalExceptionHandler
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID

// QA P0 #5 — refreshToken을 HttpOnly 쿠키로만 전달, 응답 본문에서는 제거.
class AuthControllerCookieTest {

    private val authService = mock(AuthService::class.java)
    private val controller = AuthController(authService, refreshTokenDays = 30)
    private val mvc = MockMvcBuilders
        .standaloneSetup(controller)
        .setControllerAdvice(GlobalExceptionHandler())
        .build()

    private fun authResponse() = AuthResponse(
        accessToken = "access-jwt",
        refreshToken = "refresh-secret",
        expiresIn = 900,
        user = AuthUserResponse(UUID.randomUUID(), "u@example.com", "u", UserRole.USER),
    )

    @Test
    fun `로그인은 refreshToken을 HttpOnly 쿠키로 내리고 본문에서는 제외한다`() {
        `when`(authService.login(LoginRequest("u@example.com", "pw123456"))).thenReturn(authResponse())

        mvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"u@example.com","password":"pw123456"}"""
        }.andExpect {
            status { isOk() }
            cookie {
                value("allfolio_rt", "refresh-secret")
                httpOnly("allfolio_rt", true)
                secure("allfolio_rt", true)
                path("allfolio_rt", "/api/auth")
            }
            jsonPath("$.accessToken") { value("access-jwt") }
            jsonPath("$.refreshToken") { doesNotExist() }
        }
    }

    @Test
    fun `refresh는 쿠키의 refreshToken만으로 동작한다`() {
        `when`(authService.refresh("refresh-secret")).thenReturn(authResponse())

        mvc.post("/api/auth/refresh") {
            cookie(Cookie("allfolio_rt", "refresh-secret"))
        }.andExpect {
            status { isOk() }
            jsonPath("$.accessToken") { value("access-jwt") }
            jsonPath("$.refreshToken") { doesNotExist() }
        }
        verify(authService).refresh("refresh-secret")
    }

    @Test
    fun `refresh는 본문 refreshToken 폴백을 지원한다`() {
        `when`(authService.refresh("body-token")).thenReturn(authResponse())

        mvc.post("/api/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"refreshToken":"body-token"}"""
        }.andExpect { status { isOk() } }
        verify(authService).refresh("body-token")
    }

    @Test
    fun `refresh에 쿠키도 본문도 없으면 401`() {
        mvc.post("/api/auth/refresh")
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `logout은 세션을 무효화하고 쿠키를 삭제한다`() {
        mvc.post("/api/auth/logout") {
            cookie(Cookie("allfolio_rt", "refresh-secret"))
        }.andExpect {
            status { isNoContent() }
            cookie { maxAge("allfolio_rt", 0) }
        }
        verify(authService).logout("refresh-secret")
    }
}
