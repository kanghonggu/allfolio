package com.allfolio.auth

import com.allfolio.account.AccountDeletionService
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.web.server.ResponseStatusException
import java.util.Optional
import java.util.UUID

class AuthServiceDeleteAccountTest {

    private val userRepository = mock(UserRepository::class.java)
    private val refreshTokenRepository = mock(RefreshTokenRepository::class.java)
    private val passwordEncoder = mock(PasswordEncoder::class.java)
    private val jwtTokenService = mock(JwtTokenService::class.java)
    private val accountDeletionService = mock(AccountDeletionService::class.java)

    private val service = AuthService(
        userRepository, refreshTokenRepository, passwordEncoder, jwtTokenService,
        30L, accountDeletionService,
    )

    private val userId = UUID.randomUUID()
    private val user = UserEntity(id = userId, email = "u@example.com", passwordHash = "hash", displayName = null)

    @Test
    fun `deleteAccount purges when password matches`() {
        `when`(userRepository.findById(userId)).thenReturn(Optional.of(user))
        `when`(passwordEncoder.matches("pw", "hash")).thenReturn(true)

        service.deleteAccount(userId, "pw")

        verify(accountDeletionService).purge(userId)
    }

    @Test
    fun `deleteAccount rejects wrong password and purges nothing`() {
        `when`(userRepository.findById(userId)).thenReturn(Optional.of(user))
        `when`(passwordEncoder.matches("wrong", "hash")).thenReturn(false)

        assertThrows(ResponseStatusException::class.java) {
            service.deleteAccount(userId, "wrong")
        }
        verify(accountDeletionService, never()).purge(userId)
    }

    @Test
    fun `deleteAccount throws when user missing`() {
        `when`(userRepository.findById(userId)).thenReturn(Optional.empty())

        assertThrows(ResponseStatusException::class.java) {
            service.deleteAccount(userId, "pw")
        }
        verify(accountDeletionService, never()).purge(userId)
    }
}
