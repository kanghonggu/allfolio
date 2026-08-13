package com.allfolio.api.admin

import com.allfolio.market.index.DomesticIndexCollectSummary
import com.allfolio.market.index.IndexCollectService
import com.allfolio.market.index.IndexSlot
import com.allfolio.market.index.KisIndexClient
import com.allfolio.market.index.KisIndexException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime
import java.time.ZoneOffset

@RestController
@RequestMapping("/api/admin/market-index")
class MarketIndexAdminController(
    private val kisIndexClient: KisIndexClient,
    private val indexCollectService: IndexCollectService,
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

        if (summary.requested > 0 && summary.collected == 0) {
            throw ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "국내 지수를 한 건도 수집하지 못했습니다 (slot=${summary.slot}, 요청 ${summary.requested}건): " +
                    summary.failures.joinToString("; ").ifBlank { "사유 없음" },
            )
        }
        return ResponseEntity.ok(summary)
    }
}
