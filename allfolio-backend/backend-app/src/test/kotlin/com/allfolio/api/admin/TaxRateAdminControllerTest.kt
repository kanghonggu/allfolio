package com.allfolio.api.admin

import com.allfolio.auth.JwtTokenService
import com.allfolio.auth.UserEntity
import com.allfolio.auth.UserRole
import com.allfolio.config.JwtUserIdFilter
import com.allfolio.config.SecurityConfig
import com.allfolio.config.SseTokenFilter
import com.allfolio.unifiedasset.application.port.TaxRateRepository
import com.allfolio.unifiedasset.application.usecase.TaxRateService
import com.allfolio.unifiedasset.domain.tax.IncomeType
import com.allfolio.unifiedasset.domain.tax.TaxRate
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
import org.springframework.test.web.servlet.post
import java.time.LocalDate
import java.util.UUID

@SpringBootTest(
    classes = [
        TaxRateAdminControllerTest.TestApplication::class,
        TaxRateAdminControllerTest.TestBeans::class,
        SecurityConfig::class,
        JwtUserIdFilter::class,
        SseTokenFilter::class,
        JwtTokenService::class,
        TaxRateAdminController::class,
    ],
    properties = [
        "allfolio.auth.jwt-secret=test-secret-test-secret-test-secret-1234",
        "allfolio.auth.access-token-minutes=15",
        "allfolio.cors.allowed-origins=http://localhost:3000",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
    ],
)
@AutoConfigureMockMvc
class TaxRateAdminControllerTest {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jwtTokenService: JwtTokenService

    private fun tokenFor(role: UserRole): String =
        jwtTokenService.issue(UserEntity(email = "t@example.com", passwordHash = "h", displayName = null, role = role)).first

    @Test
    fun `목록은 무토큰이면 403`() {
        mockMvc.get("/api/admin/tax-rates").andExpect { status { isForbidden() } }
    }

    @Test
    fun `목록은 USER 토큰이면 403`() {
        mockMvc.get("/api/admin/tax-rates") {
            header("Authorization", "Bearer ${tokenFor(UserRole.USER)}")
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `목록은 ADMIN 토큰이면 200`() {
        mockMvc.get("/api/admin/tax-rates") {
            header("Authorization", "Bearer ${tokenFor(UserRole.ADMIN)}")
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `등록은 ADMIN 토큰이면 200`() {
        mockMvc.post("/api/admin/tax-rates") {
            header("Authorization", "Bearer ${tokenFor(UserRole.ADMIN)}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"country":"US","incomeType":"DIVIDEND","rate":15,"effectiveStart":"2024-01-01"}"""
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `등록은 세율 범위 밖이면 400`() {
        mockMvc.post("/api/admin/tax-rates") {
            header("Authorization", "Bearer ${tokenFor(UserRole.ADMIN)}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"country":"US","incomeType":"DIVIDEND","rate":51,"effectiveStart":"2024-01-01"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    class TestApplication

    @TestConfiguration
    class TestBeans {
        @Bean
        fun taxRateRepository(): TaxRateRepository = object : TaxRateRepository {
            val store = mutableListOf<TaxRate>()
            override fun findAll() = store.toList()
            override fun findOpen(country: String, incomeType: IncomeType) =
                store.firstOrNull { it.country == country && it.incomeType == incomeType && it.effectiveEnd == null }
            override fun findEffective(country: String, incomeType: IncomeType, date: LocalDate) = null
            override fun save(taxRate: TaxRate): TaxRate { store.removeIf { it.id == taxRate.id }; store.add(taxRate); return taxRate }
        }

        @Bean
        fun taxRateService(repo: TaxRateRepository) = TaxRateService(repo)
    }
}
