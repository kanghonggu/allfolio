package com.allfolio.api.admin

import com.allfolio.auth.JwtTokenService
import com.allfolio.auth.UserEntity
import com.allfolio.auth.UserRole
import com.allfolio.config.GlobalExceptionHandler
import com.allfolio.config.JwtUserIdFilter
import com.allfolio.config.SecurityConfig
import com.allfolio.config.SseTokenFilter
import com.allfolio.fx.FxRateBackfillService
import com.allfolio.fx.FxRateService
import com.allfolio.fx.hana.HanaCollectSummary
import com.allfolio.fx.hana.HanaFxCollectService
import com.allfolio.fx.hana.HanaFxParseException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import java.util.UUID

/**
 * POST /api/admin/fx/hana/collect · GET /api/admin/fx/usdkrw 회귀 방지 (AF-99 Task 11).
 *
 * 이 엔드포인트의 값어치는 **실패를 구분해 주는 데** 있다. 운영자가 손에 쥐는 건 상태 코드와 본문뿐인데,
 * 손대지 않으면 안전장치 거부(우리 판단)·마크업 변경(하나은행)·동시 실행(경합)이 전부 같은 500
 * "서버 오류가 발생했습니다"로 뭉개져 다음 행동이 나오지 않는다. 그래서 매핑 하나하나를 여기서 못 박는다.
 *
 * 특히 422/502의 경계가 핵심이다 — 502를 본 운영자는 하나은행이 깨졌는지 확인하러 가지만,
 * 안전장치 거부는 응답이 정상적으로 왔고 **우리가** 검사해서 거부한 것이라 할 일이 완전히 다르다
 * (값을 눈으로 확인하고 `force=true`로 다시 돌리는 것).
 */
@WebMvcTest(controllers = [FxRateAdminController::class])
@ContextConfiguration(classes = [FxRateAdminHanaControllerTest.TestApplication::class])
@Import(
    FxRateAdminController::class,
    SecurityConfig::class,
    SseTokenFilter::class,
    JwtUserIdFilter::class,
    JwtTokenService::class,
    GlobalExceptionHandler::class,
)
@TestPropertySource(
    properties = [
        "allfolio.cors.allowed-origins=http://localhost:3000",
        "allfolio.auth.jwt-secret=01234567890123456789012345678901",
        "allfolio.auth.access-token-minutes=60",
    ]
)
class FxRateAdminHanaControllerTest {

    @SpringBootConfiguration
    class TestApplication

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jwtTokenService: JwtTokenService

    @MockBean private lateinit var fxRateService: FxRateService
    @MockBean private lateinit var backfillService: FxRateBackfillService
    @MockBean private lateinit var hanaCollectService: HanaFxCollectService

    private val requested: LocalDate = LocalDate.of(2026, 8, 12)
    private val kstToday: LocalDate get() = LocalDate.now(ZoneId.of("Asia/Seoul"))

    private fun summary(
        requestedDate: LocalDate = requested,
        baseDate: LocalDate = LocalDate.of(2026, 8, 11),
    ) = HanaCollectSummary(
        requestedDate = requestedDate,
        baseDate = baseDate,
        roundNo = 314,
        currencies = 23,
        inserted = 20,
        updated = 2,
        unchanged = 1,
        skipped = 4,
    )

    /**
     * 목이 실제로 받은 인자.
     *
     * `ArgumentCaptor`를 쓰지 않는다 — `capture()`는 null을 돌려주는데 [HanaFxCollectService.collect]의
     * 파라미터가 둘 다 non-null(하나는 primitive `Boolean`)이라 호출 지점에 Kotlin 널검사·언박싱이 붙어
     * NPE로 죽는다. mockito-kotlin이 있으면 우회되지만 이 프로젝트에는 없다.
     * `mockingDetails`는 매처를 거치지 않고 기록된 호출을 그대로 읽으므로 그 함정이 없고,
     * 날짜와 force를 한자리에서 같이 본다.
     */
    private fun collectArgs(): List<Any?> =
        mockingDetails(hanaCollectService).invocations.single().arguments.toList()

    private fun tokenFor(role: UserRole): String =
        jwtTokenService.issue(
            UserEntity(
                id = UUID.randomUUID(),
                email = "${UUID.randomUUID()}@example.com",
                passwordHash = "hash",
                displayName = "Test Admin",
                role = role,
            )
        ).first

    private fun callCollect(
        dateParam: String? = "2026-08-12",
        forceParam: String? = null,
        role: UserRole? = UserRole.ADMIN,
    ) = mockMvc.post("/api/admin/fx/hana/collect") {
        if (role != null) header("Authorization", "Bearer ${tokenFor(role)}")
        if (dateParam != null) param("date", dateParam)
        if (forceParam != null) param("force", forceParam)
    }

    // ---------------------------------------------------------------- 수집

    @Test
    fun `수집은 ADMIN이 아니면 403`() {
        callCollect(role = UserRole.USER).andExpect { status { isForbidden() } }
        callCollect(role = null).andExpect { status { isForbidden() } }

        verifyNoInteractions(hanaCollectService)
    }

    @Test
    fun `성공하면 200과 요약을 그대로 돌려준다`() {
        `when`(hanaCollectService.collect(requested, false)).thenReturn(summary())

        callCollect().andExpect {
            status { isOk() }
            jsonPath("$.requestedDate") { value("2026-08-12") }
            // 조회일자가 아니라 하나은행이 답한 기준일이 나가야 한다 — 연휴엔 둘이 다르고,
            // 저장 키가 기준일이라 운영자가 확인해야 하는 값은 이쪽이다
            jsonPath("$.baseDate") { value("2026-08-11") }
            jsonPath("$.roundNo") { value(314) }
            jsonPath("$.currencies") { value(23) }
            jsonPath("$.inserted") { value(20) }
            jsonPath("$.updated") { value(2) }
            jsonPath("$.unchanged") { value(1) }
            jsonPath("$.skipped") { value(4) }
        }
    }

    /**
     * 안전장치가 막은 건 하나은행 잘못이 아니다 — 응답은 정상적으로 왔고 우리가 검사해서 거부했다.
     * 502로 내보내면 운영자가 은행 상태를 확인하러 가는데, 실제 할 일은 값을 눈으로 보고
     * `force=true`로 다시 돌릴지 정하는 것이다. 그리고 그 판단은 **사유 문구**가 있어야 가능하다.
     */
    @Test
    fun `안전장치 거부는 422에 실제 사유를 실어 준다 - 502가 아니다`() {
        `when`(hanaCollectService.collect(requested, false))
            .thenThrow(IllegalStateException("안전장치에 걸려 저장하지 않았습니다: USD 변동 3.10% > 2.0%"))

        callCollect().andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.error") { value("안전장치에 걸려 저장하지 않았습니다: USD 변동 3.10% > 2.0%") }
        }
    }

    @Test
    fun `응답을 신뢰할 수 없으면 502`() {
        `when`(hanaCollectService.collect(requested, false))
            .thenThrow(HanaFxParseException("11컬럼 행이 있는 환율 테이블을 찾지 못했습니다"))

        callCollect().andExpect {
            status { isBadGateway() }
            jsonPath("$.error") { value("하나은행 응답 파싱 실패: 11컬럼 행이 있는 환율 테이블을 찾지 못했습니다") }
        }
    }

    @Test
    fun `제약 위반은 409로 재실행을 유도한다 - 422가 아니다`() {
        `when`(hanaCollectService.collect(requested, false))
            .thenThrow(DataIntegrityViolationException("uk_hana_fx_quote"))

        callCollect().andExpect {
            status { isConflict() }
            jsonPath("$.error") { value(org.hamcrest.Matchers.containsString("다시 실행")) }
        }
    }

    /**
     * 서버 기본 타임존은 UTC일 수도 있다(Render 컨테이너가 그렇다). 그때 `LocalDate.now()`를 쓰면
     * KST 오전 9시 이전에는 "어제"를 조회하게 되고, 현재고시(pbldDvCd=3)가 아니라
     * 최종고시(0) 경로로 새서 오늘 회차가 영영 안 들어온다.
     */
    /**
     * JVM 기본 타임존을 잠시 UTC-12로 바꾼다. 개발 기기가 KST면 `LocalDate.now()`와
     * `LocalDate.now(KST)`가 늘 같은 값이라, 컨트롤러가 기본 타임존을 쓰도록 퇴행해도
     * 이 테스트가 못 잡는다. 두 존이 21시간 벌어져 있으면 하루의 대부분에서 날짜가 갈리므로
     * 그 퇴행이 실제로 걸린다. (완전 결정적이려면 Clock을 주입해야 하는데,
     * 어드민 엔드포인트 하나 때문에 컨트롤러에 시계를 끼울 값어치는 없다.)
     */
    @Test
    fun `date를 생략하면 KST 오늘로 수집한다`() {
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Etc/GMT+12"))
        try {
            val expected = kstToday
            `when`(hanaCollectService.collect(expected, false))
                .thenReturn(summary(requestedDate = expected))

            callCollect(dateParam = null).andExpect { status { isOk() } }

            // force도 함께 본다 — 생략 시 기본값이 true로 새면 안전장치가 늘 뚫린 채로 돈다
            assertThat(collectArgs()).containsExactly(expected, false)
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun `force는 서비스에 그대로 전달된다`() {
        `when`(hanaCollectService.collect(requested, true)).thenReturn(summary())

        callCollect(forceParam = "true").andExpect { status { isOk() } }

        // 컨트롤러가 조용히 false로 떨어뜨리면 안전장치를 푸는 방법 자체가 사라진다
        assertThat(collectArgs()).containsExactly(requested, true)
    }

    @Test
    fun `날짜 형식이 깨지면 400 - 서비스는 호출되지 않는다`() {
        callCollect(dateParam = "2026-13-99").andExpect { status { isBadRequest() } }

        verifyNoInteractions(hanaCollectService)
    }

    // ---------------------------------------------------------------- USD 조회

    /**
     * `usdtkrw`만 있으면 하나은행 전환 뒤 평가 경로가 실제로 무엇을 쓰는지 확인할 방법이 없다 —
     * 두 값이 김치 프리미엄만큼 벌어지는 게 정상인데, 그 차이를 볼 창이 없으면
     * "환율이 이상하다"는 신고가 들어와도 고시 반영 여부를 판별할 수 없다.
     */
    @Test
    fun `usdkrw는 평가 경로가 쓰는 값을 돌려준다`() {
        `when`(fxRateService.getUsdToKrw()).thenReturn(BigDecimal("1389.4000"))

        mockMvc.get("/api/admin/fx/usdkrw") {
            header("Authorization", "Bearer ${tokenFor(UserRole.ADMIN)}")
        }.andExpect {
            status { isOk() }
            jsonPath("$.usdKrw") { value(1389.4000) }
        }
    }

    /** USDT 경로와 섞이면 이 엔드포인트를 만든 이유가 사라진다 */
    @Test
    fun `usdkrw는 usdtkrw를 호출하지 않는다`() {
        `when`(fxRateService.getUsdToKrw()).thenReturn(BigDecimal("1389.4000"))

        mockMvc.get("/api/admin/fx/usdkrw") {
            header("Authorization", "Bearer ${tokenFor(UserRole.ADMIN)}")
        }.andExpect { status { isOk() } }

        verify(fxRateService).getUsdToKrw()
        verify(fxRateService, never()).getUsdtToKrw()
    }

    @Test
    fun `usdkrw는 ADMIN이 아니면 403`() {
        mockMvc.get("/api/admin/fx/usdkrw") {
            header("Authorization", "Bearer ${tokenFor(UserRole.USER)}")
        }.andExpect { status { isForbidden() } }

        mockMvc.get("/api/admin/fx/usdkrw").andExpect { status { isForbidden() } }

        verifyNoInteractions(fxRateService)
    }
}
