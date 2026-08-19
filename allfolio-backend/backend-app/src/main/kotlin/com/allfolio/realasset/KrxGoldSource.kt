package com.allfolio.realasset

import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.domain.asset.ConfidenceLevel
import org.springframework.stereotype.Component
import java.math.RoundingMode
import java.time.LocalDate

/**
 * 금(KRX 금현물) 평가. `평가액 = 시세(원/g) x 중량(g)`.
 *
 * 시세는 AF-108이 이미 모아 둔 `market_commodity_quote`(`code='GOLD_KRX'`, 단위 `KRW/g`)에서
 * 온다 — 이 클래스는 수집을 하지 않는다.
 *
 * **평가 대상은 `ua_assets`의 `type='GOLD'` 행이다.** 사용자가 기존 자산 등록 폼으로 넣은
 * 바로 그 금이고, 지금은 `현재 총 가치`를 손으로 입력해야 한다(`USER_INPUT`/`LOW`).
 * 이 어댑터가 그걸 `MARKET_PRICE`/`HIGH`로 바꾼다.
 *
 * **순도를 곱하지 않는다.** `ua_assets`에 순도 컬럼이 없고, v1 범위가 순금(24K)이다.
 * 18K를 받는 날 컬럼을 만들고 여기서 곱한다 — 지금 `sub_type` 같은 자유 문자열에서
 * 순도를 추측해 곱하면, 틀렸을 때 손익이 조용히 25% 어긋난다.
 *
 * **`confidence`가 항상 HIGH인 것은 근거가 있다.** 금은 거래소 체결가(TRADE)라 표본이나
 * 스프레드를 따질 것이 없다 — 값이 있으면 그 값이 맞다. 등급이 갈리는 것은 호가에서
 * 중앙값을 내는 시계(v2)부터다. 여기에 MEDIUM/LOW 분기를 만들지 말 것.
 *
 * **`price_as_of`가 평가일보다 앞서는 것은 정상이다.** 공공데이터포털 금 시세는 D+1 공표라
 * 평일에도 최소 하루 낡았고, 연휴 뒤에는 4일까지 벌어진다(2026-08-18 실측: 78일 중
 * 1일 68% · 2일 15% · 3일 14% · 4일 3%). "오래됐다"고 판정하는 임계치는 5 이상이다.
 */
@Component
class KrxGoldSource(
    private val lookup: CommodityQuoteLookup,
) : ValuationSource {

    override fun supports(assetType: AssetType): Boolean = assetType == AssetType.GOLD

    override fun valuate(asset: Asset, asOf: LocalDate): Valuation? {
        // 단위를 해석할 수 없으면 계산하지 않는다. 기본값 g을 주면 돈으로 입력한 금이
        // 3.75배로 평가된다 — GoldWeight의 KDoc 참조.
        val grams = GoldWeight.toGrams(asset.quantity, asset.symbol) ?: return null

        val quote = lookup.latestAsOf(GOLD_CODE, asOf) ?: return null
        if (quote.unit != EXPECTED_UNIT) return null

        return Valuation(
            unitPrice = quote.price,
            // 원 단위 반올림을 여기 한 곳에서만 한다. 저장 컬럼이 NUMERIC(30,10)이라
            // 소수를 담을 수 있지만, 원화에 소수점을 두지 않는 것이 이 저장소의 규칙이다.
            valuationKrw = quote.price.multiply(grams).setScale(0, RoundingMode.HALF_UP),
            priceAsOf = quote.tradeDate,
            priceBasis = PriceBasis.TRADE,
            confidence = ConfidenceLevel.HIGH,
        )
    }

    private companion object {
        /**
         * `market_commodity_quote`의 금 코드. `application.yml`의 `market-commodity.fsc[0].code`와
         * 같은 값이어야 한다 — 설정에서 읽지 않는 이유는 그 목록이 **수집 대상**이지
         * 평가가 참조할 코드가 아니기 때문이다(둘을 묶으면 수집 목록을 손대는 순간 평가가 갈린다).
         */
        const val GOLD_CODE = "GOLD_KRX"

        /**
         * 중량을 g으로 환산해 곱하므로 시세도 원/g이어야 한다. AF-108은 단위를 코드 상수가
         * 아니라 **행에 저장**한다 — 소스가 단위를 바꾼 날 저장은 멀쩡한데 화면만 조용히
         * 틀리는 것을 막으려는 설계다. 여기서 가정해 버리면 그 방어가 무효가 된다.
         */
        const val EXPECTED_UNIT = "KRW/g"
    }
}
