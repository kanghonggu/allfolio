package com.allfolio.api.admin

import com.allfolio.market.index.DomesticIndexCollectSummary
import com.allfolio.market.index.IndexCollectService
import com.allfolio.market.index.IndexSlot
import com.allfolio.market.index.KisIndexClient
import com.allfolio.market.index.KisIndexException
import com.allfolio.market.index.OverseasIndexCollectService
import com.allfolio.market.index.OverseasIndexCollectSummary
import com.allfolio.market.index.OverseasSchedule
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

@RestController
@RequestMapping("/api/admin/market-index")
class MarketIndexAdminController(
    private val kisIndexClient: KisIndexClient,
    private val indexCollectService: IndexCollectService,
    private val overseasIndexCollectService: OverseasIndexCollectService,
) {
    /**
     * GET /api/admin/market-index/raw?iscd=0001 — KIS 원본 응답 그대로 (AF-101).
     *
     * 파서를 쓰기 전에 필드의 실제 타입·형식을 눈으로 확인하기 위한 것이다.
     * 등락률이 `1.23`인지 `0.0123`인지, 값이 문자열인지 숫자인지는 공식 샘플로 확정되지 않았고,
     * 추측해서 파서를 쓰면 잘못된 가정 위에 테스트까지 쌓인다.
     */
    @GetMapping("/raw")
    fun raw(@RequestParam iscd: String): ResponseEntity<Map<String, Any?>> =
        try {
            ResponseEntity.ok(kisIndexClient.fetchRaw(iscd))
        } catch (e: KisIndexException) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, e.message)
        }

    /**
     * GET /api/admin/market-index/raw-overseas?iscd=SPX&from=2026-08-01&to=2026-08-13
     * — KIS 해외 지수 원본 응답 그대로 (AF-110).
     *
     * `/raw`와 같은 이유로 존재한다. 해외 지수 응답은 `output1`·`output2` 두 갈래로 오는데
     * 최신 봉이 어느 쪽에 실리는지가 확정되지 않았고, 이 엔드포인트는 **그걸 눈으로 보려고**
     * 있는 것이다. 국내 지수(AF-101)에서 등락률 단위와 부호 규약을 맞힌 것도 파서를 쓰기 전에
     * 이 단계를 밟았기 때문이다. 여기에 필드 매핑이나 DTO를 얹으면 존재 이유가 사라진다.
     *
     * **`from`/`to`에 기본값을 두지 않는다.** 파라미터 이름을 오타내면 기본 구간이 조용히
     * 채워져 아무도 고르지 않은 창을 조회하게 되는데, 응답은 멀쩡해 보여서 그게 엉뚱한 구간의
     * 데이터라는 걸 알아볼 수가 없다. `/collect`의 `slot`에 기본값을 두지 않은 것과 같은 이유다.
     */
    @GetMapping("/raw-overseas")
    fun rawOverseas(
        @RequestParam iscd: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate,
    ): ResponseEntity<Map<String, Any?>> =
        try {
            ResponseEntity.ok(kisIndexClient.fetchOverseasRaw(iscd, from, to))
        } catch (e: KisIndexException) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, e.message)
        }

    /**
     * POST /api/admin/market-index/collect?slot=CLOSE — 국내 지수 수집 (어드민 전용, AF-101).
     *
     * **`slot`에 기본값을 두지 않는다.** 파라미터 이름을 오타내면(`slott=CLOSE`) 기본값이 조용히
     * 엉뚱한 슬롯으로 저장하는데, 그 행은 값이 멀쩡해서 사후에 틀렸다는 걸 알아볼 수가 없다.
     * 잘못된 슬롯으로 남은 데이터보다 400이 낫다.
     *
     * **UTC 시각을 넘긴다.** [IndexCollectService.collect]는 받은 시각을 UTC로 보고 KST로 옮겨
     * 거래일과 시장상태를 뽑는다. KST 머신에서 `LocalDateTime.now()`를 넘기면 +9가 두 번 먹어
     * 15:50 수집이 자정을 넘겨 다음 날 거래일로 박힌다. Render 컨테이너는 UTC라 운영에선 우연히
     * 맞지만, 그 우연에 기대면 로컬에서 재현할 때마다 날짜가 달라진다.
     *
     * **전멸은 502, 부분 실패는 200이다.** [IndexCollectService.collect]는 지수 하나가 터져도
     * 나머지를 살리려고 예외 대신 요약으로 돌려준다(그게 맞다 — 예외로 끝내면 살아 있던 두 건까지
     * 잃는다). 그런데 그 대가로 **아무것도 던지지 않게 되어**, 그대로 200을 내면 세 지수가 전부
     * 실패한 날에도 크론 잡이 초록으로 끝나고 지수 데이터가 끊긴 걸 아무도 모른다. AF-103에서
     * 안전장치가 422로 잡을 빨갛게 만들어 사람을 부르게 한 것과 같은 원칙이다:
     * 조용한 수집 중단은 반드시 보여야 한다.
     *
     * 반대로 부분 실패까지 빨갛게 칠하면 안 된다. 지수 하나가 간헐적으로 실패할 때마다 잡이
     * 빨개지면 매일 빨간 잡을 보게 되고, 그러면 진짜 전멸한 날에도 아무도 안 본다.
     * 부분 실패는 요약의 `failures`에 이미 실려 잡 요약에 남는다.
     *
     * `requested > 0` 조건을 빼면 안 된다. `requested == 0`은 "설정에 지수가 하나도 없다"는
     * 설정 상태이지 상류 장애가 아니다. 이걸 502로 내보내면 운영자가 KIS를 확인하러 가는데
     * 진짜 문제는 빠진 YAML 블록이다.
     *
     * **대신 `requested == 0`은 500이다.** 502에서 빼 놓고 아무 데도 안 걸면 조용한 수집 중단이
     * 다른 길로 되돌아온다: `application.yml`의 `market-index:` 키 이름을 바꾸거나 리팩터링에서
     * `@ConfigurationProperties` prefix가 어긋나면 목록이 비고, 서비스는 아무도 안 읽는 WARN을
     * 찍고, 여기서 200이 나가고, 워크플로는 본문을 일부러 파싱하지 않아 잡이 영원히 초록으로 끝난다.
     * 502가 막으려던 바로 그 실패다. 502가 아니라 500인 이유는 이게 **우리 설정 실수**이지 KIS의
     * 오작동이 아니기 때문이다 — 502로 부르면 운영자가 남의 API를 확인하느라 시간을 버린다.
     *
     * [KisIndexException]을 502로 갈아끼우는 이유는 백필·하나은행 엔드포인트와 같다 —
     * 요청은 멀쩡했고 상류 응답이 이상한 것이라, 전역 폴백의 500 + "서버 오류"로 뭉개지면
     * 운영자가 우리 버그를 찾으러 간다. **다만 오늘 이 catch로 들어오는 경로는 없다** —
     * 수집 중 발생한 [KisIndexException]은 서비스의 지수별 try/catch가 삼켜 요약의 `failures`로
     * 바뀌기 때문이다. 죽은 코드로 보고 지우지 말 것(호출 경로가 늘면 다시 살아난다).
     * 반대로 이게 살아 있는 경로라고 믿고 여기에 실패 처리를 얹지도 말 것 — 실패는 요약으로 온다.
     */
    @PostMapping("/collect")
    fun collect(@RequestParam slot: IndexSlot): ResponseEntity<DomesticIndexCollectSummary> {
        val summary = try {
            indexCollectService.collect(slot, LocalDateTime.now(ZoneOffset.UTC))
        } catch (e: KisIndexException) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, e.message)
        }

        if (summary.requested == 0) {
            // 우리 설정 실수다. KIS를 확인하러 보내지 않도록 502가 아니라 500으로 낸다
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "수집 대상 국내 지수가 설정에 없습니다 — application.yml의 market-index.domestic 을 확인하세요 " +
                    "(slot=${summary.slot})",
            )
        }

        if (summary.requested > 0 && summary.collected == 0) {
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "국내 지수를 한 건도 수집하지 못했습니다 (slot=${summary.slot}, 요청 ${summary.requested}건): " +
                    summary.failures.joinToString("; ").ifBlank { "사유 없음" },
            )
        }
        return ResponseEntity.ok(summary)
    }

    /**
     * POST /api/admin/market-index/collect-overseas?schedule=US — 해외 지수 수집 (어드민 전용, AF-110).
     *
     * **상태코드 규약(500 / 502 / 200)과 그 근거는 위 [collect]의 KDoc에 있다.** 같은 이유가
     * 그대로 적용되므로 여기서 요약해 다시 쓰지 않는다 — 두 벌이 되면 한쪽만 고쳐져 갈라진다.
     * 해외에서 달라지는 것만 아래에 적는다.
     *
     * **`schedule`은 [String]이 아니라 [OverseasSchedule]로 받는다.** 서비스는 문자열을 받지만
     * 그 타입을 여기까지 끌고 오면 URL 오타(`schedule=Us`)가 설정 대조에서 0건이 되어
     * `requested == 0` → **500**으로 나온다. 500 문구는 운영자를 `market-index.overseas`로
     * 보내는데 그 yml은 멀쩡하고, 진짜 원인은 워크플로가 실어 보낸 URL이다. enum이면 Spring이
     * 변환에서 400을 내고 받은 값을 문구에 싣는다 — 국내 `slot`이 [IndexSlot]으로 얻는 것과 같다.
     * 서비스에는 `schedule.name`을 넘긴다(`"US"` 같은 리터럴을 박으면 ASIA 슬롯이 미국 지수를
     * 수집한다 — 저장되는 값은 그럴듯해서 눈으로는 못 잡는다).
     *
     * **기본값을 두지 않는 이유도 국내와 같다.** 다만 해외는 슬롯이 곧 시장군이라 손해의 모양이
     * 다르다: 빠뜨린 슬롯이 조용히 US로 떨어지면 아시아 3종은 며칠이고 아무도 수집하지 않고,
     * 빈 자리는 실패가 아니라 "그냥 없는 데이터"로 보여서 경보에도 안 걸린다.
     *
     * **`Instant`를 여기서 넣는다.** 국내가 `LocalDateTime.now(ZoneOffset.UTC)`를 넣는 것과 같은
     * 원칙 — 시각 변환은 한 곳에만 있어야 한다. [OverseasIndexCollectService.collect]가
     * `LocalDateTime` 대신 [Instant]를 받는 덕에 국내에 있던 "UTC로 해석한다"는 규약 자체가 없다.
     *
     * `requested == 0` 문구는 `market-index.overseas`를 가리켜야 한다. 국내 문구를 복사해
     * `domestic`이 남으면 운영자가 아무 관계 없는 블록을 들여다본다.
     *
     * [KisIndexException] → 502 catch도 국내와 같다. **오늘 이 catch로 들어오는 경로가 없는 것도
     * 같다** — 지수별 try/catch가 삼켜 요약의 `failures`가 된다. 죽은 코드로 보고 지우지 말 것.
     */
    @PostMapping("/collect-overseas")
    fun collectOverseas(
        @RequestParam schedule: OverseasSchedule,
    ): ResponseEntity<OverseasIndexCollectSummary> {
        val summary = try {
            overseasIndexCollectService.collect(schedule.name, Instant.now())
        } catch (e: KisIndexException) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, e.message)
        }

        if (summary.requested == 0) {
            // 우리 설정 실수다. KIS를 확인하러 보내지 않도록 502가 아니라 500으로 낸다
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "수집 대상 해외 지수가 설정에 없습니다 — application.yml의 market-index.overseas 를 확인하세요 " +
                    "(schedule=${summary.schedule})",
            )
        }

        if (summary.requested > 0 && summary.collected == 0) {
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "해외 지수를 한 건도 수집하지 못했습니다 (schedule=${summary.schedule}, " +
                    "요청 ${summary.requested}건): " +
                    summary.failures.joinToString("; ").ifBlank { "사유 없음" },
            )
        }
        return ResponseEntity.ok(summary)
    }
}
