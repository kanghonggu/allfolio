package com.allfolio.api.market

import com.allfolio.realasset.watch.RefSearchOutcome
import com.allfolio.realasset.watch.WatchRefSearchClient
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 시계 ref 후보 찾기 (AF-207) — 모델명(예: Daytona)으로 ref 후보를 고르게 한다.
 *
 * 2026-09-09 데모 리뷰: "보통 데이토나라고 검색하지, 레퍼런스 번호는 UX적으로 안 좋다."
 *
 * ## 확인 단계를 대신하지 않는다
 *
 * 이 엔드포인트는 [WatchRefLookupController] **앞에** 붙는다. 사용자가 후보를 고르면 화면은
 * 그 `refKey`를 그대로 `/api/watch/refs/lookup`에 넘기고, 저장은 여전히 **확인 단계가 돌려준
 * 매칭 키**다(#224 "검색이 아니라 확인이다"). 후보 목록은 상류가 ref 단위로 묶어 준 것이라
 * `롤렉스`/`로렉스`를 화면에서 묶는 일은 생기지 않는다.
 *
 * ## 인증
 *
 * `/api/watch/` 아래 경로는 `SecurityConfig`의 `anyRequest().authenticated()`에 걸린다 — lookup과 같다.
 * 따로 열거나 닫지 않는다.
 *
 * ## 🔴 응답은 늘 200이다 — 상태로 가른다
 *
 * - `status=OK`, `candidates=[]` → **후보가 없다.** 화면은 영문 모델명·번호로 다시 찾으라고 안내한다.
 * - `status=UNAVAILABLE` → **검색을 못 했다.** 화면은 번호로 직접 확인하라고 안내한다.
 *
 * 상류 실패를 5xx로 내보내지 않는 이유: 화면에서 axios 오류와 "검색 불가"가 같은 갈래로
 * 가야 하는데, 5xx면 그 갈래가 프록시·인증 오류와 섞인다. 상태로 말해 두면 진짜 우리 쪽
 * 오류만 오류로 남는다.
 */
@RestController
@RequestMapping("/api/watch")
class WatchRefSearchController(
    private val client: WatchRefSearchClient,
) {
    /**
     * GET /api/watch/refs/search?q=daytona&brand=롤렉스&size=20
     *
     * 상류에 보내지 않고 바로 `OK []`를 주는 경우 — 무료 티어 호출을 아끼려는 것이다:
     * - 공백을 뗀 `q`가 [MIN_QUERY_LENGTH]자 미만(상류도 `[]`를 준다 — 자동완성 첫 타자)
     * - `q`·`brand`가 [MAX_QUERY_LENGTH]자 초과(상류는 400을 준다. 그렇게 긴 ref·브랜드는 없으니
     *   "후보 없음"이 정확한 답이고, 상류 400을 "검색 불가"로 내보내면 거짓이 된다)
     */
    @GetMapping("/refs/search")
    fun search(
        @RequestParam q: String,
        @RequestParam(required = false) brand: String?,
        @RequestParam(required = false, defaultValue = "$DEFAULT_SIZE") size: Int,
    ): ResponseEntity<WatchRefSearchView> {
        val query = q.trim()
        val brandFilter = brand?.trim()?.takeIf { it.isNotEmpty() }

        if (query.length < MIN_QUERY_LENGTH ||
            query.length > MAX_QUERY_LENGTH ||
            (brandFilter?.length ?: 0) > MAX_QUERY_LENGTH
        ) {
            return ResponseEntity.ok(WatchRefSearchView(status = WatchRefSearchStatus.OK))
        }

        val view = when (val outcome = client.search(query, brandFilter, size.coerceIn(1, MAX_SIZE))) {
            is RefSearchOutcome.Found -> WatchRefSearchView(
                status = WatchRefSearchStatus.OK,
                candidates = outcome.candidates.map {
                    WatchRefCandidateView(
                        ref = it.ref ?: it.refKey!!,
                        refKey = it.refKey!!,
                        brand = it.brand,
                        modelTitle = it.modelTitle,
                        officialPriceKrw = it.officialPriceKrw,
                    )
                },
            )
            is RefSearchOutcome.Unavailable -> WatchRefSearchView(status = WatchRefSearchStatus.UNAVAILABLE)
        }
        return ResponseEntity.ok(view)
    }

    companion object {
        /** 상류 `RefSearchService.MIN_QUERY_LENGTH`와 같다 */
        const val MIN_QUERY_LENGTH = 2
        /** 상류 `RefSearchService.MAX_QUERY_LENGTH`와 같다 — 넘기면 상류가 400 */
        const val MAX_QUERY_LENGTH = 64
        const val DEFAULT_SIZE = 20
        const val MAX_SIZE = 50
    }
}

enum class WatchRefSearchStatus {
    /** 상류가 답했다. 후보가 0건일 수 있다 */
    OK,

    /** 상류 실패·타임아웃·배포 전(404). 화면은 ref 직접 확인으로 안내한다 */
    UNAVAILABLE,
}

data class WatchRefSearchView(
    val status: WatchRefSearchStatus,
    val candidates: List<WatchRefCandidateView> = emptyList(),
)

data class WatchRefCandidateView(
    /** 표시용 원문 ref */
    val ref: String,
    /** 🔴 확인 단계(`/refs/lookup?ref=`)에 **그대로** 넘길 키 */
    val refKey: String,
    val brand: String?,
    val modelTitle: String?,
    val officialPriceKrw: Long?,
)
