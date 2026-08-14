package com.allfolio.api.admin

import com.allfolio.fx.EcosStatListClient
import com.allfolio.market.rate.RateCollectService
import com.allfolio.market.rate.RateCollectSummary
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

        /**
         * 한 번에 허용하는 최대 구간(일). 2년 + 윤년 여유.
         *
         * 주석으로만 "끊어 부르세요"라고 적어 두면 안 지켜진다 — 실제로 이 KDoc 안에서 두 번 어긋났다.
         * 이유는 [RateCollectService]의 KDoc에 있다: 할당식 id + `@Version` 부재 탓에 기존 행마다
         * `em.merge`가 SELECT를 하나씩 내므로, 6.5년치 한 방이면 순차 왕복 만 회로 무료 플랜 Neon
         * 커넥션을 오래 쥔다. 거절이 그것보다 낫다.
         */
        private const val MAX_RANGE_DAYS = 732L

        /**
         * ECOS 통계표·항목 코드 모양. 실제 코드는 `721Y001`·`901Y009`·`0000000001`처럼 영숫자뿐이다.
         *
         * **모양 검사가 보안 장치다.** 이 값은 인증키가 첫 세그먼트로 들어간 경로에 그대로 이어 붙는다
         * ([EcosStatListClient]). `/`·`%2F`·`?`·`#`를 넣으면 요청이 `ecos.bok.or.kr`의 다른 경로나
         * 쿼리로 새면서 **우리 인증키를 달고 간다** — 되울리는 오류 본문을 일부러 끌어내는 방법이 그것이다.
         * (baseUrl과 경로 접두사가 고정이라 SSRF는 아니다.)
         * 덤으로 `{`도 막힌다: Spring의 URI 템플릿 확장에서 예외가 터져 오타가 "IO"(=연결 실패)로
         * 보고되는데, 그 둘을 갈라 주려고 만든 도구가 그러면 곤란하다.
         */
        private val STAT_CODE = Regex("[A-Za-z0-9]{1,32}")

        private fun requireStatCode(value: String) {
            if (!STAT_CODE.matches(value)) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "통계표·항목 코드는 영숫자 1~32자여야 합니다: $value",
                )
            }
        }
    }

    /**
     * GET /api/admin/rate/ecos/tables?stat=721Y001 — ECOS 통계표 목록 (AF-102).
     *
     * 수집 대상 코드를 확인하기 위한 것이다. **추정한 코드를 설정에 넣지 말 것** —
     * ECOS는 틀린 코드에 오류가 아니라 0건을 주므로, 잘못 넣으면 "기간이 비었다"와 구분되지 않는다.
     * 응답은 파싱하지 않고 그대로 나간다(오류 응답도 그대로 보여야 코드 실수가 드러난다).
     * 인증키만 `***`로 가린다 — 그건 확인하러 온 대상이 아니고, 이 본문은 브라우저·붙여넣기로 흘러간다.
     *
     * **응답의 `list_total_count`가 10000을 넘으면 목록이 잘린 것이다** — 단발 조회라 페이지가 없다.
     *
     * `produces`를 달지 않는다: "받은 걸 그대로 보여준다"가 전제인데, 프록시가 가로챈 HTML을
     * JSON이라고 라벨링하면 그 전제를 스스로 깬다.
     *
     * ECOS 실패는 그대로 올려보낸다 — `GlobalExceptionHandler`가 code까지 실어 502/500으로 가른다.
     * 여기서 502로 갈아끼우면 키가 없는 상태(`NO_KEY`)까지 502가 되어, 정작 할 일이
     * `ECOS_API_KEY` 등록인 운영자를 한국은행 상태 페이지로 보낸다 — 이 엔드포인트를 부르는 시점이
     * 대개 키를 넣기 **전**이라 하필 가장 흔한 경로다.
     */
    @GetMapping("/ecos/tables")
    fun tables(@RequestParam(required = false) stat: String?): ResponseEntity<String> {
        stat?.let { requireStatCode(it) }
        return ResponseEntity.ok(statListClient.tables(stat))
    }

    /**
     * GET /api/admin/rate/ecos/items?stat=721Y001 — 통계표 하나의 항목 목록.
     *
     * 나머지 판단은 [tables]와 같다(원본 그대로, 인증키만 마스킹, 실패는 전역 핸들러).
     * 여기도 `list_total_count`가 10000을 넘으면 잘린 것이다.
     */
    @GetMapping("/ecos/items")
    fun items(@RequestParam stat: String): ResponseEntity<String> {
        requireStatCode(stat)
        return ResponseEntity.ok(statListClient.items(stat))
    }

    /**
     * POST /api/admin/rate/collect — 금리 수집 (어드민 전용, AF-102).
     *
     * **날짜를 주지 않으면 KST 오늘 기준 최근 2주다.** 일일 수집과 백필이 같은 경로인 이유는
     * 둘이 같은 일이기 때문이다 — "이 구간을 ECOS가 준 값으로 맞춘다", 그리고 멱등하다.
     * 초기 백필은 **1~2년씩 끊어** 부른다 (예: `?from=2020-01-01&to=2021-12-31`, 그다음
     * `?from=2022-01-01&to=2023-12-31` …). 이유는 [RateCollectService]의 KDoc에 있고,
     * 지키지 않으면 거절한다 — 2년(+윤년 여유)을 넘는 구간은 400이다.
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

        // 뒤집힌 구간은 서비스가 require로 잡아 400이 된다. 여기서는 길이만 본다.
        if (ChronoUnit.DAYS.between(start, end) > MAX_RANGE_DAYS) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "한 번에 요청할 수 있는 구간은 최대 ${MAX_RANGE_DAYS}일입니다 ($start~$end) — " +
                    "1~2년씩 끊어 호출하세요 (예: ?from=2020-01-01&to=2021-12-31)",
            )
        }

        val summary = rateCollectService.collect(start, end, LocalDateTime.now(ZoneOffset.UTC))

        if (summary.requested == 0) {
            // 우리 설정 실수다. ECOS를 확인하러 보내지 않도록 502가 아니라 500으로 낸다
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "수집 대상 금리가 설정에 없습니다 — application.yml의 market-rate.series 를 확인하세요",
            )
        }

        // **`collected == 0`만으로 502를 내면 안 된다.** `collected`는 "실제로 저장한 행 수"라,
        // 일부 종목이 정상적으로 빈 응답을 준 날에도 낮아진다(긴 연휴 등). 그걸 502로 부르면
        // 운영자가 멀쩡한 한국은행을 확인하러 간다.
        // **여기 기준금리를 예로 들지 말 것.** "변경 시에만 공표된다"고 적혀 있었지만 실측은
        // 반대였다 — BASE_RATE는 2020-01-01 이후 2,415행으로 주말까지 포함한 달력 전일 행이 있고,
        // 공표만 시장금리보다 이틀쯤 늦다. BASE_RATE가 비면 정상이 아니라 깨진 신호다.
        //
        // 그래서 두 가지만 잡는다.
        //  1. 저장 0 + 실패 있음 — 상류 장애다.
        //  2. 저장 0 + **전 종목이 빈 응답** — 코드가 전부 틀렸다는 뜻이다. 설정 6종이 전부
        //     일별(cycle: D) 계열이고, 달력 14일에 국내 영업일이 하나도 없는 경우는 없다.
        //     즉 "전부 정상적으로 비었다"는 상태는 존재하지 않는다.
        //     이걸 빼면 통계표 코드를 전부 잘못 넣은 잡이 영원히 초록으로 끝난다 —
        //     ECOS가 틀린 코드에 0건을 주기 때문에 생긴, 이 기능이 존재하는 이유 그 자체인 실패다.
        // 일부만 빈 경우는 여전히 200이고, `emptySeries`가 설명하며 워크플로가 경고로 띄운다.
        //
        // **두 경우의 상태 코드가 다르다.** 둘 다 잡을 빨갛게 만들지만, 상태 코드는 운영자를
        // 어디로 보낼지를 정한다 — 위 `requested == 0`을 502가 아니라 500으로 낸 것과 같은 판단이다.
        //  · 전량 실패 → **502**. 우리 요청은 멀쩡했고 상류가 죽었다.
        //  · 전 종목 0건 → **500**. ECOS는 정상 응답을 줬고, 틀린 건 우리가 넣은 통계표·항목 코드다.
        //    이걸 502로 부르면 운영자가 한국은행 상태를 확인하러 가는데, 할 일은 application.yml을 고치는 것이다.
        if (summary.collected == 0 && (summary.failed > 0 || summary.emptySeries.size == summary.requested)) {
            // 두 경우는 운영자를 서로 다른 곳으로 보낸다 — 문구를 섞으면 그 안내가 사라진다
            val (status, reason) =
                if (summary.failed > 0) {
                    HttpStatus.BAD_GATEWAY to
                        "금리를 한 건도 수집하지 못했습니다 — 전량 실패 (요청 ${summary.requested}건, $start~$end): " +
                        summary.failures.joinToString("; ").ifBlank { "사유 없음" }
                } else {
                    HttpStatus.INTERNAL_SERVER_ERROR to
                        "금리를 한 건도 수집하지 못했습니다 — 전 종목 0건 (요청 ${summary.requested}건, $start~$end): " +
                        "통계표·항목 코드를 확인하세요 (GET /api/admin/rate/ecos/tables). " +
                        "대상: " + summary.emptySeries.joinToString(", ")
                }
            throw ResponseStatusException(status, reason)
        }
        return ResponseEntity.ok(summary)
    }
}
