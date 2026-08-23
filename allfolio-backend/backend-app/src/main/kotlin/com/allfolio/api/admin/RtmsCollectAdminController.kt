package com.allfolio.api.admin

import com.allfolio.market.realestate.RtmsCollectService
import com.allfolio.market.realestate.RtmsCollectSummary
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

/**
 * 실거래가 수집 트리거 (A1 v3).
 *
 * **시군구를 명시로 받는다.** 자동 대상 선정(사용자가 보유한 부동산의 시군구)은 R3에서
 * 붙인다 — 지금은 보유 부동산이 0건이라 자동으로 하면 아무것도 안 받는다. 백필과
 * 시범 수집을 먼저 돌릴 수 있어야 R2 선택 UI에 보여 줄 단지 목록이 생긴다.
 */
@RestController
@RequestMapping("/api/admin/rtms")
class RtmsCollectAdminController(
    private val collectService: RtmsCollectService,
) {
    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")

        /**
         * 한 번에 요청할 수 있는 최대 조합 수.
         *
         * **일 1,000콜 제한이 근거다.** 한 조합이 페이징으로 여러 콜을 쓰므로(실측 최다 3콜)
         * 조합 수만으로는 예산을 못 잡지만, 상한이 없으면 실수 한 번에 하루치가 날아간다.
         * 서비스의 budget이 실제 방어이고 이건 요청 단계의 그물이다.
         */
        private const val MAX_COMBOS = 300

        /** 한 번에 거슬러 올라갈 수 있는 최대 개월 수 */
        private const val MAX_MONTHS = 36
    }

    /**
     * POST /api/admin/rtms/collect — `(시군구 × 최근 N개월)` 수집.
     *
     * **전멸은 502, 부분 실패는 200, 대상 0건은 400이다.** 원자재·금리 수집과 같은 판단이다:
     * 조용한 수집 중단은 반드시 보여야 하고(502), 매일 빨간 잡은 아무도 안 보며(200),
     * **다만 대상 0건은 여기서 우리 설정이 아니라 호출자의 파라미터 문제라 400이다**
     * (원자재는 설정 목록이 비는 것이라 500이었다).
     *
     * @param sgg 법정동 코드 앞 5자리, 쉼표 구분 (예: `11110,11680`)
     * @param months 이번 달부터 거슬러 받을 개월 수
     */
    @PostMapping("/collect")
    fun collect(
        @RequestParam sgg: String,
        @RequestParam(defaultValue = "12") months: Int,
        @RequestParam(required = false) budget: Int?,
    ): ResponseEntity<RtmsCollectSummary> {
        val codes = sgg.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (codes.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "sgg가 비어 있습니다")
        }
        codes.firstOrNull { !it.matches(Regex("\\d{5}")) }?.let {
            // 5자리가 아니면 포털이 조용히 0건을 준다 — 오류가 아니라 빈 결과라 안 보인다
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "법정동 코드는 숫자 5자리여야 합니다: $it",
            )
        }
        if (months !in 1..MAX_MONTHS) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST, "months는 1~$MAX_MONTHS 사이여야 합니다 (요청: $months)",
            )
        }

        // 날짜는 서버가 KST로 정한다 — 러너 시계를 배치에 싣지 않는다.
        // UTC 컨테이너에서 그냥 now()를 쓰면 매월 1일 09시 이전에 '이번 달'이 지난달이 된다.
        val now = LocalDateTime.now(KST)
        val thisMonth = YearMonth.from(now)
        val targets = codes.flatMap { code ->
            (0 until months).map { back -> code to thisMonth.minusMonths(back.toLong()) }
        }
        if (targets.size > MAX_COMBOS) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "조합이 너무 많습니다 ${targets.size}개 (상한 $MAX_COMBOS) — 시군구나 개월 수를 줄이세요",
            )
        }

        val summary = collectService.collect(
            targets = targets,
            today = thisMonth,
            now = now,
            budget = budget ?: RtmsCollectService.DEFAULT_BUDGET,
        )

        // **`fetched == 0`만으로 502를 내면 안 된다.** 전부 이미 받아 둔 조합이면 정상이다.
        // 실제로 부르려 했는데 하나도 성공하지 못한 경우만 상류 장애로 본다.
        val attempted = summary.fetched + summary.failures.size
        if (attempted > 0 && summary.fetched == 0) {
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "실거래가 수집 전멸 (${summary.failures.size}건 실패): ${summary.failures.take(3)}",
            )
        }
        return ResponseEntity.ok(summary)
    }
}
