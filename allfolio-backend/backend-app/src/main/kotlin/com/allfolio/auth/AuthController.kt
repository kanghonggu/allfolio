package com.allfolio.auth

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.util.UUID

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService,
    @Value("\${allfolio.auth.refresh-token-days}") private val refreshTokenDays: Long,
    @Value("\${allfolio.auth.cookie-secure:true}") private val cookieSecure: Boolean = true,
) {

    @PostMapping("/register")
    fun register(@RequestBody request: RegisterRequest): ResponseEntity<AuthBodyResponse> =
        withRefreshCookie(authService.register(request), HttpStatus.CREATED)

    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): ResponseEntity<AuthBodyResponse> =
        withRefreshCookie(authService.login(request))

    @PostMapping("/refresh")
    fun refresh(
        @CookieValue(REFRESH_COOKIE, required = false) cookieToken: String?,
        @RequestBody(required = false) body: RefreshRequest?,
    ): ResponseEntity<AuthBodyResponse> {
        val token = cookieToken ?: body?.refreshToken
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "refresh token이 없습니다.")
        return withRefreshCookie(authService.refresh(token))
    }

    @PostMapping("/logout")
    fun logout(
        @CookieValue(REFRESH_COOKIE, required = false) cookieToken: String?,
        @RequestBody(required = false) body: LogoutRequest?,
    ): ResponseEntity<Void> {
        (cookieToken ?: body?.refreshToken)?.let { authService.logout(it) }
        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, expiredRefreshCookie().toString())
            .build()
    }

    @GetMapping("/me")
    fun me(@RequestHeader("X-User-Id") userId: UUID): AuthUserResponse =
        authService.me(userId)

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteMe(
        @RequestHeader("X-User-Id") userId: UUID,
        @RequestBody request: DeleteAccountRequest,
    ) {
        authService.deleteAccount(userId, request.password)
    }

    /** refreshToken은 HttpOnly 쿠키로만 내리고 본문에서는 제외한다 (QA P0 #5) */
    private fun withRefreshCookie(auth: AuthResponse, status: HttpStatus = HttpStatus.OK): ResponseEntity<AuthBodyResponse> {
        val cookie = refreshCookieBuilder(auth.refreshToken)
            .maxAge(Duration.ofDays(refreshTokenDays))
            .build()
        return ResponseEntity.status(status)
            .header(HttpHeaders.SET_COOKIE, cookie.toString())
            .body(AuthBodyResponse(auth.accessToken, auth.expiresIn, auth.user))
    }

    private fun expiredRefreshCookie(): ResponseCookie =
        refreshCookieBuilder("").maxAge(0).build()

    private fun refreshCookieBuilder(value: String): ResponseCookie.ResponseCookieBuilder =
        ResponseCookie.from(REFRESH_COOKIE, value)
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite("Lax")
            .path("/api/auth")

    companion object {
        const val REFRESH_COOKIE = "allfolio_rt"
    }
}
