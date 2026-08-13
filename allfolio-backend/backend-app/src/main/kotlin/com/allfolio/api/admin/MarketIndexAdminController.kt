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
     * [KisIndexException]을 502로 갈아끼우는 이유는 백필·하나은행 엔드포인트와 같다 —
     * 요청은 멀쩡했고 상류 응답이 이상한 것이라, 전역 폴백의 500 + "서버 오류"로 뭉개지면
     * 운영자가 우리 버그를 찾으러 간다.
     */
    @PostMapping("/collect")
    fun collect(@RequestParam slot: IndexSlot): ResponseEntity<DomesticIndexCollectSummary> =
        try {
            ResponseEntity.ok(indexCollectService.collect(slot, LocalDateTime.now(ZoneOffset.UTC)))
        } catch (e: KisIndexException) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, e.message)
        }
}
