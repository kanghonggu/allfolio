package com.allfolio.auth

import java.util.UUID

data class RegisterRequest(
    val email: String,
    val password: String,
    val displayName: String?,
)

data class LoginRequest(
    val email: String,
    val password: String,
)

data class RefreshRequest(val refreshToken: String? = null)
data class LogoutRequest(val refreshToken: String? = null)
data class DeleteAccountRequest(val password: String)

data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
    val user: AuthUserResponse,
)

/** 클라이언트 응답 본문 — refreshToken은 HttpOnly 쿠키로만 전달 (QA P0 #5) */
data class AuthBodyResponse(
    val accessToken: String,
    val expiresIn: Long,
    val user: AuthUserResponse,
)

data class AuthUserResponse(
    val id: UUID,
    val email: String,
    val displayName: String?,
    val role: UserRole,
)
