package com.allfolio.realasset

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class RealAssetCreateRequest(
    val assetType: AssetType,
    val subType: String? = null,
    val name: String,
    val quantity: BigDecimal,
    /** 생략하면 24K(1.0) */
    val purity: BigDecimal? = null,
    val acquiredAt: LocalDate,
    val acquiredCostKrw: Long,
)

data class RealAssetCreateResponse(val id: UUID)

/**
 * 실물자산 등록·조회 (A1 · G6·G7).
 *
 * 로그인 필수 — `SecurityConfig`의 `anyRequest().authenticated()`가 잡는다.
 * 여기에 명시적 matcher를 더하지 않는 이유는 `MarketQueryController`와 같다: 넓히는 실수만 생긴다.
 *
 * **사용자를 본문이 아니라 헤더에서 받는다.** `X-User-Id`는 `JwtUserIdFilter`가 JWT의 sub에서
 * 넣어 주는 값이라 클라이언트가 위조할 수 없다(필터가 요청 헤더를 덮어쓴다).
 * 본문에 사용자 자리를 만들면 그 순간 IDOR이 된다 — 이 저장소는 이체·환전에서 한 번 겪었다.
 *
 * **오늘 날짜를 여기서 정해 서비스에 넘긴다.** 서비스가 `LocalDate.now()`를 부르면 컨테이너가
 * UTC라 한국 사용자가 오늘 산 금을 저녁에 등록할 때 "미래 취득일"로 걸린다.
 */
@RestController
@RequestMapping("/api/real-assets")
class RealAssetController(
    private val registration: RealAssetRegistrationService,
    private val query: RealAssetQueryService,
) {
    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @RequestHeader("X-User-Id") userId: UUID,
        @RequestBody request: RealAssetCreateRequest,
    ): RealAssetCreateResponse = RealAssetCreateResponse(
        registration.register(
            userId = userId,
            request = RealAssetRegistration(
                assetType = request.assetType,
                subType = request.subType,
                name = request.name,
                quantity = request.quantity,
                purity = request.purity,
                acquiredAt = request.acquiredAt,
                acquiredCostKrw = request.acquiredCostKrw,
            ),
            today = LocalDate.now(KST),
            now = Instant.now(),
        ),
    )

    /**
     * GET /api/real-assets — 내 실물자산 목록 (평가액·손익·기준일).
     *
     * **평가가 없는 자산도 나온다.** 등록 당일에는 스냅샷이 없는데 목록에서 빠지면
     * 사용자는 등록이 실패한 줄 안다. 평가 칸만 `null`이고 자산은 보인다.
     */
    @GetMapping
    fun list(@RequestHeader("X-User-Id") userId: UUID): List<RealAssetView> = query.findByUser(userId)
}
