package com.allfolio.api.admin

import com.allfolio.auth.JwtTokenService
import com.allfolio.auth.UserEntity
import com.allfolio.auth.UserRole
import com.allfolio.config.GlobalExceptionHandler
import com.allfolio.config.JwtUserIdFilter
import com.allfolio.config.SecurityConfig
import com.allfolio.config.SseTokenFilter
import com.allfolio.fx.BackfillSummary
import com.allfolio.fx.EcosApiException
import com.allfolio.fx.FxRateBackfillService
import com.allfolio.fx.FxRateService
import org.junit.jupiter.api.Test
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
import org.springframework.test.web.servlet.post
import java.time.LocalDate
import java.util.UUID

/**
 * POST /api/admin/fx/backfill 의 상태 코드 매핑 회귀 방지 (AF-100 Task 11).
 *
 * 이 엔드포인트의 값어치는 실패를 구분해 주는 데 있다. 운영자가 보는 건 상태 코드와 본문뿐인데,
 * 손대지 않으면 ECOS 장애(외부)·0건 응답(설정)·제약 위반(경합)이 전부 같은 500 "서버 오류가 발생했습니다"로
 * 뭉개져 다음 행동이 안 나온다. 그래서 매핑 하나하나를 여기서 못 박는다.
 */
@WebMvcTest(controllers = [FxRateAdminController::class])
@ContextConfiguration(classes = [FxRateAdminBackfillControllerTest.TestApplication::class])
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
class FxRateAdminBackfillControllerTest {

    @SpringBootConfiguration
    class TestApplication

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jwtTokenService: JwtTokenService

    @MockBean private lateinit var fxRateService: FxRateService
    @MockBean private lateinit var backfillService: FxRateBackfillService

    private val from: LocalDate = LocalDate.of(2020, 1, 1)
    private val to: LocalDate = LocalDate.of(2020, 1, 31)

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

    private fun callBackfill(
        currency: String = "USD",
        fromParam: String = "2020-01-01",
        toParam: String = "2020-01-31",
        role: UserRole? = UserRole.ADMIN,
    ) = mockMvc.post("/api/admin/fx/backfill") {
        if (role != null) header("Authorization", "Bearer ${tokenFor(role)}")
        param("currency", currency)
        param("from", fromParam)
        param("to", toParam)
    }

    @Test
    fun `백필은 ADMIN이 아니면 403`() {
        callBackfill(role = UserRole.USER).andExpect { status { isForbidden() } }
        callBackfill(role = null).andExpect { status { isForbidden() } }
    }

    @Test
    fun `성공하면 200과 요약을 그대로 돌려준다`() {
        `when`(backfillService.backfill("USD", from, to)).thenReturn(
            BackfillSummary(
                currency = "USD", from = from, to = to,
                saved = 21, inserted = 18, updated = 2, unchanged = 1,
                skipped = 3, duplicates = 4, outOfRange = 5,
                firstDate = LocalDate.of(2020, 1, 2), lastDate = LocalDate.of(2020, 1, 31),
            )
        )

        callBackfill().andExpect {
            status { isOk() }
            jsonPath("$.currency") { value("USD") }
            jsonPath("$.from") { value("2020-01-01") }
            jsonPath("$.to") { value("2020-01-31") }
            jsonPath("$.saved") { value(21) }
            // 운영자가 실제로 읽는 칸들 — 뭉뚱그려지면 요약을 반환하는 의미가 없다
            jsonPath("$.inserted") { value(18) }
            jsonPath("$.updated") { value(2) }
            jsonPath("$.unchanged") { value(1) }
            jsonPath("$.skipped") { value(3) }
            jsonPath("$.duplicates") { value(4) }
            jsonPath("$.outOfRange") { value(5) }
            jsonPath("$.firstDate") { value("2020-01-02") }
            jsonPath("$.lastDate") { value("2020-01-31") }
        }
    }

    @Test
    fun `0건 응답은 502에 실제 사유를 실어 준다`() {
        `when`(backfillService.backfill("USD", from, to))
            .thenThrow(IllegalStateException("ECOS 응답 0건 — 기존 값을 덮지 않고 중단합니다 (currency=USD)"))

        callBackfill().andExpect {
            status { isBadGateway() }
            jsonPath("$.error") { value("ECOS 응답 0건 — 기존 값을 덮지 않고 중단합니다 (currency=USD)") }
        }
    }

    @Test
    fun `ECOS 오류는 502에 detail과 code를 함께 실어 준다`() {
        `when`(backfillService.backfill("USD", from, to))
            .thenThrow(EcosApiException("INFO-200", "해당 자료가 없습니다."))

        callBackfill().andExpect {
            status { isBadGateway() }
            jsonPath("$.error") { value("해당 자료가 없습니다.") }
            jsonPath("$.code") { value("INFO-200") }
        }
    }

    /**
     * 설정 누락은 상류 장애가 아니라 우리 문제다. 502로 내보내면 운영자가 한국은행 상태를
     * 확인하러 가는데, 실제 할 일은 Render에 ECOS_API_KEY를 등록하는 것이다.
     */
    @Test
    fun `인증키 미설정은 502가 아니라 500이다`() {
        `when`(backfillService.backfill("USD", from, to))
            .thenThrow(EcosApiException("NO_KEY", "ECOS 인증키가 설정되지 않았습니다 (ECOS_API_KEY)"))

        callBackfill().andExpect {
            status { isInternalServerError() }
            // 전역 폴백의 "서버 오류가 발생했습니다"가 아니라 실제 사유가 나가야 한다
            jsonPath("$.error") { value("ECOS 인증키가 설정되지 않았습니다 (ECOS_API_KEY)") }
            jsonPath("$.code") { value("NO_KEY") }
        }
    }

    @Test
    fun `시계열 코드 미설정도 500이다`() {
        `when`(backfillService.backfill("USD", from, to))
            .thenThrow(EcosApiException("NO_SERIES", "ECOS 통계표·항목 코드가 설정되지 않았습니다"))

        callBackfill().andExpect {
            status { isInternalServerError() }
            jsonPath("$.code") { value("NO_SERIES") }
        }
    }

    @Test
    fun `제약 위반은 409로 재실행을 유도한다 - 422가 아니다`() {
        `when`(backfillService.backfill("USD", from, to))
            .thenThrow(DataIntegrityViolationException("uk_fx_rate_daily"))

        callBackfill().andExpect {
            status { isConflict() }
            jsonPath("$.error") { exists() }
        }
    }

    @Test
    fun `설정 없는 통화는 400에 실제 사유를 실어 준다`() {
        `when`(backfillService.backfill("XAU", from, to))
            .thenThrow(IllegalArgumentException("ECOS 시계열 설정이 없는 통화입니다: XAU"))

        callBackfill(currency = "XAU").andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value("ECOS 시계열 설정이 없는 통화입니다: XAU") }
        }
    }

    @Test
    fun `날짜 형식이 깨지면 400 - 서비스는 호출되지 않는다`() {
        callBackfill(fromParam = "2020-13-99").andExpect { status { isBadRequest() } }

        // 바인딩에서 걸러야 한다. 여기까지 통과하면 파싱 안 된 날짜로 ECOS를 때린다.
        verifyNoInteractions(backfillService)
    }

    @Test
    fun `from 파라미터가 없으면 400`() {
        mockMvc.post("/api/admin/fx/backfill") {
            header("Authorization", "Bearer ${tokenFor(UserRole.ADMIN)}")
            param("currency", "USD")
            param("to", "2020-01-31")
        }.andExpect { status { isBadRequest() } }

        verifyNoInteractions(backfillService)
    }

    /**
     * currency에 기본값이 있으면 파라미터 이름 오타(`currncy=JPY`)가 조용히 USD 전 구간 백필로
     * 둔갑한다. 필수로 두면 바인딩이 400으로 잡는다.
     */
    @Test
    fun `currency 파라미터가 없으면 400 - 조용히 USD로 떨어지지 않는다`() {
        mockMvc.post("/api/admin/fx/backfill") {
            header("Authorization", "Bearer ${tokenFor(UserRole.ADMIN)}")
            param("from", "2020-01-01")
            param("to", "2020-01-31")
        }.andExpect { status { isBadRequest() } }

        verifyNoInteractions(backfillService)
    }
}
