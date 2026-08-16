package com.allfolio.api.admin

import com.allfolio.market.commodity.CommodityCollectService
import com.allfolio.market.commodity.CommodityCollectSummary
import com.allfolio.market.commodity.CommodityProperties
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
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
import java.time.temporal.ChronoUnit

/**
 * 설정에 실린 원자재 한 종목. 진단 조회([CommodityAdminController.config])의 응답 요소다.
 *
 * @param group 어느 목록에서 왔는지 — `fredDaily`·`fredMonthly`·`fsc`.
 *              환경변수는 리스트를 **병합하지 않고 통째로 교체**하므로, 한 목록이 통째로
 *              사라진 상태를 이 필드로만 알아볼 수 있다
 */
data class CommodityConfigItemView(
    val group: String,
    val code: String,
    val seriesId: String,
    val unit: String,
    val frequency: String,
)

/** @param total 항목 수. 배포 후 "16종이 다 실렸나"를 한 눈에 본다 */
data class CommodityConfigView(
    val total: Int,
    val items: List<CommodityConfigItemView>,
)

@RestController
@RequestMapping("/api/admin/commodity")
class CommodityAdminController(
    private val commodityCollectService: CommodityCollectService,
    private val properties: CommodityProperties,
) {
    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")

        /**
         * 한 번에 허용하는 최대 구간(일). 2년 + 윤년 여유.
         *
         * 이유는 [CommodityCollectService]의 KDoc에 있다: 할당식 id + `@Version` 부재 탓에
         * 기존 행마다 `em.merge`가 SELECT를 하나씩 내므로, 긴 구간 한 방이면 순차 왕복이
         * 무료 플랜 Neon 커넥션을 오래 쥔다. 주석으로만 "끊어 부르세요"라고 적으면 안 지켜진다.
         */
        private const val MAX_RANGE_DAYS = 732L

        /**
         * 어느 목록에도 속하지 않은 항목의 라벨. [CommodityProperties.allItems]에는 있는데
         * 아래 이름표 표에 없다는 뜻 — 목록이 하나 늘었다는 신호다.
         */
        internal const val UNKNOWN_GROUP = "unknown"
    }

    /**
     * GET /api/admin/commodity/config — 수집 대상 설정 조회 (AF-108).
     *
     * **상류를 부르지 않는다.** 배포 후 "설정이 의도대로 실렸나"만 본다.
     * 이게 필요한 이유는 [CommodityProperties]의 KDoc에 있다: 스프링은 리스트를 병합하지 않고
     * 우선순위가 높은 쪽으로 **통째로 교체**하므로, 환경변수 한 줄이 목록 하나를 통째로 지울 수 있다.
     * 그 상태의 증상은 "수집은 초록인데 화면에 몇 종이 없다"라서 로그로는 안 보인다.
     *
     * 인증키는 실리지 않는다 — 여기 나가는 `seriesId`는 공개 식별자다(예: `DCOILWTICO`).
     */
    @GetMapping("/config")
    fun config(): ResponseEntity<CommodityConfigView> {
        // **기준은 [CommodityProperties.allItems] 하나다.** 목록을 손으로 더하면 넷째 목록이
        // 생기는 날 "설정이 의도대로 실렸나"를 보러 온 이 엔드포인트가 그 목록만 조용히 빠뜨린다 —
        // 그 파일 KDoc이 금지하는 패턴이 정확히 그것이다. 아래 표는 **이름표를 붙이는 용도**일 뿐이고,
        // 표에 없는 항목도 `unknown`으로 반드시 나온다(그게 "목록이 하나 늘었다"는 신호다).
        val labels = linkedMapOf(
            "fredDaily" to properties.fredDaily,
            "fredMonthly" to properties.fredMonthly,
            "fsc" to properties.fsc,
        )
        // CommodityItem은 equals를 정의하지 않으므로 동일성으로 찾는다 — allItems가 같은
        // 인스턴스를 이어 붙여 돌려주므로 성립한다
        val items = properties.allItems.map { item ->
            val group = labels.entries.firstOrNull { (_, list) -> list.any { it === item } }?.key ?: UNKNOWN_GROUP
            view(group, item)
        }
        return ResponseEntity.ok(CommodityConfigView(total = items.size, items = items))
    }

    /**
     * POST /api/admin/commodity/collect — 원자재 수집 (어드민 전용, AF-108).
     *
     * **날짜를 주지 않으면 끝점은 KST 오늘이고, 시작점은 종목의 주기가 정한다**
     * (일간 14일 · 월간 400일 — 근거는 [CommodityCollectService]의 창 상수 KDoc에 있다).
     * 일일 수집과 백필이 같은 경로인 이유는
     * 둘이 같은 일이기 때문이다 — "이 구간을 소스가 준 값으로 맞춘다", 그리고 멱등하다.
     * 초기 백필은 **1~2년씩 끊어** 부른다 (예: `?from=2020-01-01&to=2021-12-31`).
     * 2년(+윤년 여유)을 넘는 구간은 400이다.
     *
     * **`LocalDate.now()`가 아니라 KST로 옮겨 오늘을 구한다** — Render 컨테이너는 UTC라
     * KST 새벽 실행이 하루 전으로 밀린다. `MarketRateAdminController`·`FxRateAdminController`가
     * 같은 자리에 같은 방어를 한다.
     *
     * **전멸은 502, 부분 실패는 200, 대상 0건은 500이다.** 판단 근거는
     * [MarketRateAdminController.collect]의 KDoc과 같다: 조용한 수집 중단은 반드시 보여야 하고(502),
     * 매일 빨간 잡은 아무도 안 보며(200), 빈 설정은 우리 실수라 상류를 확인하러 보내면 안 된다(500).
     *
     * 배포 직후 전량 실패가 나면 대개 `market_commodity_quote` 테이블이 없는 것이다 —
     * 마이그레이션(`docs/superpowers/migrations/2026-08-16-market-commodity-quote.sql`)이
     * 배포 전에 실행돼야 한다. 그 경우 `failures`에 제약·릴레이션 오류가 그대로 실린다.
     */
    @PostMapping("/collect")
    fun collect(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): ResponseEntity<CommodityCollectSummary> {
        val end = to ?: LocalDate.now(KST)

        // 뒤집힌 구간은 서비스가 require로 잡아 400이 된다. 여기서는 길이만 본다.
        // **`from`이 없을 때는 잴 것이 없다** — 그때 구간을 정하는 건 여기가 아니라 서비스이고,
        // 주기별 기본 창(일간 14일·월간 400일)은 둘 다 이 상한 안이다
        if (from != null && ChronoUnit.DAYS.between(from, end) > MAX_RANGE_DAYS) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "한 번에 요청할 수 있는 구간은 최대 ${MAX_RANGE_DAYS}일입니다 ($from~$end) — " +
                    "1~2년씩 끊어 호출하세요 (예: ?from=2020-01-01&to=2021-12-31)",
            )
        }

        // **`from`을 그대로(null 포함) 넘긴다.** 기본 창을 여기서 정하지 않는 이유는 그 길이가
        // 종목의 주기에 달려 있고, 어느 종목이 무슨 주기인지는 서비스만 알기 때문이다.
        // 금리 어드민(`MarketRateAdminController.WINDOW_DAYS = 14`)이 창을 직접 정하는 것과
        // 갈리는 지점이다 — 금리는 전 종목이 일별 계열이라 창이 하나뿐이었다.
        val summary = commodityCollectService.collect(from, end, LocalDateTime.now(ZoneOffset.UTC))

        if (summary.requested == 0) {
            // 우리 설정 실수다. 상류를 확인하러 보내지 않도록 502가 아니라 500으로 낸다.
            // **목록 셋 다 이름을 댄다** — 하나만 대면 다른 쪽이 빈 경우에 운영자가 멀쩡한 목록만 들여다본다
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "수집 대상 원자재가 설정에 없습니다 — application.yml의 " +
                    "market-commodity.fred-daily·market-commodity.fred-monthly·market-commodity.fsc 를 확인하세요",
            )
        }

        // **`collected == 0`만으로 502를 내면 안 된다.** 일부 종목이 정상적으로 빈 응답을 준 날에도
        // 낮아지기 때문이다. 그래서 두 가지만 잡는다.
        //  1. 저장 0 + 실패 있음 — 상류 장애이거나 테이블이 없다.
        //  2. 저장 0 + **전 종목이 빈 응답** — 시리즈 ID가 전부 틀렸다는 뜻이다.
        //     창이 주기에 맞춰져 있어서(일간 14일 · 월간 400일) 살아 있는 계열은 반드시 값을 준다 —
        //     즉 "전부 정상적으로 비었다"는 상태는 존재하지 않는다. 이걸 빼면 시리즈 ID를 전부
        //     잘못 넣은 잡이 영원히 초록으로 끝난다.
        //     **이 분기의 근거가 창 길이라는 데 주의할 것** — 창을 줄이면 월간이 정상적으로도
        //     비게 되고, 그러면 이 500은 없는 장애를 쫓게 만드는 거짓 경보가 된다.
        // 일부만 빈 경우는 여전히 200이다 — 계열이 중단·신규 편입일 수 있고 `emptySeries`가 설명한다.
        //
        // **두 경우의 상태 코드가 다르다** — 상태 코드는 운영자를 어디로 보낼지를 정한다.
        //  · 전량 실패 → 502. 우리 요청은 멀쩡했고 상류(또는 DB)가 답을 못 줬다.
        //  · 전 종목 0건 → 500. 상류는 정상 응답을 줬고, 틀린 건 우리가 넣은 시리즈 ID다.
        if (summary.collected == 0 && (summary.failed > 0 || summary.emptySeries.size == summary.requested)) {
            val (status, reason) =
                if (summary.failed > 0) {
                    HttpStatus.BAD_GATEWAY to
                        "원자재를 한 건도 수집하지 못했습니다 — 전량 실패 (요청 ${summary.requested}건, ${summary.from}~${summary.to}): " +
                        summary.failures.joinToString("; ").ifBlank { "사유 없음" }
                } else {
                    HttpStatus.INTERNAL_SERVER_ERROR to
                        "원자재를 한 건도 수집하지 못했습니다 — 전 종목 0건 (요청 ${summary.requested}건, ${summary.from}~${summary.to}): " +
                        "시리즈 ID를 확인하세요 (GET /api/admin/commodity/config). " +
                        "대상: " + summary.emptySeries.joinToString(", ")
                }
            throw ResponseStatusException(status, reason)
        }
        return ResponseEntity.ok(summary)
    }

    private fun view(group: String, item: CommodityProperties.CommodityItem) = CommodityConfigItemView(
        group = group,
        code = item.code,
        seriesId = item.seriesId,
        unit = item.unit,
        frequency = item.frequency,
    )
}
