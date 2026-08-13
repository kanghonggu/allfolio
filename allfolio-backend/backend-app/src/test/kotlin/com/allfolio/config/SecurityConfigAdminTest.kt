package com.allfolio.config

import com.allfolio.api.admin.FxRateAdminController
import com.allfolio.api.admin.MarketIndexAdminController
import com.allfolio.api.scheduler.SchedulerTriggerController
import com.allfolio.market.index.IndexCollectService
import com.allfolio.market.index.KisIndexClient
import com.allfolio.market.index.OverseasIndexCollectService
import com.allfolio.auth.JwtTokenService
import com.allfolio.auth.UserEntity
import com.allfolio.auth.UserRole
import com.allfolio.fx.FxRateBackfillService
import com.allfolio.fx.FxRateService
import com.allfolio.fx.CashFlowRecomputeService
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

    @MockBean
    private lateinit var cashFlowRecomputeService: CashFlowRecomputeService

    // 같은 이유로 자리만 채운다 — SchedulerTriggerController가 MarketIndexAdminController를,
    // 그쪽이 이 둘을 요구한다 (AF-101).
    @MockBean
    private lateinit var kisIndexClient: KisIndexClient

    @MockBean
    private lateinit var indexCollectService: IndexCollectService

    // AF-110. **이 자리를 빠뜨리면 테스트가 실패하는 게 아니라 컨텍스트가 아예 안 뜬다** —
    // 실패 메시지가 DefaultCacheAwareContextLoaderDelegate라 이 파일과 무관해 보인다.
    // MarketIndexAdminController에 생성자 인자를 늘릴 때마다 여기도 늘려야 한다.
    @MockBean
    private lateinit var overseasIndexCollectService: OverseasIndexCollectService

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

    /**
     * AF-110: 해외 지수 트리거도 같은 규칙 아래 있는지. 위 트리거와 같은 이유로 503이 곧
     * "컨트롤러까지 닿았다"는 증거다(`schedule`을 실어 보내야 한다 — 빠지면 파라미터 해석
     * 단계에서 400이 나 인가 경로를 안 지난다).
     *
     * 경로 규칙이 `api/internal/scheduler` 하위 전체를 덮는 와일드카드라 지금은 자동으로
     * 걸리지만, 그 규칙이 경로 열거로 바뀌는 날 새 경로만 401로 막히는 걸 여기서 잡는다.
     * (와일드카드 표기를 이 주석에 그대로 쓰면 안 된다 — Kotlin 블록 주석은 중첩돼서
     * 슬래시-별-별이 주석 안에서 새 주석을 열고 파일 끝까지 삼킨다. 실제로 겪었다.)
     */
    @Test
    fun `해외 지수 트리거도 Security를 통과해 컨트롤러까지 도달한다`() {
        mockMvc.post("/api/internal/scheduler/index/overseas?schedule=US")
            .andExpect { status { isServiceUnavailable() } }
    }

    /**
     * 반대로 어드민 수집 엔드포인트는 **닫혀 있어야** 한다. 스케줄러 경로가 permitAll이라
     * 위임 대상인 이 엔드포인트까지 열린 것처럼 착각하기 쉬운데, 이쪽은 토큰 검사가 아니라
     * `hasRole("ADMIN")`이 지킨다. 열리면 아무나 KIS 호출을 돌릴 수 있다.
     */
    @Test
    fun `해외 지수 수집 어드민 엔드포인트는 토큰 없이 403으로 차단된다`() {
        mockMvc.post("/api/admin/market-index/collect-overseas?schedule=US")
            .andExpect { status { isForbidden() } }
    }
}
