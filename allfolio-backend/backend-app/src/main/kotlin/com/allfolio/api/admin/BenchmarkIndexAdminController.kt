package com.allfolio.api.admin

import com.allfolio.market.benchmark.BenchmarkCollectSummary
import com.allfolio.market.benchmark.BenchmarkIndexProperties
import com.allfolio.market.benchmark.FscIndexCollectService
import com.allfolio.unifiedasset.domain.benchmark.BenchmarkType
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
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 설정에 실린 벤치마크 지수 한 종. 진단 조회([BenchmarkIndexAdminController.config])의 응답 요소다.
 *
 * @param knownType `type`이 [BenchmarkType]에 실제로 있는가. **이 필드가 이 조회의 핵심이다** —
 *                  기동 시 설정 검증은 일부러 도메인 enum을 안 본다(근거는
 *                  `BenchmarkIndexProperties.BenchmarkIndexItem.type`의 필드 KDoc에 있다).
 *                  그래서 `type: KOSPPI` 같은 오타는 기동을 막지 못하고 수집 시점의 `valueOf`에서야
 *                  터진다. 배포 후 여기서 먼저 보이게 한다
 */
data class BenchmarkIndexConfigItemView(
    val type: String,
    val idxNm: String,
    val idxCsf: String,
    val knownType: Boolean,
)

/** @param total 항목 수. 배포 후 "설정이 통째로 사라지지 않았나"를 한 눈에 본다 */
data class BenchmarkIndexConfigView(
    val total: Int,
    val items: List<BenchmarkIndexConfigItemView>,
)

/**
 * 벤치마크 지수 수집 어드민 (AF-107).
 *
 * [CommodityAdminController]를 따라 만들었다 — 같은 판정(전멸 502 / 부분 실패 200 / 대상 0건 500),
 * 같은 상한, 같은 KST 기준 날짜. 다른 점은 둘이다:
 *  1. **500의 원인 목록** — 마이그레이션 부재가 빠지고 `type` 오타가 들어온다. 아래 [collect] KDoc 참조
 *  2. **기본 창을 서비스가 아니라 여기서 정한다** — 원자재는 종목마다 주기가 달라 서비스만 창을
 *     알 수 있었지만 여기는 전부 일별 지수라 창이 하나뿐이다([WINDOW_DAYS]). 그래서 `from`이
 *     null이어도 구간 길이가 정해지고, 원자재의 `from != null` 가드 없이 검사가 늘 돈다
 */
@RestController
@RequestMapping("/api/admin/benchmark-index")
class BenchmarkIndexAdminController(
    private val fscIndexCollectService: FscIndexCollectService,
    private val properties: BenchmarkIndexProperties,
) {
    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")

        /**
         * 기본 수집 창. 달력 14일이면 연휴가 끼어도 국내 영업일이 5~6일은 들어온다.
         * 영업일을 세지 않는 이유는 공휴일 달력을 들일 값어치가 없어서다 —
         * `MarketRateAdminController.WINDOW_DAYS`와 같은 값·같은 근거다.
         *
         * **이 길이를 줄이면 `FscIndexCollectService`의 `emptySeries` KDoc과
         * 아래 "전 지수 0건 = 500" 판정을 다시 볼 것.** 그 둘의 근거가 "살아 있는 지수는
         * 창에 영업일이 있으면 반드시 값을 준다"이고, 그 전제가 곧 이 숫자다.
         */
        private const val WINDOW_DAYS = 14L

        /**
         * 한 번에 허용하는 최대 구간(일). 2년 + 윤년 여유.
         *
         * [CommodityAdminController]·[MarketRateAdminController]와 **같은 값을 쓴다.**
         * 저장 경로가 달라(여긴 `BenchmarkDailyStore.upsert` 한 방이라 행마다 SELECT를 내지 않는다)
         * 더 늘려도 될 것처럼 보이지만, 상한을 소스마다 다르게 두면 "백필은 몇 년씩 끊나"라는
         * 질문의 답이 엔드포인트마다 갈린다. 1년 백필(365일)은 이 안에 넉넉히 들어온다.
         */
        private const val MAX_RANGE_DAYS = 732L
    }

    /**
     * GET /api/admin/benchmark-index/config — 수집 대상 설정 조회 (AF-107).
     *
     * **상류를 부르지 않는다.** 배포 후 "설정이 의도대로 실렸나"만 본다.
     * 필요한 이유는 [BenchmarkIndexProperties]의 KDoc에 있다: 스프링은 리스트를 병합하지 않고
     * 우선순위가 높은 쪽으로 **통째로 교체**하므로, 환경변수 한 줄이 목록을 통째로 지울 수 있다.
     * 그 상태의 증상은 "수집은 초록인데 KOSPI가 안 쌓인다"라서 로그로는 안 보인다.
     *
     * 인증키는 실리지 않는다 — 나가는 값은 `코스피`·`KOSPI시리즈` 같은 공개 조회 파라미터뿐이다.
     */
    @GetMapping("/config")
    fun config(): ResponseEntity<BenchmarkIndexConfigView> {
        val known = BenchmarkType.entries.map { it.name }.toSet()
        val items = properties.fsc.map { item ->
            BenchmarkIndexConfigItemView(
                type = item.type,
                idxNm = item.idxNm,
                idxCsf = item.idxCsf,
                knownType = item.type in known,
            )
        }
        return ResponseEntity.ok(BenchmarkIndexConfigView(total = items.size, items = items))
    }

    /**
     * POST /api/admin/benchmark-index/collect — 벤치마크 지수 수집 (어드민 전용, AF-107).
     *
     * **날짜를 주지 않으면 KST 오늘 기준 최근 14일이다.** 일일 수집과 백필이 같은 경로인 이유는
     * 둘이 같은 일이기 때문이다 — "이 구간을 소스가 준 값으로 맞춘다", 그리고 UPSERT라 멱등하다.
     * 초기 1년 백필은 `?from=2025-08-17&to=2026-08-17`처럼 구간을 실어 부른다.
     * 2년(+윤년 여유)을 넘는 구간은 400이다.
     *
     * **`LocalDate.now()`가 아니라 KST로 옮겨 오늘을 구한다** — Render 컨테이너는 UTC라
     * KST 새벽 실행이 하루 전으로 밀린다. `CommodityAdminController`·`MarketRateAdminController`·
     * `FxRateAdminController`가 같은 자리에 같은 방어를 한다.
     *
     * **전멸은 502, 부분 실패는 200, 우리 설정 문제는 500이다.** 상태 코드가 정하는 건
     * "운영자를 어디로 보낼지"이고, 그 판단은 [MarketRateAdminController.collect]와 같다.
     *
     * **500의 원인이 원자재와 다르다.** `benchmark_daily`는 이미 있는 표라 마이그레이션 부재가
     * 원인이 될 수 없다(원자재의 가장 흔한 첫 배포 실패가 그것이었다). 여기서 500은 셋이다:
     *  1. `benchmark-index.fsc`가 비었다 (`requested == 0`)
     *  2. 전 지수 0건 — `(idxNm, idxCsf)` 쌍이 틀렸거나 인증키가 이 오퍼레이션에 미승인이다
     *  3. `type`이 [BenchmarkType]에 없다 — 설정 오타다
     *
     * **3번을 502에 섞지 않는 것이 이 메서드가 원자재와 갈리는 유일한 지점이다.** 지수가
     * KOSPI 하나뿐이라 `type` 오타 하나면 전량 실패가 되는데, 그걸 502로 부르면 운영자가
     * 멀쩡한 공공데이터포털 상태를 확인하러 간다. 할 일은 `application.yml`을 고치는 것이다.
     * (수집 서비스는 `valueOf`를 지수별 try 안에 둬 나머지를 살린다 — 그 격리는 그대로다.
     * 여기서 보는 건 "설정된 지수가 **전부** 미지의 type인가"뿐이라 격리와 충돌하지 않는다.)
     */
    @PostMapping("/collect")
    fun collect(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): ResponseEntity<BenchmarkCollectSummary> {
        val end = to ?: LocalDate.now(KST)

        // **백필용으로 `from`을 주면 그대로 쓴다.** 기본 창을 여기서 정하는 이유는 원자재와 갈린다 —
        // 원자재는 종목마다 주기가 달라 서비스만 창을 알 수 있었지만, 여기는 전부 일별 지수라
        // 창이 하나뿐이다. `MarketRateAdminController`와 같은 모양이다.
        val start = from ?: end.minusDays(WINDOW_DAYS)

        // 뒤집힌 구간은 서비스가 require로 잡아 400이 된다. 여기서는 길이만 본다.
        if (ChronoUnit.DAYS.between(start, end) > MAX_RANGE_DAYS) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "한 번에 요청할 수 있는 구간은 최대 ${MAX_RANGE_DAYS}일입니다 ($start~$end) — " +
                    "1~2년씩 끊어 호출하세요 (예: ?from=2025-08-17&to=2026-08-17)",
            )
        }

        val summary = fscIndexCollectService.collect(start, end)

        if (summary.requested == 0) {
            // 우리 설정 실수다. 상류를 확인하러 보내지 않도록 502가 아니라 500으로 낸다
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "수집 대상 지수가 설정에 없습니다 — application.yml의 benchmark-index.fsc 를 확인하세요",
            )
        }

        // **`saved == 0`만으로 502를 내면 안 된다** — 지수 일부가 정상적으로 빈 응답을 준 날에도
        // 0이 될 수 있다. 그래서 두 가지만 잡는다. 근거는 [MarketRateAdminController.collect]와 같다.
        //  1. 저장 0 + 실패 있음 — 상류 장애이거나 설정 오타다(아래에서 다시 가른다).
        //  2. 저장 0 + 전 지수 빈 응답 — (idxNm, idxCsf) 쌍이 틀렸다는 뜻이다. 창이 14일이라
        //     살아 있는 지수는 반드시 값을 준다 — "전부 정상적으로 비었다"는 상태는 없다.
        // 일부만 빈 경우는 여전히 200이고 `emptySeries`가 설명한다.
        if (summary.saved == 0 && (summary.failed > 0 || summary.emptySeries.size == summary.requested)) {
            val unknown = unknownTypes()
            val (status, reason) = when {
                // 설정된 지수가 전부 미지의 type이면 상류는 애초에 불리지도 않았다 —
                // 502로 부르면 운영자를 멀쩡한 포털로 보낸다
                unknown.size == summary.requested ->
                    HttpStatus.INTERNAL_SERVER_ERROR to
                        "지수를 한 건도 수집하지 못했습니다 — type이 BenchmarkType에 없습니다 " +
                        "(${unknown.joinToString(", ")}): application.yml의 benchmark-index.fsc 를 고치세요. " +
                        "가능한 값: " + BenchmarkType.entries.joinToString(", ") { it.name }

                summary.failed > 0 ->
                    HttpStatus.BAD_GATEWAY to
                        "지수를 한 건도 수집하지 못했습니다 — 전량 실패 (요청 ${summary.requested}건, ${summary.from}~${summary.to}): " +
                        summary.failures.joinToString("; ").ifBlank { "사유 없음" }

                else ->
                    HttpStatus.INTERNAL_SERVER_ERROR to
                        "지수를 한 건도 수집하지 못했습니다 — 전 지수 0건 (요청 ${summary.requested}건, ${summary.from}~${summary.to}): " +
                        "(idxNm, idxCsf) 쌍과 인증키 승인 상태를 확인하세요 (GET /api/admin/benchmark-index/config). " +
                        "대상: " + summary.emptySeries.joinToString(", ")
            }
            throw ResponseStatusException(status, reason)
        }
        return ResponseEntity.ok(summary)
    }

    /**
     * 설정에 실렸지만 [BenchmarkType]에 없는 `type`들.
     *
     * 기동 시 설정 검증이 이 검사를 안 하는 이유는
     * `BenchmarkIndexProperties.BenchmarkIndexItem.type`의 필드 KDoc에 있다 —
     * 설정 클래스를 도메인 enum에 묶지 않으려는 것이다. 어드민 컨트롤러는 이미 도메인을 안다.
     * (`validate()`의 KDoc은 "조용한 0건"을 막는 이야기라 이 근거가 아니다.)
     */
    private fun unknownTypes(): List<String> {
        val known = BenchmarkType.entries.map { it.name }.toSet()
        return properties.fsc.map { it.type }.filter { it !in known }
    }
}
