package com.allfolio.realasset

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

enum class AssetType { GOLD, WATCH, REAL_ESTATE }

/**
 * 체결가(TRADE)와 호가(ASK)는 다른 숫자다. 실물자산은 스프레드가 커서 둘을 섞으면
 * 손익이 왜곡된다 — 금은 TRADE, 시계(chrono24·중고 카페)는 ASK다.
 */
enum class PriceBasis { TRADE, ASK }

enum class Confidence { HIGH, MEDIUM, LOW }

/** 평가에 필요한 만큼의 보유 자산. 등록·수정 필드는 G6이 붙인다 */
data class RealAsset(
    val id: UUID,
    val assetType: AssetType,
    /** 시세 조인 키. 금=시세 코드('GOLD_KRX') · 시계=ref · 부동산=단지코드+면적 */
    val sourceRef: String?,
    /** 금은 g. 3.75g = 1돈이라 소수 필수 */
    val quantity: BigDecimal,
    /** 24K=1.0 · 18K=0.75 */
    val purity: BigDecimal,
)

data class Valuation(
    val unitPrice: BigDecimal,
    /**
     * **Long인 것은 의도다.** 설계 문서는 `BigDecimal`로 적었지만 `real_asset_valuation.valuation_krw`는
     * `BIGINT`라, BigDecimal로 들고 다니면 "원 단위로 반올림한다"는 규칙이 저장하는 쪽마다 한 벌씩
     * 생긴다. 한쪽만 고쳐지는 순간 같은 자산의 평가액이 화면과 DB에서 갈린다.
     * 반올림을 여기서 한 번만 하고, 타입으로 소수를 못 흘려보내게 한다.
     */
    val valuationKrw: Long,
    /** **필수다.** 폴백 때 평가일과 다르다 — 이걸 안 채우면 과거 화면을 재현할 수 없다 */
    val priceAsOf: LocalDate,
    /** 적용한 시세의 단위. 소스가 단위를 바꾼 날을 스냅샷 경계 너머로 추적하려고 남긴다 */
    val priceUnit: String,
    val priceBasis: PriceBasis,
    val confidence: Confidence,
)

/**
 * 자산 한 종류의 평가 방법.
 *
 * **평가 로직을 자산별 분기문이 아니라 어댑터로 분리한다** — 실물자산 확장의 핵심이다.
 * 금으로 파이프라인을 완성하고, 시계·부동산은 이 인터페이스의 구현만 갈아끼운다.
 *
 * **반환이 nullable인 게 요점이다.** 시계 표본 3건 미만, 부동산 최근 거래 없음, 금 시세 없음 —
 * 전부 "지금은 산출 불가"이지 0원이 아니다. 억지로 숫자를 만들지 않고 호출부(G5)가
 * 직전 유효 스냅샷 유지로 처리한다.
 *
 * `priceAsOf`·`priceBasis`를 [Valuation]의 필수 필드로 둔 것도 같은 의도다 —
 * 어댑터가 이걸 안 채우면 컴파일이 안 된다. UI 기준일 표시 누락을 타입으로 막는다.
 */
interface ValuationSource {
    fun supports(assetType: AssetType): Boolean

    /** 산출 불가능하면 null. 예외로 알리지 않는다 — 자산 하나가 배치 전체를 죽이면 안 된다 */
    fun valuate(asset: RealAsset, asOf: LocalDate): Valuation?
}
