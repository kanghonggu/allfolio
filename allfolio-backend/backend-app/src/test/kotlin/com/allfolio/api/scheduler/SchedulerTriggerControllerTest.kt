package com.allfolio.api.scheduler

import com.allfolio.api.admin.FxRateAdminController
import com.allfolio.api.admin.MarketIndexAdminController
import com.allfolio.api.admin.MarketRateAdminController
import com.allfolio.config.GlobalExceptionHandler
import com.allfolio.fx.BackfillSummary
import com.allfolio.fx.CashFlowRecomputeService
import com.allfolio.fx.EcosStatListClient
import com.allfolio.fx.FxRateBackfillService
import com.allfolio.fx.FxRateService
import com.allfolio.fx.hana.HanaCollectSummary
import com.allfolio.fx.hana.HanaFxCollectService
import com.allfolio.market.index.DomesticIndexCollectSummary
import com.allfolio.market.index.IndexCollectService
import com.allfolio.market.index.IndexSlot
import com.allfolio.market.index.KisIndexClient
import com.allfolio.market.index.KisIndexException
import com.allfolio.market.index.OverseasIndexCollectService
import com.allfolio.market.index.OverseasIndexCollectSummary
import com.allfolio.market.index.OverseasSchedule
import com.allfolio.market.rate.RateCollectService
import com.allfolio.market.rate.RateCollectSummary
import com.allfolio.workflow.application.WfRunSummary
import com.allfolio.workflow.application.WfStepExecutor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.ResponseEntity
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

class SchedulerTriggerControllerTest {

    // JUnit5는 테스트마다 인스턴스를 새로 만들므로 목이 테스트 간에 새지 않는다.
    private val admin: FxRateAdminController = mock(FxRateAdminController::class.java)
    private val indexAdmin: MarketIndexAdminController = mock(MarketIndexAdminController::class.java)
    private val rateAdmin: MarketRateAdminController = mock(MarketRateAdminController::class.java)
    private val stepExecutor: WfStepExecutor = mock(WfStepExecutor::class.java)

    private val summary = HanaCollectSummary(
        requestedDate = LocalDate.of(2026, 8, 12),
        baseDate = LocalDate.of(2026, 8, 12),
        roundNo = 286,
        currencies = 58,
        inserted = 58,
        updated = 0,
        unchanged = 0,
        skipped = 0,
    )

    // GlobalExceptionHandler를 붙이지 않으면 ResponseStatusException이 상태만 있고 본문이 빈
    // 응답으로 풀려, 운영과 다른 경로를 테스트하게 된다. 워크플로가 --fail을 일부러 안 쓰는 이유가
    // 이 본문을 잡 요약에 남기기 위해서라, 본문까지 운영과 같아야 의미가 있다.
    private fun mvc(token: String) = MockMvcBuilders
        .standaloneSetup(SchedulerTriggerController(admin, indexAdmin, rateAdmin, stepExecutor, token))
        .setControllerAdvice(GlobalExceptionHandler())
        .build()

    @Test
    fun `토큰이 맞으면 수집을 실행하고 요약을 돌려준다`() {
        `when`(admin.collectHana(null, false)).thenReturn(ResponseEntity.ok(summary))

        mvc("secret").perform(
            post("/api/internal/scheduler/fx/hana-collect").header("X-Scheduler-Token", "secret")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.roundNo").value(286))
            .andExpect(jsonPath("$.currencies").value(58))

        // 스케줄 실행은 절대 force를 쓰지 않는다 — 2% 급변동 가드가 살아있어야 한다
        verify(admin).collectHana(null, false)
    }

    @Test
    fun `토큰이 틀리면 401이고 수집을 부르지 않는다`() {
        mvc("secret").perform(
            post("/api/internal/scheduler/fx/hana-collect").header("X-Scheduler-Token", "wrong")
        )
            .andExpect(status().isUnauthorized)
            // 상태만 보면 본문이 비어도 통과한다. Actions 잡 요약에 남는 건 본문이다.
            .andExpect(jsonPath("$.error").exists())

        verify(admin, never()).collectHana(any(), anyBoolean())
    }

    @Test
    fun `헤더가 없으면 401이고 수집을 부르지 않는다`() {
        mvc("secret").perform(post("/api/internal/scheduler/fx/hana-collect"))
            .andExpect(status().isUnauthorized)

        verify(admin, never()).collectHana(any(), anyBoolean())
    }

    // 빈 설정이 "토큰 불필요"로 해석되면 환경변수를 빠뜨린 순간 엔드포인트가 완전 공개된다.
    @Test
    fun `설정 토큰이 비어 있으면 토큰을 제시해도 503으로 닫는다`() {
        mvc("").perform(
            post("/api/internal/scheduler/fx/hana-collect").header("X-Scheduler-Token", "anything")
        )
            .andExpect(status().isServiceUnavailable)
            // 503은 "토큰이 틀렸다"가 아니라 "서버 설정이 빠졌다"다. 본문이 없으면
            // Actions 로그를 읽는 사람이 시크릿을 고치러 가는 헛수고를 한다.
            .andExpect(jsonPath("$.error").exists())

        verify(admin, never()).collectHana(any(), anyBoolean())
    }

    // 이 케이스가 이 파일에서 가장 위험하다. 빈 토큰 가드가 없으면 헤더 없는 요청이
    // ByteArray(0) 대 ByteArray(0) 비교가 되어 MessageDigest.isEqual이 true를 돌려주고,
    // 인증을 "통과"해 완전 공개된 엔드포인트에서 수집이 실제로 돈다.
    // 가드가 장식이 아니라 하중을 받는 지점이라 독립된 테스트로 둔다.
    @Test
    fun `설정 토큰이 비어 있고 헤더도 없으면 503으로 닫는다`() {
        mvc("").perform(post("/api/internal/scheduler/fx/hana-collect"))
            .andExpect(status().isServiceUnavailable)

        verify(admin, never()).collectHana(any(), anyBoolean())
    }

    // isBlank()을 isEmpty()로 바꿔도 기존 테스트가 전부 통과했다(변이 테스트).
    // 환경변수에 공백이 섞여 들어오면 그걸 진짜 비밀값으로 받아들이게 된다 —
    // "설정 누락의 기본값은 닫힘"이라는 불변식이 공백 입력에서만 조용히 뒤집힌다.
    @Test
    fun `설정 토큰이 공백뿐이어도 503으로 닫는다`() {
        mvc("   ").perform(
            post("/api/internal/scheduler/fx/hana-collect").header("X-Scheduler-Token", "   ")
        ).andExpect(status().isServiceUnavailable)

        verify(admin, never()).collectHana(any(), anyBoolean())
    }

    // 이 경로는 SecurityConfig에서 permitAll이고 상태를 바꾸는 작업이다.
    // GET으로도 열리면 크롤러·링크 프리페처가 수집을 돌릴 수 있다.
    @Test
    fun `GET으로는 트리거되지 않는다`() {
        mvc("secret").perform(
            get("/api/internal/scheduler/fx/hana-collect").header("X-Scheduler-Token", "secret")
        ).andExpect(status().isMethodNotAllowed)

        verify(admin, never()).collectHana(any(), anyBoolean())
    }

    // 위임의 값어치는 FxRateAdminController의 예외→상태 매핑을 물려받는 데 있다.
    // 목이 이미 만들어진 ResponseStatusException을 던지게 하면 그 매핑이 한 번도 실행되지 않아,
    // catch (IllegalStateException) 블록을 통째로 지워도 테스트가 통과한다.
    // 그래서 여기서는 진짜 FxRateAdminController를 세우고 수집 서비스가 던지게 한다.
    @Test
    fun `수집 서비스가 안전장치에 걸리면 422로 옮겨진다`() {
        val collectService = mock(HanaFxCollectService::class.java)
        // any(LocalDate::class.java)는 매처를 등록하고 null을 돌려주는데, HanaFxCollectService는
        // Kotlin 파이널 클래스라 원본 바이트코드의 non-null 파라미터 검사가 남아 NPE가 난다.
        // 엘비스로 아무 값이나 채우면 매처는 그대로 등록된 채 검사만 통과한다.
        `when`(collectService.collect(any(LocalDate::class.java) ?: LocalDate.EPOCH, anyBoolean()))
            .thenThrow(IllegalStateException("USD 환율이 2% 넘게 움직였습니다"))

        val realAdmin = FxRateAdminController(
            mock(FxRateService::class.java),
            mock(FxRateBackfillService::class.java),
            collectService,
            mock(CashFlowRecomputeService::class.java),
        )

        MockMvcBuilders.standaloneSetup(SchedulerTriggerController(realAdmin, indexAdmin, rateAdmin, stepExecutor, "secret"))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
            .perform(
                post("/api/internal/scheduler/fx/hana-collect").header("X-Scheduler-Token", "secret")
            )
            .andExpect(status().isUnprocessableEntity)
            // 502(은행 탓)와 구분되는 신호라 사유 문구가 실려야 한다.
            .andExpect(jsonPath("$.error").value("USD 환율이 2% 넘게 움직였습니다"))
    }

    // 설정값 양쪽 끝의 공백은 잘라낸다 — Render 대시보드에서 손으로 옮기다 개행이 붙으면
    // 진짜 틀린 토큰과 똑같이 401이 나서 첫 배포에서 원인을 찾기 어렵다.
    @Test
    fun `설정 토큰에 개행이 붙어 있어도 인증을 통과한다`() {
        `when`(admin.collectHana(null, false)).thenReturn(ResponseEntity.ok(summary))

        mvc("secret\n").perform(
            post("/api/internal/scheduler/fx/hana-collect").header("X-Scheduler-Token", "secret")
        ).andExpect(status().isOk)

        verify(admin).collectHana(null, false)
    }

    // ── 국내 지수 트리거 (AF-101) ───────────────────────────────────────────────

    private val indexSummary = DomesticIndexCollectSummary(
        tradeDate = LocalDate.of(2026, 8, 12),
        slot = "CLOSE",
        requested = 3,
        collected = 3,
        inserted = 3,
        updated = 0,
        failed = 0,
        failures = emptyList(),
    )

    @Test
    fun `토큰이 맞으면 요청한 슬롯 그대로 수집을 실행한다`() {
        `when`(indexAdmin.collect(IndexSlot.CLOSE)).thenReturn(ResponseEntity.ok(indexSummary))

        mvc("secret").perform(
            post("/api/internal/scheduler/index/domestic")
                .param("slot", "CLOSE")
                .header("X-Scheduler-Token", "secret")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.slot").value("CLOSE"))
            .andExpect(jsonPath("$.collected").value(3))

        // 슬롯이 바꿔치기되면 값은 그럴듯한 채로 엉뚱한 지점에 저장된다 — 그대로 넘어가는지 못 박는다
        verify(indexAdmin).collect(IndexSlot.CLOSE)
    }

    // slot에 기본값을 붙이면 이 테스트가 무너진다. 워크플로의 case 분기가 슬롯을 못 실어 보낸
    // 상황에서, 기본값은 "조용히 엉뚱한 슬롯을 덮어쓰기"이고 400은 "빨간 잡"이다.
    @Test
    fun `슬롯이 없으면 400이고 수집을 부르지 않는다`() {
        mvc("secret").perform(
            post("/api/internal/scheduler/index/domestic").header("X-Scheduler-Token", "secret")
        ).andExpect(status().isBadRequest)

        verify(indexAdmin, never()).collect(any(IndexSlot::class.java) ?: IndexSlot.CLOSE)
    }

    @Test
    fun `지수 트리거도 토큰이 틀리면 401이고 수집을 부르지 않는다`() {
        mvc("secret").perform(
            post("/api/internal/scheduler/index/domestic")
                .param("slot", "OPEN")
                .header("X-Scheduler-Token", "wrong")
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").exists())

        verify(indexAdmin, never()).collect(any(IndexSlot::class.java) ?: IndexSlot.CLOSE)
    }

    // FX 트리거와 같은 가드를 **재사용**하는지 본다. 두 번째 토큰 검사를 따로 짜면
    // 한쪽만 fail-closed가 되어도 이 파일이 통과해버리므로, 지수 경로에서도 독립으로 못 박는다.
    @Test
    fun `설정 토큰이 비어 있으면 지수 트리거도 503으로 닫는다`() {
        mvc("").perform(
            post("/api/internal/scheduler/index/domestic")
                .param("slot", "OPEN")
                .header("X-Scheduler-Token", "anything")
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.error").exists())

        verify(indexAdmin, never()).collect(any(IndexSlot::class.java) ?: IndexSlot.CLOSE)
    }

    @Test
    fun `지수 트리거는 GET으로는 열리지 않는다`() {
        mvc("secret").perform(
            get("/api/internal/scheduler/index/domestic")
                .param("slot", "CLOSE")
                .header("X-Scheduler-Token", "secret")
        ).andExpect(status().isMethodNotAllowed)

        verify(indexAdmin, never()).collect(any(IndexSlot::class.java) ?: IndexSlot.CLOSE)
    }

    // FX 쪽과 같은 이유로 진짜 MarketIndexAdminController를 세운다 — 목이 이미 만들어진
    // ResponseStatusException을 던지게 하면 위임이 물려받으려던 예외→상태 매핑이 한 번도
    // 실행되지 않아, catch (KisIndexException) 블록을 통째로 지워도 통과한다.
    @Test
    fun `KIS 응답이 이상하면 502로 옮겨진다`() {
        val collectService = mock(IndexCollectService::class.java)
        // any(...)는 매처를 등록하고 null을 돌려주는데, IndexCollectService는 Kotlin 파이널
        // 클래스라 원본 바이트코드의 non-null 파라미터 검사가 남아 NPE가 난다.
        // 엘비스로 아무 값이나 채우면 매처는 그대로 등록된 채 검사만 통과한다.
        `when`(
            collectService.collect(
                any(IndexSlot::class.java) ?: IndexSlot.CLOSE,
                any(LocalDateTime::class.java) ?: LocalDateTime.MIN,
            )
        ).thenThrow(KisIndexException("KIS 응답에 output이 없습니다"))

        val realIndexAdmin = MarketIndexAdminController(
            mock(KisIndexClient::class.java),
            collectService,
            mock(OverseasIndexCollectService::class.java),
        )

        MockMvcBuilders.standaloneSetup(SchedulerTriggerController(admin, realIndexAdmin, rateAdmin, stepExecutor, "secret"))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
            .perform(
                post("/api/internal/scheduler/index/domestic")
                    .param("slot", "CLOSE")
                    .header("X-Scheduler-Token", "secret")
            )
            .andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.error").value("KIS 응답에 output이 없습니다"))
    }

    // 크론이 실제로 때리는 건 이 트리거 경로다. IndexCollectService는 지수가 전부 터져도
    // 예외 대신 요약을 돌려주므로, 어드민 컨트롤러가 502로 바꿔주지 않으면 전면 중단이
    // HTTP 200으로 나가 잡이 초록으로 끝난다. 그 변환이 위임을 타고 여기까지 오는지 본다 —
    // 어드민 테스트만으로는 트리거가 상태를 삼키거나 200으로 덮어써도 잡히지 않는다.
    @Test
    fun `지수를 한 건도 못 모으면 트리거도 502를 낸다`() {
        val collectService = mock(IndexCollectService::class.java)
        `when`(
            collectService.collect(
                any(IndexSlot::class.java) ?: IndexSlot.CLOSE,
                any(LocalDateTime::class.java) ?: LocalDateTime.MIN,
            )
        ).thenReturn(
            indexSummary.copy(
                requested = 3,
                collected = 0,
                inserted = 0,
                updated = 0,
                failed = 3,
                failures = listOf("KOSPI: timeout", "KOSDAQ: timeout", "KOSPI200: timeout"),
            )
        )

        MockMvcBuilders
            .standaloneSetup(
                SchedulerTriggerController(
                    admin,
                    MarketIndexAdminController(
                        mock(KisIndexClient::class.java),
                        collectService,
                        mock(OverseasIndexCollectService::class.java),
                    ),
                    rateAdmin,
                    stepExecutor,
                    "secret",
                )
            )
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
            .perform(
                post("/api/internal/scheduler/index/domestic")
                    .param("slot", "CLOSE")
                    .header("X-Scheduler-Token", "secret")
            )
            .andExpect(status().isBadGateway)
            // 잡 요약에 남는 건 본문이다. 사유가 없으면 502만 보고 원인을 다시 찾아야 한다.
            .andExpect(jsonPath("$.error").exists())
    }

    // ── 해외 지수 트리거 (AF-110) ───────────────────────────────────────────────
    //
    // 국내 트리거의 여섯 케이스(정상 위임 / 파라미터 누락 400 / 401 / 503 / GET 405 /
    // 502 위임)를 그대로 짝지어 둔다. 해외에만 있는 것은 스케줄 오타 400이다.

    private val overseasSummary = OverseasIndexCollectSummary(
        schedule = "US",
        requested = 6,
        collected = 6,
        inserted = 6,
        updated = 0,
        failed = 0,
        failures = emptyList(),
        names = mapOf("SPX" to "S&P500 지수(SPX)"),
    )

    private fun verifyNoOverseasCollect() {
        verify(indexAdmin, never()).collectOverseas(
            any(OverseasSchedule::class.java) ?: OverseasSchedule.US,
        )
    }

    /** 어드민을 세우는 자리가 여럿이라 한 곳으로 모은다 — 생성자 인자가 늘면 여기만 고친다 */
    private fun realIndexAdmin(overseas: OverseasIndexCollectService) = MarketIndexAdminController(
        mock(KisIndexClient::class.java),
        mock(IndexCollectService::class.java),
        overseas,
    )

    @Test
    fun `토큰이 맞으면 요청한 스케줄 그대로 해외 수집을 실행한다`() {
        `when`(indexAdmin.collectOverseas(OverseasSchedule.ASIA))
            .thenReturn(ResponseEntity.ok(overseasSummary.copy(schedule = "ASIA", requested = 3, collected = 3)))

        mvc("secret").perform(
            post("/api/internal/scheduler/index/overseas")
                .param("schedule", "ASIA")
                .header("X-Scheduler-Token", "secret")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.schedule").value("ASIA"))
            .andExpect(jsonPath("$.collected").value(3))
            // names는 배포 후 9종 코드를 대조하는 유일한 수단이다. 응답에서 빠지면
            // 워크플로의 ::warning:: 파이썬 블록이 찍을 것이 없어진다.
            .andExpect(jsonPath("$.names").exists())

        // US가 아니라 ASIA로 검증한다 — 어딘가에 US가 하드코딩돼 있으면 US로는 안 잡힌다
        verify(indexAdmin).collectOverseas(OverseasSchedule.ASIA)
    }

    // 기본값을 붙이면 이 테스트가 무너진다. 해외에서 빠진 스케줄은 국내처럼 "엉뚱한 슬롯을
    // 덮어쓰기"가 아니라 **한쪽 시장군이 통째로 수집되지 않기**다 — 실패로도 안 남는다.
    @Test
    fun `해외 스케줄이 없으면 400이고 수집을 부르지 않는다`() {
        mvc("secret").perform(
            post("/api/internal/scheduler/index/overseas").header("X-Scheduler-Token", "secret")
        ).andExpect(status().isBadRequest)

        verifyNoOverseasCollect()
    }

    // enum으로 받은 이유를 트리거 경로에서도 못 박는다. String이면 이 요청이 400이 아니라
    // 500("설정에 해외 지수가 없습니다")으로 나가고, 그 문구는 운영자를 멀쩡한 yml로 보낸다.
    @Test
    fun `해외 스케줄에 오타가 있으면 400이고 수집을 부르지 않는다`() {
        mvc("secret").perform(
            post("/api/internal/scheduler/index/overseas")
                .param("schedule", "Us")
                .header("X-Scheduler-Token", "secret")
        ).andExpect(status().isBadRequest)

        verifyNoOverseasCollect()
    }

    @Test
    fun `해외 지수 트리거도 토큰이 틀리면 401이고 수집을 부르지 않는다`() {
        mvc("secret").perform(
            post("/api/internal/scheduler/index/overseas")
                .param("schedule", "US")
                .header("X-Scheduler-Token", "wrong")
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").exists())

        verifyNoOverseasCollect()
    }

    // 다른 트리거와 같은 authorize를 **재사용**하는지 본다. 경로마다 검사를 따로 짜면
    // 새 경로 하나만 fail-open이어도 나머지 테스트가 전부 통과해버린다.
    @Test
    fun `설정 토큰이 비어 있으면 해외 지수 트리거도 503으로 닫는다`() {
        mvc("").perform(
            post("/api/internal/scheduler/index/overseas")
                .param("schedule", "US")
                .header("X-Scheduler-Token", "anything")
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.error").exists())

        verifyNoOverseasCollect()
    }

    @Test
    fun `해외 지수 트리거는 GET으로는 열리지 않는다`() {
        mvc("secret").perform(
            get("/api/internal/scheduler/index/overseas")
                .param("schedule", "US")
                .header("X-Scheduler-Token", "secret")
        ).andExpect(status().isMethodNotAllowed)

        verifyNoOverseasCollect()
    }

    // 크론이 실제로 때리는 건 이 경로다. OverseasIndexCollectService는 9종이 전부 터져도
    // 예외 대신 요약을 돌려주므로, 어드민이 502로 바꿔주지 않으면 전면 중단이 200으로 나가
    // 잡이 초록으로 끝난다. 그 변환이 위임을 타고 여기까지 오는지 본다 —
    // 어드민 테스트만으로는 트리거가 상태를 삼키거나 200으로 덮어써도 잡히지 않는다.
    @Test
    fun `해외 지수를 한 건도 못 모으면 트리거도 502를 낸다`() {
        val overseasService = mock(OverseasIndexCollectService::class.java)
        // any(...)는 매처를 등록하고 null을 돌려주는데, OverseasIndexCollectService는 Kotlin
        // 파이널 클래스라 원본 바이트코드의 non-null 파라미터 검사가 남아 NPE가 난다.
        // 엘비스로 아무 값이나 채우면 매처는 그대로 등록된 채 검사만 통과한다.
        `when`(
            overseasService.collect(
                any(String::class.java) ?: "",
                any(Instant::class.java) ?: Instant.EPOCH,
            )
        ).thenReturn(
            overseasSummary.copy(
                requested = 6,
                collected = 0,
                inserted = 0,
                failed = 6,
                failures = listOf("SPX: timeout", "NASDAQ: timeout"),
                names = emptyMap(),
            )
        )

        MockMvcBuilders
            .standaloneSetup(SchedulerTriggerController(admin, realIndexAdmin(overseasService), rateAdmin, stepExecutor, "secret"))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
            .perform(
                post("/api/internal/scheduler/index/overseas")
                    .param("schedule", "US")
                    .header("X-Scheduler-Token", "secret")
            )
            .andExpect(status().isBadGateway)
            // 잡 요약에 남는 건 본문이다. 사유가 없으면 502만 보고 원인을 다시 찾아야 한다.
            .andExpect(jsonPath("$.error").exists())
    }

    // 500(우리 설정)과 502(상류)를 가르는 것이 위임을 타고 오는지. 여기서 502로 뭉개지면
    // 운영자가 KIS를 확인하러 가는데 진짜 원인은 빠진 market-index.overseas 블록이다.
    @Test
    fun `해외 수집 대상이 없으면 트리거는 500을 낸다`() {
        val overseasService = mock(OverseasIndexCollectService::class.java)
        `when`(
            overseasService.collect(
                any(String::class.java) ?: "",
                any(Instant::class.java) ?: Instant.EPOCH,
            )
        ).thenReturn(
            overseasSummary.copy(requested = 0, collected = 0, inserted = 0, names = emptyMap())
        )

        MockMvcBuilders
            .standaloneSetup(SchedulerTriggerController(admin, realIndexAdmin(overseasService), rateAdmin, stepExecutor, "secret"))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
            .perform(
                post("/api/internal/scheduler/index/overseas")
                    .param("schedule", "US")
                    .header("X-Scheduler-Token", "secret")
            )
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("market-index.overseas")))
    }

    // 국내와 같은 이유로 진짜 어드민을 세운다 — 목이 이미 만들어진 ResponseStatusException을
    // 던지게 하면 위임이 물려받으려던 catch (KisIndexException) 블록이 한 번도 실행되지 않아,
    // 그걸 통째로 지워도 통과한다.
    @Test
    fun `해외 수집 중 KIS 응답이 이상하면 502로 옮겨진다`() {
        val overseasService = mock(OverseasIndexCollectService::class.java)
        `when`(
            overseasService.collect(
                any(String::class.java) ?: "",
                any(Instant::class.java) ?: Instant.EPOCH,
            )
        ).thenThrow(KisIndexException("KIS 해외 응답에 output2가 없습니다"))

        MockMvcBuilders
            .standaloneSetup(SchedulerTriggerController(admin, realIndexAdmin(overseasService), rateAdmin, stepExecutor, "secret"))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
            .perform(
                post("/api/internal/scheduler/index/overseas")
                    .param("schedule", "US")
                    .header("X-Scheduler-Token", "secret")
            )
            .andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.error").value("KIS 해외 응답에 output2가 없습니다"))
    }

    // ── 백필 트리거 (AF-100) ───────────────────────────────────────────────────

    private val backfillSummary = BackfillSummary(
        currency = "USD",
        from = LocalDate.of(2020, 1, 1),
        to = LocalDate.of(2020, 12, 31),
        saved = 261,
        inserted = 261,
        updated = 0,
        unchanged = 0,
        skipped = 0,
        duplicates = 0,
        outOfRange = 0,
        firstDate = LocalDate.of(2020, 1, 2),
        lastDate = LocalDate.of(2020, 12, 30),
    )

    // 세 파라미터가 그대로 넘어가는지가 이 엔드포인트의 전부다. 하나라도 바꿔치기되면
    // 저장되는 행은 그럴듯한 채로 운영자가 요청한 구간과 다른 구간이 채워진다.
    @Test
    fun `백필은 통화와 구간을 그대로 넘긴다`() {
        `when`(
            admin.backfill("USD", LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31))
        ).thenReturn(ResponseEntity.ok(backfillSummary))

        mvc("secret").perform(
            post("/api/internal/scheduler/fx/backfill")
                .param("currency", "USD")
                .param("from", "2020-01-01")
                .param("to", "2020-12-31")
                .header("X-Scheduler-Token", "secret")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.inserted").value(261))
            .andExpect(jsonPath("$.currency").value("USD"))

        verify(admin).backfill("USD", LocalDate.of(2020, 1, 1), LocalDate.of(2020, 12, 31))
    }

    // 기본값을 붙이면 이 세 테스트가 무너진다. 워크플로 입력이 빠진 실행에서 기본값은
    // "조용히 엉뚱한 구간 백필"이고 400은 "빨간 잡"이다.
    @Test
    fun `백필에 통화가 없으면 400이고 백필을 부르지 않는다`() {
        mvc("secret").perform(
            post("/api/internal/scheduler/fx/backfill")
                .param("from", "2020-01-01")
                .param("to", "2020-12-31")
                .header("X-Scheduler-Token", "secret")
        ).andExpect(status().isBadRequest)

        verifyNoBackfill()
    }

    @Test
    fun `백필에 시작일이 없으면 400이고 백필을 부르지 않는다`() {
        mvc("secret").perform(
            post("/api/internal/scheduler/fx/backfill")
                .param("currency", "USD")
                .param("to", "2020-12-31")
                .header("X-Scheduler-Token", "secret")
        ).andExpect(status().isBadRequest)

        verifyNoBackfill()
    }

    @Test
    fun `백필에 종료일이 없으면 400이고 백필을 부르지 않는다`() {
        mvc("secret").perform(
            post("/api/internal/scheduler/fx/backfill")
                .param("currency", "USD")
                .param("from", "2020-01-01")
                .header("X-Scheduler-Token", "secret")
        ).andExpect(status().isBadRequest)

        verifyNoBackfill()
    }

    @Test
    fun `백필 트리거도 토큰이 틀리면 401이고 백필을 부르지 않는다`() {
        mvc("secret").perform(
            post("/api/internal/scheduler/fx/backfill")
                .param("currency", "USD")
                .param("from", "2020-01-01")
                .param("to", "2020-12-31")
                .header("X-Scheduler-Token", "wrong")
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").exists())

        verifyNoBackfill()
    }

    // 기존 두 트리거와 같은 authorize를 **재사용**하는지 본다. 두 번째 토큰 검사를 따로 짜면
    // 이 경로만 fail-closed가 아니어도 다른 테스트가 전부 통과해버린다.
    // 백필은 어드민 전용이던 작업이라 이 경로가 공개로 새는 건 가장 비싼 실수다.
    @Test
    fun `설정 토큰이 비어 있으면 백필 트리거도 503으로 닫는다`() {
        mvc("").perform(
            post("/api/internal/scheduler/fx/backfill")
                .param("currency", "USD")
                .param("from", "2020-01-01")
                .param("to", "2020-12-31")
                .header("X-Scheduler-Token", "anything")
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.error").exists())

        verifyNoBackfill()
    }

    // 헤더가 아예 없는 요청. 빈 토큰 가드가 없으면 ByteArray(0) 대 ByteArray(0) 비교가
    // 참이 되어 인증을 "통과"하고, 공개된 엔드포인트에서 백필이 실제로 돈다.
    @Test
    fun `설정 토큰이 비어 있고 헤더도 없으면 백필 트리거도 503으로 닫는다`() {
        mvc("").perform(
            post("/api/internal/scheduler/fx/backfill")
                .param("currency", "USD")
                .param("from", "2020-01-01")
                .param("to", "2020-12-31")
        ).andExpect(status().isServiceUnavailable)

        verifyNoBackfill()
    }

    @Test
    fun `백필 트리거는 GET으로는 열리지 않는다`() {
        mvc("secret").perform(
            get("/api/internal/scheduler/fx/backfill")
                .param("currency", "USD")
                .param("from", "2020-01-01")
                .param("to", "2020-12-31")
                .header("X-Scheduler-Token", "secret")
        ).andExpect(status().isMethodNotAllowed)

        verifyNoBackfill()
    }

    // FX 수집·지수와 같은 이유로 진짜 FxRateAdminController를 세운다 — 목이 이미 만들어진
    // ResponseStatusException을 던지게 하면 위임이 물려받으려던 예외→상태 매핑이 실행되지 않아,
    // catch (IllegalStateException) 블록을 통째로 지워도 통과한다.
    // 스크립트의 구간별 실패 정책이 이 상태 코드를 읽고 계속할지 멈출지 정하므로,
    // 502가 500으로 뭉개지면 재실행하면 되는 실패에서 백필이 통째로 중단된다.
    @Test
    fun `ECOS 응답이 이상하면 백필 트리거도 502로 옮겨진다`() {
        val backfillService = mock(FxRateBackfillService::class.java)
        `when`(
            backfillService.backfill(
                any(String::class.java) ?: "",
                any(LocalDate::class.java) ?: LocalDate.EPOCH,
                any(LocalDate::class.java) ?: LocalDate.EPOCH,
            )
        ).thenThrow(IllegalStateException("ECOS가 0건을 돌려줬습니다"))

        val realAdmin = FxRateAdminController(
            mock(FxRateService::class.java),
            backfillService,
            mock(HanaFxCollectService::class.java),
            mock(CashFlowRecomputeService::class.java),
        )

        MockMvcBuilders.standaloneSetup(SchedulerTriggerController(realAdmin, indexAdmin, rateAdmin, stepExecutor, "secret"))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
            .perform(
                post("/api/internal/scheduler/fx/backfill")
                    .param("currency", "USD")
                    .param("from", "2020-01-01")
                    .param("to", "2020-12-31")
                    .header("X-Scheduler-Token", "secret")
            )
            .andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.error").value("ECOS가 0건을 돌려줬습니다"))
    }

    // ── 금리 트리거 (AF-102) ───────────────────────────────────────────────────

    private val rateSummary = RateCollectSummary(
        from = LocalDate.of(2026, 7, 30),
        to = LocalDate.of(2026, 8, 13),
        requested = 2,
        collected = 20,
        inserted = 1,
        updated = 0,
        unchanged = 19,
        skippedRows = 0,
        outOfRange = 0,
        emptySeries = emptyList(),
        failed = 0,
        failures = emptyList(),
    )

    // 날짜를 노출하지 않는 게 이 엔드포인트의 설계다. null이 그대로 넘어가야
    // 어드민 컨트롤러의 KST 오늘 기준 2주 창이 적용된다 — 러너의 UTC 시계가 아니라.
    @Test
    fun `토큰이 맞으면 금리 수집을 위임한다`() {
        `when`(rateAdmin.collect(null, null)).thenReturn(ResponseEntity.ok(rateSummary))

        mvc("secret").perform(
            post("/api/internal/scheduler/rate").header("X-Scheduler-Token", "secret")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.collected").value(20))
            // 워크플로가 updated와 unchanged를 갈라 찍는다 — 합쳐지면 "ECOS가 값을 정정했다"는
            // 신호가 매일 나오는 unchanged 더미에 묻힌다. 두 필드가 응답에 남는지 못 박는다.
            .andExpect(jsonPath("$.updated").value(0))
            .andExpect(jsonPath("$.unchanged").value(19))

        verify(rateAdmin).collect(null, null)
    }

    @Test
    fun `금리 트리거도 토큰이 틀리면 401이고 수집을 부르지 않는다`() {
        mvc("secret").perform(
            post("/api/internal/scheduler/rate").header("X-Scheduler-Token", "wrong")
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").exists())

        verifyNoRateCollect()
    }

    // 앞의 세 트리거와 같은 authorize를 **재사용**하는지 본다. 경로마다 검사를 새로 짜면
    // 새 경로 하나만 fail-open이 되어도 다른 테스트가 전부 통과해버린다.
    @Test
    fun `설정 토큰이 비어 있으면 금리 트리거도 503으로 닫는다`() {
        mvc("").perform(
            post("/api/internal/scheduler/rate").header("X-Scheduler-Token", "anything")
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.error").exists())

        verifyNoRateCollect()
    }

    @Test
    fun `설정 토큰이 비어 있고 헤더도 없으면 금리 트리거도 503으로 닫는다`() {
        mvc("").perform(post("/api/internal/scheduler/rate"))
            .andExpect(status().isServiceUnavailable)

        verifyNoRateCollect()
    }

    @Test
    fun `금리 트리거는 GET으로는 열리지 않는다`() {
        mvc("secret").perform(
            get("/api/internal/scheduler/rate").header("X-Scheduler-Token", "secret")
        ).andExpect(status().isMethodNotAllowed)

        verifyNoRateCollect()
    }

    // 크론이 실제로 때리는 건 이 트리거 경로다. RateCollectService는 종목이 전부 터져도
    // 예외 대신 요약을 돌려주므로, 어드민 컨트롤러가 502로 바꿔주지 않으면 전면 중단이
    // HTTP 200으로 나가 잡이 초록으로 끝난다. 목을 쓰면 그 변환이 한 번도 실행되지 않아
    // 어드민 쪽 분기를 통째로 지워도 통과하므로, 여기서는 진짜 컨트롤러를 세운다.
    @Test
    fun `금리를 한 건도 못 모으면 트리거도 502를 낸다`() {
        val mvc = mvcWithRealRateAdmin(
            rateSummary.copy(
                requested = 2,
                collected = 0,
                inserted = 0,
                unchanged = 0,
                failed = 2,
                failures = listOf("CD91: timeout", "국고채3년: timeout"),
            )
        )

        mvc.perform(post("/api/internal/scheduler/rate").header("X-Scheduler-Token", "secret"))
            .andExpect(status().isBadGateway)
            // 잡 요약에 남는 건 본문이다. 사유가 없으면 502만 보고 원인을 다시 찾아야 한다.
            .andExpect(jsonPath("$.error").exists())
    }

    // 502와 갈라지는 쪽. ECOS는 정상 응답을 줬는데 전 종목이 0건이면 틀린 건 우리가 넣은
    // 통계표·항목 코드다 — 상류 장애(502)로 부르면 운영자를 한국은행 상태 페이지로 보내게 된다.
    // 워크플로의 에러 범례가 이 두 코드를 갈라 안내하므로, 위임이 상태를 뭉개지 않는지 본다.
    @Test
    fun `전 종목이 0건이면 트리거는 502가 아니라 500을 낸다`() {
        val mvc = mvcWithRealRateAdmin(
            rateSummary.copy(
                requested = 2,
                collected = 0,
                inserted = 0,
                unchanged = 0,
                emptySeries = listOf("CD91", "국고채3년"),
            )
        )

        mvc.perform(post("/api/internal/scheduler/rate").header("X-Scheduler-Token", "secret"))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.error").exists())
    }

    private fun mvcWithRealRateAdmin(summary: RateCollectSummary) = MockMvcBuilders
        .standaloneSetup(
            SchedulerTriggerController(
                admin,
                indexAdmin,
                MarketRateAdminController(rateCollectServiceReturning(summary), mock(EcosStatListClient::class.java)),
                stepExecutor,
                "secret",
            )
        )
        .setControllerAdvice(GlobalExceptionHandler())
        .build()

    private fun rateCollectServiceReturning(summary: RateCollectSummary): RateCollectService {
        val service = mock(RateCollectService::class.java)
        // any(...)는 매처를 등록하고 null을 돌려주는데, RateCollectService는 Kotlin 파이널
        // 클래스라 원본 바이트코드의 non-null 파라미터 검사가 남아 NPE가 난다.
        // 엘비스로 아무 값이나 채우면 매처는 그대로 등록된 채 검사만 통과한다.
        `when`(
            service.collect(
                any(LocalDate::class.java) ?: LocalDate.EPOCH,
                any(LocalDate::class.java) ?: LocalDate.EPOCH,
                any(LocalDateTime::class.java) ?: LocalDateTime.MIN,
            )
        ).thenReturn(summary)
        return service
    }

    // 맨 any()를 쓴다. any(LocalDate::class.java)는 Mockito 2.1부터 null을 매칭하지 않아
    // 트리거가 실제로 부르는 collect(null, null)을 놓친다 — never() 검증이 통째로 장식이 된다.
    private fun verifyNoRateCollect() {
        verify(rateAdmin, never()).collect(any(), any())
    }

    private fun verifyNoBackfill() {
        verify(admin, never()).backfill(
            any(String::class.java) ?: "",
            any(LocalDate::class.java) ?: LocalDate.EPOCH,
            any(LocalDate::class.java) ?: LocalDate.EPOCH,
        )
    }

    // ── 마감 트리거 (AF-107) ───────────────────────────────────────────────────
    //
    // 다른 트리거와 달리 어드민에 위임하지 않는다 — 어드민은 X-User-Id를 실행자로 찍는데
    // 크론에는 어드민 신원이 없다. 그래서 WfStepExecutor를 직접 부르고, 실행자는 SYSTEM이다.

    private val runSummary = WfRunSummary(
        ymd = LocalDate.of(2026, 8, 15),
        executedSteps = listOf("S010", "S030"),
        gateSkippedSteps = listOf("S040"),
        notScheduledSteps = emptyList(),
    )

    @Test
    fun `마감 트리거는 워크플로우를 돌리고 요약을 돌려준다`() {
        `when`(stepExecutor.runDaily(any(LocalDate::class.java) ?: LocalDate.EPOCH, anyString()))
            .thenReturn(runSummary)

        mvc("secret").perform(
            post("/api/internal/scheduler/closing").header("X-Scheduler-Token", "secret")
        )
            .andExpect(status().isOk)
            // 어느 단계가 게이트에서 스킵됐는지가 Actions 잡 요약에서 읽히는 유일한 관측 수단이다.
            // executedSteps만 남고 gateSkippedSteps가 빠지면 "S030이 왜 안 돌았나"에 답할 길이 없다.
            .andExpect(jsonPath("$.executedSteps[1]").value("S030"))
            .andExpect(jsonPath("$.gateSkippedSteps[0]").value("S040"))
    }

    @Test
    fun `토큰이 다르면 401이고 워크플로우를 안 돌린다`() {
        mvc("secret").perform(
            post("/api/internal/scheduler/closing").header("X-Scheduler-Token", "wrong")
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").exists())

        verifyNoClosingRun()
    }

    // 다른 트리거와 같은 authorize를 **재사용**하는지 본다. 마감은 실제 금융 데이터를 쓰는
    // 작업이라, 이 경로 하나만 fail-open이어도 나머지 테스트가 전부 통과해버린다.
    @Test
    fun `설정 토큰이 비면 503으로 닫는다`() {
        mvc("").perform(
            post("/api/internal/scheduler/closing").header("X-Scheduler-Token", "anything")
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.error").exists())

        verifyNoClosingRun()
    }

    // 크론은 UTC 15:00에 뛴다 — KST로는 다음 날 00:00이다. LocalDate.now()를 쓰면
    // 컨테이너가 UTC라 정확히 그 하루를 잃고, 마감이 매번 어제 일자로 돈다.
    // 크론 표현식 자체는 .github/ 아래 YAML이라 값으로 검증할 방법이 없어 여기서 못 박는다.
    @Test
    fun `UTC 15시는 KST로 다음 날이다`() {
        assertThat(SchedulerTriggerController.closingDate(Instant.parse("2026-08-15T15:00:00Z")))
            .isEqualTo(LocalDate.of(2026, 8, 16))

        // 경계 바로 아래. 이게 없으면 "무조건 하루 더하기"도 위 단언을 통과한다.
        assertThat(SchedulerTriggerController.closingDate(Instant.parse("2026-08-15T14:59:59Z")))
            .isEqualTo(LocalDate.of(2026, 8, 15))
    }

    // 위 테스트는 순수 함수만 본다 — 엔드포인트가 그 함수를 **쓰는지**는 아직 아무도 안 봤다.
    // runClosing을 closingDate(Instant.now()) 대신 LocalDate.now()로 바꿔도 위 네 테스트는
    // 전부 통과한다(실제로 변이를 넣어 확인했다). 운영 컨테이너는 UTC라 거기서만 하루가 밀린다.
    //
    // **기본 시간대를 옮기지 않으면 이 테스트도 그 변이를 못 잡는다** — 개발 머신이 KST라
    // LocalDate.now()가 마침 정답을 낸다. 그래서 오늘 날짜가 KST와 반드시 다른 지역으로
    // 옮겨 두고 부른다. 되돌리기는 finally에 있다(기본 시간대는 JVM 전역이다).
    @Test
    fun `마감 트리거는 KST 오늘로 워크플로우를 돌린다`() {
        `when`(stepExecutor.runDaily(any(LocalDate::class.java) ?: LocalDate.EPOCH, anyString()))
            .thenReturn(runSummary)

        val kstToday = SchedulerTriggerController.closingDate(Instant.now())
        val original = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zoneWhereTodayDiffersFromKst()))
        try {
            // 옮긴 시간대가 정말 다른 날짜인지 먼저 확인한다. 이 가드가 없으면 위 헬퍼가
            // 틀렸을 때 테스트가 아무것도 안 보면서 초록으로 남는다.
            assertThat(LocalDate.now()).isNotEqualTo(kstToday)

            mvc("secret").perform(
                post("/api/internal/scheduler/closing").header("X-Scheduler-Token", "secret")
            ).andExpect(status().isOk)
        } finally {
            TimeZone.setDefault(original)
        }

        val ymd = ArgumentCaptor.forClass(LocalDate::class.java)
        verify(stepExecutor).runDaily(ymd.capture() ?: LocalDate.EPOCH, anyString())
        assertThat(ymd.value).isEqualTo(kstToday)
    }

    /**
     * 지금 이 순간 KST와 날짜가 다른 지역. 하루 중 어느 시각에 돌려도 성립해야 한다.
     *
     * Niue(UTC-11)는 KST보다 20시간 늦어 KST 20시 전에는 항상 어제다.
     * Kiritimati(UTC+14)는 5시간 빨라 KST 19시 이후에는 항상 내일이다.
     * 20시를 경계로 갈라 쓰면 두 구간이 하루를 빈틈없이 덮는다.
     */
    private fun zoneWhereTodayDiffersFromKst(): ZoneId =
        if (Instant.now().atZone(ZoneId.of("Asia/Seoul")).hour >= 20) {
            ZoneId.of("Pacific/Kiritimati")
        } else {
            ZoneId.of("Pacific/Niue")
        }

    private fun verifyNoClosingRun() {
        verify(stepExecutor, never()).runDaily(
            any(LocalDate::class.java) ?: LocalDate.EPOCH,
            anyString(),
        )
    }
}
