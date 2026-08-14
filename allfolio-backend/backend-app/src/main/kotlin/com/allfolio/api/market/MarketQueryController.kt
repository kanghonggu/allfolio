package com.allfolio.api.market

import com.allfolio.market.query.MarketQueryService
import com.allfolio.market.query.MarketSnapshot
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * GET /api/market — 시장 화면 데이터 (AF-104).
 *
 * **인증 규칙을 따로 두지 않는다.** `SecurityConfig`의 `.anyRequest().authenticated()`가 이미 잡는다.
 * 여기에 명시적 matcher를 더하면 넓히는 실수만 생긴다 — 로그인한 사용자만 보는 것이 기본값이고,
 * 재배포(AF-108) 관점에서도 공개보다 안전하다.
 *
 * **네 탭을 한 번에 돌려준다.** 탭마다 따로 부르면 전환마다 스피너가 돈다 —
 * 합쳐도 78행이라 나눌 이유가 없다.
 *
 * **사용자 식별자를 받지 않는다.** 시장 데이터에는 사용자별 부분이 없다.
 * 화면의 "내 통화" 카드는 프런트가 이미 받아 둔 계좌 데이터와 합쳐 만든다.
 */
@RestController
@RequestMapping("/api/market")
class MarketQueryController(
    private val marketQueryService: MarketQueryService,
) {
    @GetMapping
    fun market(): ResponseEntity<MarketSnapshot> = ResponseEntity.ok(marketQueryService.snapshot())
}
