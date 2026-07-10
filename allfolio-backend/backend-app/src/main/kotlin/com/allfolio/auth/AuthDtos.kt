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

data class RefreshRequest(val refreshToken: String)
data class LogoutRequest(val refreshToken: String)
data class DeleteAccountRequest(val password: String)

data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
    val user: AuthUserResponse,
)

data class AuthUserResponse(
    val id: UUID,
    val email: String,
    val displayName: String?,
)
