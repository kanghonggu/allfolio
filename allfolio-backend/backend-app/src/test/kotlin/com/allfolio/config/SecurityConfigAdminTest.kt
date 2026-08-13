package com.allfolio.config

import com.allfolio.api.admin.FxRateAdminController
import com.allfolio.api.admin.MarketIndexAdminController
import com.allfolio.api.admin.MarketRateAdminController
import com.allfolio.api.scheduler.SchedulerTriggerController
import com.allfolio.market.index.IndexCollectService
import com.allfolio.market.index.KisIndexClient
import com.allfolio.market.rate.RateCollectService
import com.allfolio.auth.JwtTokenService
import com.allfolio.auth.UserEntity
import com.allfolio.auth.UserRole
import com.allfolio.fx.EcosStatListClient
import com.allfolio.fx.FxRateBackfillService
import com.allfolio.fx.FxRateService
import com.allfolio.fx.hana.HanaFxCollectService
import org.springframework.boot.test.mock.mockito.MockBean
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
        MarketIndexAdminController::class,
        MarketRateAdminController::class,
        SchedulerTriggerController::class,
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

    // 이 테스트가 보는 건 인가 경로뿐이라 백필·하나은행 수집은 호출되지 않는다.
    // FxRateAdminController가 요구하므로 자리만 채운다.
    @MockBean
    private lateinit var fxRateBackfillService: FxRateBackfillService

    @MockBean
    private lateinit var hanaFxCollectService: HanaFxCollectService

    // 같은 이유로 자리만 채운다 — SchedulerTriggerController가 MarketIndexAdminController를,
    // 그쪽이 이 둘을 요구한다 (AF-101).
    @MockBean
    private lateinit var kisIndexClient: KisIndexClient

    @MockBean
    private lateinit var indexCollectService: IndexCollectService

    // 금리도 같다 — SchedulerTriggerController가 MarketRateAdminController를,
    // 그쪽이 이 둘을 요구한다 (AF-102).
    @MockBean
    private lateinit var rateCollectService: RateCollectService

    @MockBean
    private lateinit var ecosStatListClient: EcosStatListClient

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
    fun `admin FX 조회는 USER 토큰이면 403으로 차단된다`() {
        mockMvc.get("/api/admin/fx/usdtkrw") {
            header("Authorization", "Bearer ${tokenFor(UserRole.USER)}")
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `admin FX 변경은 USER 토큰이면 403으로 차단된다`() {
        mockMvc.put("/api/admin/fx/usdtkrw") {
            header("Authorization", "Bearer ${tokenFor(UserRole.USER)}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"rate":1350}"""
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `admin FX 조회는 ADMIN 토큰이면 200으로 허용된다`() {
        mockMvc.get("/api/admin/fx/usdtkrw") {
            header("Authorization", "Bearer ${tokenFor(UserRole.ADMIN)}")
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `admin FX 변경은 ADMIN 토큰이면 200으로 허용된다`() {
        mockMvc.put("/api/admin/fx/usdtkrw") {
            header("Authorization", "Bearer ${tokenFor(UserRole.ADMIN)}")
            contentType = MediaType.APPLICATION_JSON
            content = """{"rate":1350}"""
        }.andExpect { status { isOk() } }
    }

    private fun tokenFor(role: UserRole): String =
        jwtTokenService.issue(
            UserEntity(
                email = "security-test@example.com",
                passwordHash = "hash",
                displayName = null,
                role = role,
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

    @Test
    fun `admin USD 환율 조회는 토큰 없이 403으로 차단된다`() {
        mockMvc.get("/api/admin/fx/usdkrw").andExpect { status { isForbidden() } }
    }

    @Test
    fun `admin USD 환율 조회는 ADMIN 토큰이면 200으로 허용된다`() {
        mockMvc.get("/api/admin/fx/usdkrw") {
            header("Authorization", "Bearer ${tokenFor(UserRole.ADMIN)}")
        }.andExpect { status { isOk() } }
    }

    /**
     * AF-102: 금리 수집은 SecurityConfig의 "/api/admin 이하 전부" 와일드카드가 hasRole("ADMIN")로 덮는다.
     *
     * 지금 뚫린 구멍이 있어서 넣는 테스트가 아니라, 그 와일드카드를 가릴 수 있는 규칙이
     * 나중에 끼어드는 걸 막으려고 못을 박는 것이다 — matcher는 먼저 걸리는 쪽이 이기므로
     * 그 줄보다 위에 rate 경로를 permitAll 하는 한 줄이 들어오면 수집 엔드포인트가
     * 인증 없이 열린다. 컨트롤러가 이미 이 컨텍스트에 있어 비용이 거의 없다.
     *
     * (경로 와일드카드를 이 주석에 그대로 쓰지 말 것 — 슬래시+별표가 Kotlin의 중첩 블록 주석을
     * 열어 버려 파일 끝까지 주석이 안 닫힌다.)
     */
    @Test
    fun `admin 금리 수집은 토큰 없이 403으로 차단된다`() {
        mockMvc.post("/api/admin/rate/collect")
            .andExpect { status { isForbidden() } }
    }

    /**
     * AF-103: 스케줄러 트리거는 Security를 통과해 컨트롤러까지 도달해야 한다.
     *
     * 이 컨텍스트에는 scheduler.trigger-token 프로퍼티가 없어 기본값 빈 문자열이 주입되고,
     * 컨트롤러가 503으로 닫는다. **503이 나온다는 것 자체가 요청이 컨트롤러에 닿았다는 증거다** —
     * permitAll이 빠져 있으면 Security가 먼저 401로 끊어 503이 나올 수 없다.
     */
    @Test
    fun `스케줄러 트리거는 Security를 통과해 컨트롤러까지 도달한다`() {
        mockMvc.post("/api/internal/scheduler/fx/hana-collect")
            .andExpect { status { isServiceUnavailable() } }
    }
}
