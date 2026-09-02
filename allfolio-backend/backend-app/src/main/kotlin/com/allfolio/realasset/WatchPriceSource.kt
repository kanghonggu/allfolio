package com.allfolio.realasset

import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.domain.asset.ConfidenceLevel
import com.allfolio.unifiedasset.infrastructure.jpa.WatchValuationCacheJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate

/**
 * 시계 평가 — watchpricedata 호가 중앙값 (A1 v2 · W5).
 *
 * ## 로컬 캐시만 읽는다
 *
 * **사용자 요청 시점에 watchpricedata를 부르지 않는다**(설계 7절). 일 1회 배치가
 * `watch_valuation_cache`에 복제해 두고 이 어댑터는 그것만 본다. 외부 서비스의 응답 지연이
 * 우리 평가 배치에 그대로 실리는 구조를 만들지 않는다 — `RtmsSource`가 `rtms_deals_cache`를
 * 읽는 것과 같은 자리다.
 *
 * ## 🔴 호가지 체결가가 아니다
 *
 * chrono24 매물은 미국·UAE·호주·스웨덴에 있고 관세·배송이 안 붙은 값이다. 네이버 카페는
 * 개인 판매 희망가다. **한국 사용자가 그 값에 팔 수 있는 것이 아니다.** 그래서
 * [PriceBasis.ASK]이고, 화면이 "호가 중앙값"이라고 말해야 한다(설계 7절 라벨 예시).
 *
 * ## 표본 판정을 다시 하지 않는다
 *
 * `표본 3건 미만 → null`은 W4가 **서버에서** 판정한다. 여기서 다시 세면 임계치가 두 곳에
 * 생기고 한쪽만 바뀐다. 캐시에 행이 있다는 것 자체가 "서버가 산출했다"는 뜻이다.
 *
 * ## 신뢰도도 서버 값을 쓴다
 *
 * W4.5가 표본 중복 제거 + 퍼짐(사분위) 기반으로 판정한 값이다. **퍼짐을 max/min으로 재면
 * 안 된다**는 것이 그 작업의 실측 결론이었다 — 우리가 여기서 다시 계산하면 그 판단을
 * 되돌리는 셈이다. 해석만 우리 enum으로 옮긴다.
 */
@Component
class WatchPriceSource(
    private val cache: WatchValuationCacheJpaRepository,
) : ValuationSource {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun supports(assetType: AssetType) = assetType == AssetType.WATCH

    override fun valuate(asset: Asset, asOf: LocalDate): Valuation? {
        // ref가 없으면 매칭이 성립하지 않는다. 선택 UI(W6)를 거치지 않고 손으로 등록한
        // 자산이다 — `RtmsSource`가 단지일련번호 없는 자산을 거르는 것과 같다.
        val refKey = asset.symbol?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        val row = cache.findFirstByRefKeyAndAsOfLessThanEqualOrderByAsOfDesc(refKey, asOf)
        if (row == null) {
            log.debug("[시계] ref={} 캐시에 없음 — 산출하지 않음", refKey)
            return null
        }

        // **수량을 곱하지 않는다.** 시계는 개별 자산이고 `Asset`이 ILLIQUID라
        // `totalPurchaseCost()`도 수량을 곱하지 않는다. 같은 ref를 두 개 가진 사용자는
        // 자산을 두 건 등록한다 — 개체마다 상태·풀세트 여부가 달라 값이 갈리기 때문이다.
        val median = BigDecimal(row.medianKrw)

        return Valuation(
            unitPrice = median,
            valuationKrw = median,
            // 🔴 관측일이 아니라 30일 창의 끝이다. 이 값이 그대로 화면의 "기준일"이 되므로
            // 라벨이 "호가 중앙값"이라는 성격을 함께 말해야 한다(설계 7절).
            priceAsOf = row.asOf,
            priceBasis = PriceBasis.ASK,
            confidence = confidenceOf(row.confidence),
        )
    }

    /**
     * 서버 판정을 우리 enum으로 옮긴다.
     *
     * **HIGH를 그대로 통과시키지 않는다.** 호가는 체결가가 아니고 해외 매물이 섞여 있어,
     * 표본이 아무리 두꺼워도 "그 값에 팔린다"는 뜻이 아니다 — `RtmsSource`가 체결가인데도
     * MEDIUM을 상한으로 둔 것과 같은 이유이고, 시계는 그보다 한 단계 더 멀다.
     * 모르는 값이 오면 LOW로 내린다 — 낙관 쪽으로 틀리지 않는다.
     */
    private fun confidenceOf(serverValue: String?): ConfidenceLevel =
        when (serverValue?.uppercase()) {
            "HIGH", "MEDIUM" -> ConfidenceLevel.MEDIUM
            else -> ConfidenceLevel.LOW
        }
}
