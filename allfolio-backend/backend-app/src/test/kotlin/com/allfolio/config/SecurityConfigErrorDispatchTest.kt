package com.allfolio.config

import com.allfolio.api.admin.FxRateAdminController
import com.allfolio.auth.JwtTokenService
import com.allfolio.auth.UserEntity
import com.allfolio.fx.FxRateService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.math.BigDecimal

/**
 * MockMvc는 서블릿 컨테이너의 ERROR 디스패치(/error 재진입)를 수행하지 않으므로,
 * sendError 이후 시큐리티 필터 체인이 /error 요청을 다시 가로채는 실서버 동작은
 * 실제 내장 톰캣(RANDOM_PORT)으로만 검증할 수 있다.
 */
@SpringBootTest(
    classes = [
        SecurityConfigErrorDispatchTest.TestApplication::class,
        SecurityConfigErrorDispatchTest.TestBeans::class,
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
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
class SecurityConfigErrorDispatchTest {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var jwtTokenService: JwtTokenService

    @Test
    fun `실서버에서 admin 경로는 토큰 없이 403으로 차단된다`() {
        val response = restTemplate.getForEntity("/api/admin/fx/usdtkrw", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `실서버에서 admin 경로는 유효한 토큰이 있어도 403으로 차단된다`() {
        val response = restTemplate.exchange(
            "/api/admin/fx/usdtkrw",
            HttpMethod.GET,
            HttpEntity<Void>(authHeaders()),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `실서버에서 인증된 요청의 404가 401로 뒤바뀌지 않는다`() {
        val response = restTemplate.exchange(
            "/api/no-such-endpoint",
            HttpMethod.GET,
            HttpEntity<Void>(authHeaders()),
            String::class.java,
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    private fun authHeaders(): HttpHeaders =
        HttpHeaders().apply { setBearerAuth(validToken()) }

    private fun validToken(): String =
        jwtTokenService.issue(
            UserEntity(
                email = "error-dispatch-test@example.com",
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
            override fun getCryptoToKrw(symbol: String): BigDecimal = BigDecimal("90000000")
            override fun setCryptoToKrw(symbol: String, rate: BigDecimal) = Unit
        }
    }
}
