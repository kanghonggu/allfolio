package com.allfolio.auth

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDateTime
import java.util.Base64
import java.util.UUID

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtTokenService: JwtTokenService,
    @Value("\${allfolio.auth.refresh-token-days}") private val refreshTokenDays: Long,
) {
    private val secureRandom = SecureRandom()

    @Transactional
    fun register(request: RegisterRequest): AuthResponse {
        val email = normalizeEmail(request.email)
        validatePassword(request.password)
        if (userRepository.existsByEmail(email)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 가입된 이메일입니다.")
        }

        val user = userRepository.save(
            UserEntity(
                email = email,
                passwordHash = passwordEncoder.encode(request.password),
                displayName = request.displayName?.trim()?.takeIf { it.isNotBlank() },
            ),
        )
        return issueAuthResponse(user)
    }

    @Transactional
    fun login(request: LoginRequest): AuthResponse {
        val user = userRepository.findByEmail(normalizeEmail(request.email))
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.")
        if (!passwordEncoder.matches(request.password, user.passwordHash)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.")
        }
        return issueAuthResponse(user)
    }

    @Transactional
    fun refresh(request: RefreshRequest): AuthResponse {
        val existing = refreshTokenRepository.findByTokenHash(hashToken(request.refreshToken))
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "refresh token이 유효하지 않습니다.")
        if (!existing.isActive()) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "refresh token이 만료되었습니다.")
        }

        existing.revoke()
        val user = userRepository.findById(existing.userId)
            .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "사용자를 찾을 수 없습니다.") }
        return issueAuthResponse(user)
    }

    @Transactional
    fun logout(request: LogoutRequest) {
        refreshTokenRepository.findByTokenHash(hashToken(request.refreshToken))?.revoke()
    }

    @Transactional(readOnly = true)
    fun me(userId: UUID): AuthUserResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다.") }
        return user.toResponse()
    }

    private fun issueAuthResponse(user: UserEntity): AuthResponse {
        val (accessToken, expiresIn) = jwtTokenService.issue(user)
        val refreshToken = newRefreshToken()
        refreshTokenRepository.save(
            RefreshTokenEntity(
                userId = user.id,
                tokenHash = hashToken(refreshToken),
                expiresAt = LocalDateTime.now().plusDays(refreshTokenDays),
            ),
        )
        return AuthResponse(accessToken, refreshToken, expiresIn, user.toResponse())
    }

    private fun normalizeEmail(email: String): String =
        email.trim().lowercase().also {
            if (!it.contains("@")) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "이메일 형식이 올바르지 않습니다.")
        }

    private fun validatePassword(password: String) {
        if (password.length < 8) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "비밀번호는 8자 이상이어야 합니다.")
        }
    }

    private fun newRefreshToken(): String {
        val bytes = ByteArray(48)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hashToken(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun UserEntity.toResponse(): AuthUserResponse =
        AuthUserResponse(id = id, email = email, displayName = displayName)
}
