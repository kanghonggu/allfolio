package com.allfolio.realasset

import java.math.RoundingMode
import java.time.LocalDate

/**
 * 금(KRX 금현물) 평가. `평가액 = 시세 x 수량 x 순도`.
 *
 * 시세는 AF-108이 이미 모아 둔 `market_commodity_quote`(`code='GOLD_KRX'`, 단위 `KRW/g`)에서
 * 온다 — 이 클래스는 수집을 하지 않는다. 설계 문서 초안이 말하는 `krx_gold_price`는
 * 존재하지 않는 표다(원자재 17종을 한 표로 묶으면서 금도 거기 들어갔다).
 *
 * **`confidence`가 항상 HIGH인 것은 근거가 있다.** 금은 거래소 체결가(TRADE)라 표본이나
 * 스프레드를 따질 것이 없다 — 값이 있으면 그 값이 맞다. 등급이 갈리는 것은 호가에서
 * 중앙값을 내는 시계(v2)부터다. 여기에 MEDIUM/LOW 분기를 만들지 말 것.
 *
 * **`price_as_of`가 평가일보다 앞서는 것은 정상이다.** 공공데이터포털 금 시세는 D+1 공표라
 * 평일에도 최소 하루 낡았고, 연휴 뒤에는 4일까지 벌어진다(2026-08-18 실측: 78일 중
 * 1일 68% · 2일 15% · 3일 14% · 4일 3%). 이 값을 "오래됐다"고 판정하는 임계치는 5 이상이다 —
 * 그보다 낮게 잡으면 정상 운영이 매일 경보로 나온다.
 */
class KrxGoldSource(
    private val lookup: CommodityQuoteLookup,
) : ValuationSource {

    override fun supports(assetType: AssetType): Boolean = assetType == AssetType.GOLD

    override fun valuate(asset: RealAsset, asOf: LocalDate): Valuation? {
        val code = asset.sourceRef ?: return null
        val quote = lookup.latestAsOf(code, asOf) ?: return null
        if (quote.unit != EXPECTED_UNIT) return null

        val krw = quote.price.multiply(asset.quantity).multiply(asset.purity)

        return Valuation(
            unitPrice = quote.price,
            valuationKrw = krw.setScale(0, RoundingMode.HALF_UP).toLong(),
            priceAsOf = quote.tradeDate,
            priceUnit = quote.unit,
            priceBasis = PriceBasis.TRADE,
            confidence = Confidence.HIGH,
        )
    }

    private companion object {
        /**
         * `quantity`가 g 단위라는 전제와 짝을 이룬다. 소스가 단위를 바꿔 오면
         * 곱셈은 여전히 성립하지만 답이 틀린다 — 그래서 값을 안 내고 만다.
         */
        const val EXPECTED_UNIT = "KRW/g"
    }
}
