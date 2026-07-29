package com.allfolio.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AuthServiceRoleResponseTest {

    @Test
    fun `AuthUserResponse는 role을 담는다`() {
        val resp = AuthUserResponse(
            id = java.util.UUID.randomUUID(),
            email = "u@example.com",
            displayName = null,
            role = UserRole.ADMIN,
        )
        assertThat(resp.role).isEqualTo(UserRole.ADMIN)
    }
}
