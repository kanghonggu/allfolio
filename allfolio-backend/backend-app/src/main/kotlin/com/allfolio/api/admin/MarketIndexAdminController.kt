package com.allfolio.api.admin

import com.allfolio.market.index.KisIndexClient
import com.allfolio.market.index.KisIndexException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("/api/admin/market-index")
class MarketIndexAdminController(
    private val kisIndexClient: KisIndexClient,
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
}
