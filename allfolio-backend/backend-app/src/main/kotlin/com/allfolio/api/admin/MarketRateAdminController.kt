package com.allfolio.api.admin

import com.allfolio.fx.EcosApiException
import com.allfolio.fx.EcosStatListClient
import com.allfolio.market.rate.RateCollectService
import com.allfolio.market.rate.RateCollectSummary
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

@RestController
@RequestMapping("/api/admin/rate")
class MarketRateAdminController(
    private val rateCollectService: RateCollectService,
    private val statListClient: EcosStatListClient,
) {
    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")

        /**
         * 기본 수집 창. 달력 14일이면 연휴가 끼어도 영업일이 5~6일은 들어온다.
         * 영업일을 세지 않는 이유는 공휴일 달력을 들일 값어치가 없어서다.
         */
        private const val WINDOW_DAYS = 14L
    }

    /**
     * GET /api/admin/rate/ecos/tables?stat=721Y001 — ECOS 통계표 목록 (AF-102).
     *
     * 수집 대상 코드를 확인하기 위한 것이다. **추정한 코드를 설정에 넣지 말 것** —
     * ECOS는 틀린 코드에 오류가 아니라 0건을 주므로, 잘못 넣으면 "기간이 비었다"와 구분되지 않는다.
     * 응답은 파싱하지 않고 그대로 나간다(오류 응답도 그대로 보여야 경로 실수가 드러난다).
     */
    @GetMapping("/ecos/tables", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun tables(@RequestParam(required = false) stat: String?): ResponseEntity<String> =
        try {
            ResponseEntity.ok(statListClient.tables(stat))
        } catch (e: EcosApiException) {
            // 요청은 멀쩡했고 상류 응답이 이상한 것이다. 전역 폴백의 500으로 뭉개지면
            // 운영자가 우리 버그를 찾으러 간다 — 백필·하나은행 엔드포인트와 같은 판단이다
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, e.message)
        }

    /** GET /api/admin/rate/ecos/items?stat=721Y001 — 통계표 하나의 항목 목록 */
    @GetMapping("/ecos/items", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun items(@RequestParam stat: String): ResponseEntity<String> =
        try {
            ResponseEntity.ok(statListClient.items(stat))
        } catch (e: EcosApiException) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, e.message)
        }

    /**
     * POST /api/admin/rate/collect — 금리 수집 (어드민 전용, AF-102).
     *
     * **날짜를 주지 않으면 KST 오늘 기준 최근 2주다.** 일일 수집과 백필이 같은 경로인 이유는
     * 둘이 같은 일이기 때문이다 — "이 구간을 ECOS가 준 값으로 맞춘다", 그리고 멱등하다.
     * 초기 백필은 `?from=2020-01-01&to=<오늘>`로 한 번 부른다.
     * (긴 구간은 1~2년씩 끊어 부를 것 — 이유는 [RateCollectService]의 KDoc에 있다.)
     *
     * `LocalDate.now()`가 아니라 KST로 옮겨 오늘을 구한다 — Render 컨테이너는 UTC라
     * KST 새벽 실행이 하루 전으로 밀린다.
     *
     * **전멸은 502, 부분 실패는 200, 대상 0건은 500이다.** 판단 근거는
     * [MarketIndexAdminController.collect]의 KDoc에 길게 적혀 있고 여기서도 그대로다:
     * 조용한 수집 중단은 반드시 보여야 하고(502), 매일 빨간 잡은 아무도 안 보며(200),
     * 빈 설정은 우리 실수라 ECOS를 확인하러 보내면 안 된다(500).
     */
    @PostMapping("/collect")
    fun collect(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): ResponseEntity<RateCollectSummary> {
        val end = to ?: LocalDate.now(KST)
        val start = from ?: end.minusDays(WINDOW_DAYS)

        val summary = rateCollectService.collect(start, end, LocalDateTime.now(ZoneOffset.UTC))

        if (summary.requested == 0) {
            // 우리 설정 실수다. ECOS를 확인하러 보내지 않도록 502가 아니라 500으로 낸다
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "수집 대상 금리가 설정에 없습니다 — application.yml의 market-rate.series 를 확인하세요",
            )
        }

        // **`collected == 0`만으로 502를 내면 안 된다.** `collected`는 "실제로 저장한 행 수"라,
        // 모든 종목이 정상적으로 빈 응답을 준 날에도 0이 된다(긴 연휴, 또는 변경 시에만 공표되는
        // 계열만 남은 구성). 그걸 502로 부르면 운영자가 멀쩡한 한국은행을 확인하러 간다.
        // 상류 장애는 **저장이 0인데 실패가 있는** 경우다. 실패가 하나도 없이 0건이면
        // 그건 `emptySeries`가 설명하는 상태이고, 워크플로가 경고로 띄운다.
        if (summary.collected == 0 && summary.failed > 0) {
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "금리를 한 건도 수집하지 못했습니다 (요청 ${summary.requested}건, $start~$end): " +
                    summary.failures.joinToString("; ").ifBlank { "사유 없음" },
            )
        }
        return ResponseEntity.ok(summary)
    }
}
