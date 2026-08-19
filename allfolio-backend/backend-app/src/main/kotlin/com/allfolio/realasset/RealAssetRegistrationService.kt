package com.allfolio.realasset

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 사용자가 보내는 등록 정보. **`sourceRef`·`includeInTwr`·`userId`가 여기 없는 것이 요점이다** */
data class RealAssetRegistration(
    val assetType: AssetType,
    val subType: String?,
    val name: String,
    val quantity: BigDecimal,
    val purity: BigDecimal?,
    val acquiredAt: LocalDate,
    val acquiredCostKrw: Long,
)

/** 저장 직전의 자산. 서버가 정한 필드까지 다 채워져 있다 */
data class NewRealAsset(
    val id: UUID,
    val userId: UUID,
    val assetType: AssetType,
    val subType: String?,
    val name: String,
    val sourceRef: String,
    val quantity: BigDecimal,
    val purity: BigDecimal,
    val acquiredAt: LocalDate,
    val acquiredCostKrw: Long,
    val includeInTwr: Boolean,
    val isActive: Boolean,
    val createdAt: Instant,
)

/**
 * 실물자산 등록 (A1 · G6).
 *
 * **사용자가 정하지 못하는 것이 셋이다 — 요청 타입에 그 자리를 아예 안 만들었다.**
 *
 *  1. **`sourceRef`(시세 조인 키).** 받으면 사용자가 `WTI`를 넣어 원유 배럴값을 금 g수에 곱한
 *     평가액을 만들 수 있다. 오류도 로그도 안 남는다 — 평가 어댑터는 시키는 코드를 조회할 뿐이다.
 *     단위 가드(`KRW/g`)가 대부분을 걸러 주지만 그건 우연이지 설계가 아니고, 같은 단위를 쓰는
 *     코드가 하나라도 늘면 뚫린다. v1은 금만 받으므로 코드가 하나로 정해져 있다.
 *  2. **`includeInTwr`.** 계단식 평가 자산이 금융자산 TWR에 섞이면 수익률 자체가 오염된다.
 *     그 판단은 자산 유형이 지지 사용자가 지지 않는다.
 *  3. **`userId`.** 인증 헤더에서만 온다. 본문에 자리를 만들면 그 순간 IDOR이 된다 —
 *     이 저장소는 이체·환전에서 같은 실수를 한 번 겪었다.
 *
 * **오늘 날짜를 호출자가 넘긴다.** 여기서 `LocalDate.now()`를 부르면 컨테이너가 UTC라
 * 한국 사용자가 오늘 산 금을 저녁에 등록할 때 "미래 취득일"로 걸린다.
 */
@Service
class RealAssetRegistrationService(
    private val store: Store,
) {
    interface Store {
        fun insert(asset: NewRealAsset)
    }

    fun register(
        userId: UUID,
        request: RealAssetRegistration,
        today: LocalDate,
        now: Instant,
    ): UUID {
        // v1은 금만이다. 시계·부동산을 받아 두면 맡을 어댑터가 없어 **영원히 평가되지 않는
        // 자산**이 쌓인다. 배치는 그걸 skipped로 남기지만, 사용자에게는 등록에 성공한 것으로
        // 보이고 평가액만 계속 비어 있다 — 등록 시점에 거절하는 편이 정직하다.
        if (request.assetType != AssetType.GOLD) {
            throw badRequest("아직 금(KRX 금현물)만 등록할 수 있습니다 (요청: ${request.assetType})")
        }
        if (request.name.isBlank()) {
            throw badRequest("자산 이름을 입력해주세요")
        }
        if (request.quantity <= BigDecimal.ZERO) {
            throw badRequest("수량은 0보다 커야 합니다 (요청: ${request.quantity})")
        }
        val purity = request.purity ?: BigDecimal.ONE
        // 1을 넘으면 24K보다 순수한 금이 되어 평가액이 부풀고, 0 이하면 0이나 음수가 된다.
        if (purity <= BigDecimal.ZERO || purity > BigDecimal.ONE) {
            throw badRequest("순도는 0보다 크고 1 이하여야 합니다 (요청: $purity)")
        }
        if (request.acquiredAt.isAfter(today)) {
            throw badRequest("취득일이 미래일 수 없습니다 (요청: ${request.acquiredAt}, 오늘: $today)")
        }
        if (request.acquiredCostKrw < 0) {
            throw badRequest("취득가는 0 이상이어야 합니다 (요청: ${request.acquiredCostKrw})")
        }

        val id = UUID.randomUUID()
        store.insert(
            NewRealAsset(
                id = id,
                userId = userId,
                assetType = request.assetType,
                subType = request.subType,
                name = request.name.trim(),
                sourceRef = GOLD_SOURCE_REF,
                quantity = request.quantity,
                purity = purity,
                acquiredAt = request.acquiredAt,
                acquiredCostKrw = request.acquiredCostKrw,
                includeInTwr = true, // 금은 연속(영업일)이라 TWR에 포함한다
                isActive = true,
                createdAt = now,
            ),
        )
        return id
    }

    private fun badRequest(reason: String) = ResponseStatusException(HttpStatus.BAD_REQUEST, reason)

    private companion object {
        /**
         * `market_commodity_quote`의 금 코드. `application.yml`의 `market-commodity.fsc[0].code`와
         * 같은 값이어야 한다 — 설정에서 읽지 않는 이유는 그 목록이 **수집 대상**이지
         * 평가가 참조할 코드 목록이 아니기 때문이다(둘을 묶으면 수집 목록을 손대는 순간
         * 이미 등록된 자산의 조인 키가 갈린다).
         */
        const val GOLD_SOURCE_REF = "GOLD_KRX"
    }
}
