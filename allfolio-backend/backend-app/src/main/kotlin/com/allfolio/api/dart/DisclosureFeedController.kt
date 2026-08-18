package com.allfolio.api.dart

import com.allfolio.dart.query.DisclosureFeed
import com.allfolio.dart.query.DisclosureFeedService
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 사용자 보유종목의 공시 피드 (D1 Task 14).
 *
 * **인증은 `@AuthenticationPrincipal Jwt`가 아니라 `X-User-Id` 헤더다.** 이 레포는
 * `JwtUserIdFilter`가 `Authorization: Bearer` 토큰의 `sub` 클레임을 검증해 `X-User-Id`
 * 헤더로 주입하고, `SecurityConfig`가 `.anyRequest().authenticated()`로 이 경로를 막는다
 * ([PortfolioQueryController]와 같은 관례). `SecurityConfig`에 `/api/disclosures`용 규칙을
 * 별도로 추가하지 않았다 — `permitAll` 화이트리스트에 없으므로 기본값(`authenticated()`)이
 * 이미 이 경로를 인증 대상으로 잡는다.
 */
@RestController
@RequestMapping("/api/disclosures")
class DisclosureFeedController(private val service: DisclosureFeedService) {

    /**
     * 기본 조회 구간은 최근 30일이다. 날짜는 KST 기준으로 정한다 — 컨테이너가 UTC라
     * UTC 자정~09시 사이 요청이 "어제"를 기준으로 잡히는 것을 막는다(KST Timezone Shift와
     * 같은 부류의 함정).
     */
    @GetMapping
    fun feed(
        @RequestHeader("X-User-Id") userId: UUID,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
    ): ResponseEntity<DisclosureFeed> {
        val since = from ?: LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(30)
        return ResponseEntity.ok(service.feedFor(userId, since))
    }
}
