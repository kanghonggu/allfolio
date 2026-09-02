package com.allfolio.api.market

import com.allfolio.realasset.watch.WatchValuationClient
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 시계 ref 확인 (W6).
 *
 * **어드민이 아니라 일반 사용자 경로다** — 자산 등록 화면이 부른다. 시세는 공개 데이터라
 * 사용자별 격리가 필요 없다(`RealEstateComplexController`와 같은 자리).
 *
 * ## 왜 "검색"이 아니라 "확인"인가
 *
 * R2(단지·평형)는 목록에서 고르게 했다. 사용자가 단지일련번호와 전용면적을 모르기 때문이고,
 * 손으로 적으면 `84.97`과 `84.93`이 갈리지 않기 때문이다.
 *
 * **시계는 다르다.** ref는 보증서·케이스백에 적혀 있어 사용자가 읽어 올 수 있다. 반면
 * 이름으로 찾게 하려면 목록이 필요한데, watchpricedata에 ref 집계 엔드포인트가 없고
 * `/api/search`는 원본 문서를 준다 — 실측에서 brand가 `롤렉스`/`로렉스`로 갈리고 model이
 * `데이져스트`/`DJ 26mm`로 갈린다. 그걸 클라이언트에서 묶으면 R2가 막으려던 바로 그
 * 불일치를 화면에서 다시 만든다.
 *
 * 그래서 **입력 → 서버 검증 → 미리보기**로 간다. 저장은 사용자가 친 문자열이 아니라
 * **서버가 정규화해 돌려준 `refKey`**를 넣는다 — 불일치는 그 지점에서 막힌다.
 *
 * 이름 검색(자동완성)이 필요해지면 watchpricedata에 `ref_key` 집계 엔드포인트를 먼저
 * 만들어야 한다. 우리 쪽에서 만들어 낼 수 있는 목록이 아니다.
 */
@RestController
@RequestMapping("/api/watch")
class WatchRefLookupController(
    private val client: WatchValuationClient,
) {
    /**
     * GET /api/watch/refs/lookup?ref=126300
     *
     * **표본이 없으면 200에 `found=false`다 — 404가 아니다.** 등록 자체는 막지 않는다.
     * 시세를 못 구하는 시계도 자산으로는 존재하고, 사용자가 취득가를 넣어 보유 현황에
     * 두는 것이 맞다. 화면은 "자동 평가가 안 된다"고만 말하면 된다.
     *
     * **평가 캐시가 아니라 상류를 직접 부른다.** 캐시(`watch_valuation_cache`)에는 이미
     * 등록된 ref만 있어서, 등록하려는 새 ref는 거기 없다. 여기가 이 프로젝트에서
     * 사용자 요청이 watchpricedata로 나가는 유일한 자리다 —
     * **평가 경로는 여전히 로컬만 읽는다**(설계 7절). 등록은 하루 한 번이 아니라
     * 사람이 한 번 하는 일이라 지연을 감수할 수 있다.
     */
    @GetMapping("/refs/lookup")
    fun lookup(@RequestParam ref: String): ResponseEntity<WatchRefLookupView> {
        val trimmed = ref.trim()
        if (trimmed.isEmpty()) {
            return ResponseEntity.ok(WatchRefLookupView(found = false))
        }

        val v = client.valuate(trimmed)
            ?: return ResponseEntity.ok(WatchRefLookupView(found = false, ref = trimmed))

        return ResponseEntity.ok(
            WatchRefLookupView(
                found = true,
                // 🔴 **서버가 매칭에 쓴 키다. 정규화된 값이라는 뜻이 아니다** — 실측
                // (2026-09-02) 상류는 입력을 그대로 되울린다(`116238 CHSJ` → `116238 CHSJ`).
                // 그래도 이 값을 저장하는 이유는 그것이 조회에 실제로 쓰인 키이고, 상류가
                // 나중에 정규화를 하게 되면 자동으로 따라가기 때문이다.
                ref = v.refKey ?: v.ref ?: trimmed,
                sampleSize = v.sampleSize,
                medianKrw = v.median,
                asOf = v.asOf?.toString(),
                windowDays = v.windowDays,
                confidence = v.confidence,
                officialPriceKrw = v.officialPriceKrw,
            ),
        )
    }
}

/**
 * `median`이 null인 응답은 [WatchValuationClient]가 이미 걸러 null로 돌려주므로
 * `found=true`면 [medianKrw]가 있다.
 */
data class WatchRefLookupView(
    val found: Boolean,
    val ref: String? = null,
    val sampleSize: Int? = null,
    val medianKrw: Long? = null,
    /** 🔴 관측일이 아니라 30일 창의 끝이다 — 화면 문구가 그걸 말해야 한다 */
    val asOf: String? = null,
    val windowDays: Int? = null,
    val confidence: String? = null,
    val officialPriceKrw: Long? = null,
)
