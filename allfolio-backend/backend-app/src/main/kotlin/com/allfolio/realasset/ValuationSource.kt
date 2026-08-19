package com.allfolio.realasset

import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.domain.asset.ConfidenceLevel
import java.math.BigDecimal
import java.time.LocalDate

/**
 * 체결가(TRADE)와 호가(ASK)는 다른 숫자다. 실물자산은 스프레드가 커서 둘을 섞으면
 * 손익이 왜곡된다 — 금은 TRADE, 시계(chrono24·중고 카페)는 ASK다.
 *
 * **아직 저장하지 않는다.** `ua_assets`에는 이 개념이 없고 v1은 전부 TRADE라 넣을 자리가 없다.
 * 그래도 [Valuation]에 두는 이유는 시계(ASK)가 오는 순간 컬럼을 만들어야 한다는 사실을
 * 타입이 기억하게 하려는 것이다 — 그때 어댑터를 고치지 않아도 된다.
 */
enum class PriceBasis { TRADE, ASK }

data class Valuation(
    val unitPrice: BigDecimal,
    /**
     * 평가 총액(KRW). **원 단위 정수로 반올림해서 들어온다** — `ua_assets.current_value`는
     * `NUMERIC(30,10)`이라 소수를 담을 수 있지만, 원화에 소수점을 두지 않는 것이 이 저장소의
     * 규칙이다(AF-104가 자릿수를 흘린 뒤로). 반올림은 어댑터 한 곳에서만 한다.
     */
    val valuationKrw: BigDecimal,
    /**
     * **필수다.** 시세 기준일은 평가일과 다르다 — 금은 D+1 공표라 평일에도 최소 하루 낡았고
     * 연휴 뒤엔 4일까지 벌어진다. `last_updated_at`("우리가 쓴 시각")과 혼동하지 말 것.
     */
    val priceAsOf: LocalDate,
    val priceBasis: PriceBasis,
    val confidence: ConfidenceLevel,
)

/**
 * 자산 한 종류의 평가 방법.
 *
 * **평가 로직을 자산별 분기문이 아니라 어댑터로 분리한다** — 금으로 파이프라인을 완성하고
 * 시계·부동산은 이 인터페이스의 구현만 갈아끼운다.
 *
 * **`ua_assets`의 [Asset]을 그대로 받는다.** 초안은 전용 표(`real_asset`)와 전용 도메인을
 * 두려 했지만, 제품에는 이미 `AssetType.GOLD`가 있고 대시보드·배분 차트·리포트가 전부 그걸
 * 쓴다. 표를 하나 더 만들면 사용자가 금을 넣는 곳이 두 곳이 되고 한쪽만 순자산에 잡힌다.
 * 여기서 하는 일은 **이미 있는 자산의 `current_value`를 신선하게 유지하는 것**이다.
 *
 * **반환이 nullable인 게 요점이다.** 시세 없음·단위 해석 불가·표본 부족은 전부 "지금은
 * 산출 불가"이지 0원이 아니다. 억지로 숫자를 만들지 않고 호출부가 **직전 값을 그대로 둔다**.
 */
interface ValuationSource {
    fun supports(assetType: AssetType): Boolean

    /** 산출 불가능하면 null. 예외로 알리지 않는다 — 자산 하나가 배치 전체를 죽이면 안 된다 */
    fun valuate(asset: Asset, asOf: LocalDate): Valuation?
}
