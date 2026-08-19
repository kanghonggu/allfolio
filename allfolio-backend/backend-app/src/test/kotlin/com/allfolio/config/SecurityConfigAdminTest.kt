package com.allfolio.config

import com.allfolio.api.admin.BenchmarkIndexAdminController
import com.allfolio.api.admin.DartAdminController
import com.allfolio.api.admin.FxRateAdminController
import com.allfolio.api.admin.MarketIndexAdminController
import com.allfolio.api.admin.CommodityAdminController
import com.allfolio.api.admin.MarketRateAdminController
import com.allfolio.api.admin.RealAssetValuationAdminController
import com.allfolio.api.market.MarketQueryController
import com.allfolio.api.scheduler.SchedulerTriggerController
import com.allfolio.dart.DartCollectOrchestrator
import com.allfolio.dart.corp.DartCorpMapService
import com.allfolio.market.benchmark.BenchmarkIndexProperties
import com.allfolio.market.benchmark.FscIndexCollectService
import com.allfolio.market.index.IndexCollectService
import com.allfolio.market.index.KisIndexClient
import com.allfolio.market.index.OverseasIndexCollectService
import com.allfolio.market.query.MarketFlags
import com.allfolio.market.query.MarketQueryService
import com.allfolio.market.query.MarketSnapshot
import com.allfolio.market.commodity.CommodityCollectService
import com.allfolio.market.commodity.CommodityProperties
import com.allfolio.market.rate.RateCollectService
import com.allfolio.realasset.RealAssetValuationService
import com.allfolio.auth.JwtTokenService
import com.allfolio.auth.UserEntity
import com.allfolio.auth.UserRole
import com.allfolio.fx.EcosStatListClient
import com.allfolio.fx.FxRateBackfillService
import com.allfolio.fx.FxRateService
import com.allfolio.fx.CashFlowRecomputeService
import com.allfolio.fx.hana.HanaFxCollectService
import com.allfolio.workflow.application.WfStepExecutor
import org.springframework.boot.test.mock.mockito.MockBean
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
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
        CommodityAdminController::class,
        BenchmarkIndexAdminController::class,
        DartAdminController::class,
        RealAssetValuationAdminController::class,
        SchedulerTriggerController::class,
        // AF-104. 어드민이 아니지만 이 컨텍스트에 함께 둔다 — 이 파일이 보는 건 SecurityConfig의
        // 경로 규칙 전체이고(스케줄러 트리거도 여기 있다), 컨텍스트를 하나 더 띄우는 값이 안 된다.
        MarketQueryController::class,
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

    // 금리도 같다 — SchedulerTriggerController가 MarketRateAdminController를,
    // 그쪽이 이 둘을 요구한다 (AF-102).
    @MockBean
    private lateinit var rateCollectService: RateCollectService

    @MockBean
    private lateinit var ecosStatListClient: EcosStatListClient

    // 원자재도 같다 — SchedulerTriggerController가 CommodityAdminController를,
    // 그쪽이 이 둘을 요구한다 (AF-108).
    @MockBean
    private lateinit var commodityCollectService: CommodityCollectService

    @MockBean
    private lateinit var commodityProperties: CommodityProperties

    // 벤치마크 지수도 같다 — SchedulerTriggerController가 BenchmarkIndexAdminController를,
    // 그쪽이 이 둘을 요구한다 (AF-107). **위 overseasIndexCollectService와 같은 함정이다**:
    // 빠뜨리면 이 파일의 테스트가 실패하는 게 아니라 컨텍스트가 아예 안 떠서,
    // DefaultCacheAwareContextLoaderDelegate라는 이 파일과 무관해 보이는 메시지가 전 테스트에 걸린다.
    @MockBean
    private lateinit var fscIndexCollectService: FscIndexCollectService

    @MockBean
    private lateinit var benchmarkIndexProperties: BenchmarkIndexProperties

    // 실물자산 평가도 같다 — SchedulerTriggerController가 RealAssetValuationAdminController를,
    // 그쪽이 이 서비스를 요구한다 (A1 · G5). 위와 같은 함정이다.
    @MockBean
    private lateinit var realAssetValuationService: RealAssetValuationService

    // 마감 트리거는 어드민에 위임하지 않고 WfStepExecutor를 직접 요구한다.
    // 위 overseasIndexCollectService와 같은 함정이다 — 빠뜨리면 이 파일의 테스트가 실패하는 게
    // 아니라 컨텍스트가 아예 안 떠서, 무관해 보이는 실패 메시지가 전 테스트에 걸린다.
    @MockBean
    private lateinit var wfStepExecutor: WfStepExecutor

    // Task 12. SchedulerTriggerController가 DartAdminController를, 그쪽이 이 둘을 요구한다.
    // 위 overseasIndexCollectService와 같은 함정이다 — 빠뜨리면 이 파일의 테스트가 실패하는 게
    // 아니라 컨텍스트가 아예 안 떠서, 무관해 보이는 실패 메시지가 전 테스트에 걸린다.
    @MockBean
    private lateinit var dartCollectOrchestrator: DartCollectOrchestrator

    @MockBean
    private lateinit var dartCorpMapService: DartCorpMapService

    // AF-104. MarketQueryController가 요구한다. 조회 내용은 이 파일의 관심사가 아니라
    // 아래 200 테스트에서 빈 스냅샷 하나만 돌려주게 한다.
    @MockBean
    private lateinit var marketQueryService: MarketQueryService

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

    @Test
    fun `admin 원자재 수집은 토큰 없이 403으로 차단된다`() {
        mockMvc.post("/api/admin/commodity/collect")
            .andExpect { status { isForbidden() } }
    }

    /**
     * AF-107: 벤치마크 지수 수집도 같은 와일드카드 아래 있어야 한다.
     *
     * 여기가 열리면 아무나 공공데이터포털 호출을 돌릴 수 있고, 그 호출은 우리 인증키를 달고 간다 —
     * 무료 키의 일일 호출 한도를 제3자가 태울 수 있다는 뜻이다.
     * 바로 아래 스케줄러 트리거가 permitAll이라 "지수 수집은 열려 있다"는 착각이 쉬운데,
     * 이쪽을 지키는 건 토큰이 아니라 `hasRole("ADMIN")`이다.
     */
    @Test
    fun `admin 벤치마크 지수 수집은 토큰 없이 403으로 차단된다`() {
        mockMvc.post("/api/admin/benchmark-index/collect")
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
     * AF-108: 원자재 트리거도 같은 규칙 아래 있는지. 위 둘과 같은 이유로 503이 곧
     * "컨트롤러까지 닿았다"는 증거다 — 이 경로만 Security에 막히면 크론이 401을 받고
     * 원자재가 한 건도 안 쌓인다.
     */
    @Test
    fun `원자재 트리거도 Security를 통과해 컨트롤러까지 도달한다`() {
        mockMvc.post("/api/internal/scheduler/commodity")
            .andExpect { status { isServiceUnavailable() } }
    }

    /**
     * AF-107: 벤치마크 지수 트리거도 같은 규칙 아래 있는지. 위 셋과 같은 이유로 503이 곧
     * "컨트롤러까지 닿았다"는 증거다 — 이 경로만 Security에 막히면 크론이 401을 받고
     * KOSPI가 한 건도 안 쌓인다. Yahoo 동기화에서 이미 뺐으므로 그때는 **아무도** 채우지 않는다.
     *
     * **503은 `authorize(token)`이 있어야만 나온다.** 그 한 줄을 지우면 토큰 없는 요청이 그대로
     * 어드민 위임까지 내려가 다른 상태 코드가 되고, 그 순간 이 테스트가 잡는다 —
     * 이 경로가 permitAll이라 그 검사가 유일한 인증이다.
     */
    @Test
    fun `벤치마크 지수 트리거도 Security를 통과해 컨트롤러까지 도달한다`() {
        mockMvc.post("/api/internal/scheduler/benchmark-index")
            .andExpect { status { isServiceUnavailable() } }
    }

    /**
     * 마감 트리거도 같은 규칙 아래 있는지. 위 둘과 같은 이유로 503이 곧 "컨트롤러까지 닿았다"다.
     *
     * 이 경로만 Security에 막히면 크론은 401을 받고 마감이 한 번도 안 돈다 — 그게 정확히
     * `performance_daily`가 안 쌓이던 원래 증상이라, 같은 실패를 다른 원인으로 다시 겪게 된다.
     * 컨트롤러 테스트는 standaloneSetup이라 Security가 아예 안 돌아 여기서만 잡힌다.
     */
    @Test
    fun `마감 트리거도 Security를 통과해 컨트롤러까지 도달한다`() {
        mockMvc.post("/api/internal/scheduler/closing")
            .andExpect { status { isServiceUnavailable() } }
    }

    /**
     * AF-104: `GET /api/market`은 로그인해야 보인다.
     *
     * **경로와 기본 차단을 동시에 못 박는 유일한 테스트다.** 컨트롤러 테스트는 서비스를 목으로 두고
     * 메서드를 직접 부르므로 Spring MVC도 Security도 안 돈다 — 매핑이 `/api/market`이라는 것도,
     * `MarketQueryController` KDoc이 주장하는 "`.anyRequest().authenticated()`가 이미 잡는다"도
     * 거기서는 검증되지 않는다.
     *
     * 지금은 catch-all이 자동으로 덮지만, 그 규칙이 경로 열거로 바뀌거나 이 경로를 permitAll 하는
     * 한 줄이 위에 끼어드는 날 여기서 잡는다(matcher는 먼저 걸리는 쪽이 이긴다).
     * 있을 법한 일이다 — `/api/sse/prices`가 이미 permitAll이라 "시세는 공개"라는 유추가 쉽다.
     * 그런데 이 경로가 열리면 지수 데이터가 익명 제3자에게 나간다. 재배포 권한이 확인되지 않은
     * 시세를 내보내지 않겠다는 것이 AF-108이고, 그게 정확히 이 플래그가 막으려는 상황이다.
     */
    @Test
    fun `시장 조회는 토큰 없이 401로 차단된다`() {
        mockMvc.get("/api/market")
            .andExpect { status { isUnauthorized() } }
    }

    /**
     * 위 401만으로는 **경로가 못 박히지 않는다.** Security 필터는 DispatcherServlet보다 먼저 돌아,
     * `/api/market`에 매핑된 핸들러가 아예 없어도 catch-all이 똑같이 401을 준다 —
     * `@RequestMapping`을 다른 경로로 바꾸는 변이가 그대로 통과한다.
     * 로그인한 사용자로 200이 나오는 것까지 봐야 "이 경로에 핸들러가 있고, 그 핸들러가
     * 인증만 통과하면 닿는다"가 둘 다 참이 된다.
     */
    @Test
    fun `시장 조회는 USER 토큰이면 200으로 허용된다`() {
        `when`(marketQueryService.snapshot()).thenReturn(
            MarketSnapshot(
                domestic = null,
                overseas = null,
                fx = null,
                rates = emptyList(),
                commodities = null,
                flags = MarketFlags(indicesEnabled = true, commoditiesEnabled = true),
            ),
        )

        mockMvc.get("/api/market") {
            header("Authorization", "Bearer ${tokenFor(UserRole.USER)}")
        }.andExpect { status { isOk() } }
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
