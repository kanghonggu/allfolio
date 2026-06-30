package com.allfolio.config

import com.allfolio.auth.JwtTokenService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest(
    classes = [
        SecurityConfigActuatorTest.TestApplication::class,
        SecurityConfig::class,
        JwtUserIdFilter::class,
        SseTokenFilter::class,
        JwtTokenService::class,
    ],
    properties = [
        "management.endpoints.web.exposure.include=health,metrics,prometheus",
        "management.endpoint.health.show-details=when-authorized",
        "management.metrics.export.prometheus.enabled=true",
        "allfolio.auth.jwt-secret=test-secret-test-secret-test-secret-1234",
        "allfolio.auth.access-token-minutes=15",
        "allfolio.cors.allowed-origins=http://localhost:3000",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
    ],
)
@AutoConfigureMockMvc
class SecurityConfigActuatorTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `actuator health는 토큰 없이 공개된다`() {
        mockMvc.get("/actuator/health")
            .andExpect {
                status { isOk() }
            }
    }

    @Test
    fun `actuator metrics는 토큰 없이 차단된다`() {
        mockMvc.get("/actuator/metrics")
            .andExpect {
                status { isUnauthorized() }
            }
    }

    @Test
    fun `actuator prometheus는 토큰 없이 차단된다`() {
        mockMvc.get("/actuator/prometheus")
            .andExpect {
                status { isUnauthorized() }
            }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    class TestApplication
}
