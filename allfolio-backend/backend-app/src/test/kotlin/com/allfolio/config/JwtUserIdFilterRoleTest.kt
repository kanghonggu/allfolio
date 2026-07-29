package com.allfolio.config

import com.allfolio.auth.JwtTokenService
import com.allfolio.auth.UserEntity
import com.allfolio.auth.UserRole
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder

class JwtUserIdFilterRoleTest {

    private val jwt = JwtTokenService("test-secret-test-secret-test-secret-1234", 15)
    private val filter = JwtUserIdFilter(jwt)

    @AfterEach
    fun tearDown() = SecurityContextHolder.clearContext()

    private fun tokenFor(role: UserRole): String =
        jwt.issue(UserEntity(email = "u@example.com", passwordHash = "h", displayName = null, role = role)).first

    private fun authoritiesAfterFilter(role: UserRole): Set<String> {
        val req = MockHttpServletRequest().apply {
            addHeader("Authorization", "Bearer ${tokenFor(role)}")
        }
        val res = MockHttpServletResponse()
        val chain = FilterChain { _, _ -> }
        filter.doFilter(req, res, chain)
        return SecurityContextHolder.getContext().authentication.authorities.map { it.authority }.toSet()
    }

    @Test
    fun `ADMIN 토큰은 ROLE_ADMIN authority를 부여한다`() {
        assertThat(authoritiesAfterFilter(UserRole.ADMIN)).contains("ROLE_ADMIN")
    }

    @Test
    fun `USER 토큰은 ROLE_USER authority를 부여한다`() {
        assertThat(authoritiesAfterFilter(UserRole.USER)).contains("ROLE_USER")
    }
}
