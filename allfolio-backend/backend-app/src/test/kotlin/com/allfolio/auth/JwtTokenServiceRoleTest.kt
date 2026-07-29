package com.allfolio.auth

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Date

class JwtTokenServiceRoleTest {

    private val secret = "test-secret-test-secret-test-secret-1234"
    private val service = JwtTokenService(secret, 15)

    private fun user(role: UserRole) = UserEntity(
        email = "u@example.com",
        passwordHash = "hash",
        displayName = null,
        role = role,
    )

    @Test
    fun `issue는 role claim을 싣고 parsePrincipal이 되읽는다 - ADMIN`() {
        val (token, _) = service.issue(user(UserRole.ADMIN))
        val principal = service.parsePrincipal(token)
        assertThat(principal.role).isEqualTo(UserRole.ADMIN)
    }

    @Test
    fun `issue는 role claim을 싣고 parsePrincipal이 되읽는다 - USER`() {
        val (token, _) = service.issue(user(UserRole.USER))
        val principal = service.parsePrincipal(token)
        assertThat(principal.role).isEqualTo(UserRole.USER)
    }

    @Test
    fun `parsePrincipal은 subject를 userId로 되읽는다`() {
        val u = user(UserRole.USER)
        val (token, _) = service.issue(u)
        val principal = service.parsePrincipal(token)
        assertThat(principal.userId).isEqualTo(u.id)
    }

    @Test
    fun `role claim 없는 기존 토큰은 USER로 폴백된다`() {
        val key = Keys.hmacShaKeyFor(secret.toByteArray().copyOf(32))
        val legacyToken = Jwts.builder()
            .subject("11111111-1111-1111-1111-111111111111")
            .claim("email", "u@example.com")
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + 60_000))
            .signWith(key)
            .compact()
        val principal = service.parsePrincipal(legacyToken)
        assertThat(principal.role).isEqualTo(UserRole.USER)
    }
}
