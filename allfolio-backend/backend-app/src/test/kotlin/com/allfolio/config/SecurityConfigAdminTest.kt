package com.allfolio.config

import com.allfolio.api.admin.FxRateAdminController
import com.allfolio.auth.JwtTokenService
import com.allfolio.auth.UserEntity
import com.allfolio.fx.FxRateService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import java.math.BigDecimal

@SpringBootTest(
    classes = [
        SecurityConfigAdminTest.TestApplication::class,
        SecurityConfigAdminTest.TestBeans::class,
        SecurityConfig::class,
        JwtUserIdFilter::class,
        SseTokenFilter::class,
        JwtTokenService::class,
        FxRateAdminController::class,
    ],
    properties = [
        "allfolio.auth.jwt-secret=test-secret-test-secret-test-secret-1234",
        "allfolio.auth.access-token-minutes=15",
        "allfolio.cors.allowed-origins=http://localhost:3000",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
    ],
)
@AutoConfigureMockMvc
class SecurityConfigAdminTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jwtTokenService: JwtTokenService

    @Test
    fun `admin FX 조회는 토큰 없이 403으로 차단된다`() {
        mockMvc.get("/api/admin/fx/usdtkrw")
            .andExpect {
                status { isForbidden() }
            }
    }

    @Test
    fun `admin FX 변경은 토큰 없이 403으로 차단된다`() {
        mockMvc.put("/api/admin/fx/usdtkrw") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"rate":1350}"""
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `admin FX 조회는 유효한 토큰이 있어도 403으로 차단된다`() {
        mockMvc.get("/api/admin/fx/usdtkrw") {
            header("Authorization", "Bearer ${validToken()}")
        }.andExpect {
            status { isForbidden() }
        }
    }

    @Test
    fun `admin FX 변경은 유효한 토큰이 있어도 403으로 차단된다`() {
        mockMvc.put("/api/admin/fx/usdtkrw") {
            header("Authorization", "Bearer ${validToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"rate":1350}"""
        }.andExpect {
            status { isForbidden() }
        }
    }

    private fun validToken(): String =
        jwtTokenService.issue(
            UserEntity(
                email = "security-test@example.com",
                passwordHash = "hash",
                displayName = null,
            ),
        ).first

    @SpringBootConfiguration
    @EnableAutoConfiguration
    class TestApplication

    @TestConfiguration
    class TestBeans {
        @Bean
        fun fxRateService(): FxRateService = object : FxRateService {
            override fun getUsdtToKrw(): BigDecimal = BigDecimal("1350")

            override fun setUsdtToKrw(rate: BigDecimal) = Unit
        }
    }
}
