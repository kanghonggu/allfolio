package com.allfolio.api.market

import com.allfolio.market.realestate.ComplexSearchService
import com.allfolio.market.realestate.ComplexView
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * 단지·평형 검색 (R2).
 *
 * **어드민이 아니라 일반 사용자 경로다** — 자산 등록 화면이 부른다.
 * 실거래가는 공개 데이터라 사용자별 격리가 필요 없다.
 *
 * ## 왜 시군구가 필수인가
 *
 * 전국을 훑으면 "래미안"에 수백 개가 걸린다. 사용자는 자기 집이 어느 구에 있는지 알고,
 * 그 정보가 검색을 쓸 만하게 만드는 유일한 축이다.
 */
@RestController
@RequestMapping("/api/real-estate")
class RealEstateComplexController(
    private val searchService: ComplexSearchService,
) {
    /**
     * GET /api/real-estate/complexes?sgg=11680&q=래미안
     *
     * **거래가 없었던 단지는 안 나온다.** 국토부 API에 "단지 목록"이라는 것이 없어서
     * 우리가 받아 둔 거래에서 역으로 뽑기 때문이다. 그게 맞는 동작이기도 하다 —
     * 실거래가 없으면 자동 평가도 못 한다. 화면이 그렇게 말해야 한다.
     */
    @GetMapping("/complexes")
    fun complexes(
        @RequestParam sgg: String,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) limit: Int?,
    ): ResponseEntity<List<ComplexView>> {
        // 5자리가 아니면 캐시에 매칭되는 행이 없어 조용히 빈 목록이 된다.
        // 오류가 아니라 빈 결과라 화면에서 "그 단지가 없다"로 읽힌다 — 먼저 막는다.
        if (!sgg.matches(Regex("\\d{5}"))) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST, "법정동 코드는 숫자 5자리여야 합니다: $sgg",
            )
        }
        val size = (limit ?: ComplexSearchService.DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        return ResponseEntity.ok(searchService.search(sgg, q, size))
    }

    private companion object {
        /** 선택 목록의 상한. 이보다 많으면 사용자가 고르지 못한다 — 검색어를 좁혀야 한다 */
        const val MAX_LIMIT = 50
    }
}
