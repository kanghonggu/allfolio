package com.allfolio.realasset

import com.allfolio.unifiedasset.domain.asset.Asset
import com.allfolio.unifiedasset.domain.asset.AssetType
import com.allfolio.unifiedasset.domain.asset.ConfidenceLevel
import com.allfolio.unifiedasset.infrastructure.jpa.RtmsDealCacheJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate

/**
 * 부동산 평가 — 국토부 실거래가 (A1 v3 · R3).
 *
 * ## 🔴 부동산은 추세가 있다 — 시계와 다른 점이 이것이다
 *
 * 실측(2026-08-23, 강남·분당 12개월 7,908건, 조합 1,732개):
 *
 *     12개월 내 값 이동(후반 6개월 중앙값 ÷ 전반 6개월 − 1)
 *     p10 = -5.8% · p50 = +7.7% · p90 = +23.2% · |이동| 10% 초과가 46%
 *
 * **12개월치를 통째로 중앙값 내면 절반의 단지에서 시세가 뒤처진다.** 그렇다고 창을 줄이면
 * 표본이 무너진다 — `n>=3`인 조합이 12개월 49% → 6개월 32%로 떨어진다.
 *
 * 그래서 **기간이 아니라 최근 [MAX_SAMPLE]건으로 자른다.** 거래가 많은 단지는 최신만 보고,
 * 적은 단지는 [WINDOW_MONTHS]개월까지 거슬러 표본을 확보한다. 한 규칙으로 둘을 만족한다.
 *
 * ## 표본이 얇다
 *
 *     (단지, 면적) 조합당 12개월 표본: 중앙 2건
 *     n>=1 100% · n>=3 48.9% · n>=5 27.7% · n>=10 9.4%
 *
 * **절반은 산출이 안 된다.** 그게 정상 경로다 — 억지로 숫자를 만들지 않는다.
 *
 * ## 해제 거래를 뺀다
 *
 * 성사되지 않은 가격이다. 실측 2.6%.
 *
 * ## 왜 HIGH가 없나
 *
 * 실거래가는 **체결가**라 금과 같은 급으로 볼 여지가 있지만, 부동산은 **개별성이 크다** —
 * 같은 단지 같은 평형도 층·향·리모델링·조망에 따라 갈린다. 중앙값이 그 집의 값이라고
 * 말할 수 없다. 그래서 MEDIUM이 최대다.
 */
@Component
class RtmsSource(
    private val deals: RtmsDealCacheJpaRepository,
) : ValuationSource {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun supports(assetType: AssetType) = assetType == AssetType.REAL_ESTATE

    override fun valuate(asset: Asset, asOf: LocalDate): Valuation? {
        // 단지일련번호와 전용면적이 둘 다 있어야 매칭이 성립한다. 하나라도 없으면
        // 사용자가 선택 UI를 거치지 않고 손으로 등록한 자산이다 — 평가 대상이 아니다.
        val aptSeq = asset.symbol?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val area = asset.exclusiveAreaM2 ?: return null

        val from = asOf.minusMonths(WINDOW_MONTHS)
        val rows = deals.findByAptSeqAndExclusiveAreaM2AndDealDateBetween(aptSeq, area, from, asOf)
            .filter { !it.isCancelled }
            // 최근 것부터. **기간이 아니라 개수로 자르는 것이 이 어댑터의 핵심**이다
            .sortedByDescending { it.dealDate }
            .take(MAX_SAMPLE)

        if (rows.size < MIN_SAMPLE) {
            log.debug("[부동산] {} {}㎡ 표본 {}건 — {}건 미만이라 산출하지 않음",
                aptSeq, area, rows.size, MIN_SAMPLE)
            return null
        }

        val prices = rows.map { it.dealAmountKrw }.sorted()
        val median = median(prices)
        // 가장 최근 거래일이 곧 시세 기준일이다. **평가일이 아니다** — 부동산은 며칠씩
        // 거래가 없는 게 정상이라 둘이 몇 달 벌어질 수 있고, 화면이 그걸 말해야 한다.
        val priceAsOf = rows.maxOf { it.dealDate }

        return Valuation(
            // 단가 개념이 없다 — 평가 총액이 곧 그 집 값이다. 수량은 항상 1이고
            // `Asset.totalPurchaseCost()`가 ILLIQUID라 수량을 곱하지 않는다.
            unitPrice = BigDecimal(median),
            valuationKrw = BigDecimal(median),
            priceAsOf = priceAsOf,
            priceBasis = PriceBasis.TRADE,
            confidence = confidenceOf(rows.size),
        )
    }

    /**
     * 표본이 [MEDIUM_SAMPLE]건 이상이면 MEDIUM, 아니면 LOW.
     *
     * **HIGH가 없는 이유는 클래스 KDoc에 있다** — 체결가여도 부동산은 개별성이 크다.
     *
     * 경계가 5인 근거: 12개월 표본 분포에서 `n>=5`가 상위 27.7%다. 3~4건은 산출은 하되
     * 그 값을 강하게 믿으라고 말하지 않는다.
     */
    private fun confidenceOf(sampleSize: Int): ConfidenceLevel =
        if (sampleSize >= MEDIUM_SAMPLE) ConfidenceLevel.MEDIUM else ConfidenceLevel.LOW

    /** R-7이 아니라 단순 중앙값이다 — 표본이 한 자릿수라 보간이 뜻을 갖지 못한다 */
    private fun median(sorted: List<Long>): Long {
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2]
        else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }

    companion object {
        /**
         * 거슬러 올라갈 상한(개월).
         *
         * 12인 이유: 이보다 짧으면 표본이 무너진다(`n>=3`인 조합이 12개월 49% → 6개월 32%).
         * 이보다 길면 **추세 때문에 값이 뒤처진다** — 12개월 안에서도 절반 가까이가 10% 넘게
         * 움직인다. 아래 [MAX_SAMPLE]이 그 안에서 최신을 고르는 역할을 한다.
         */
        const val WINDOW_MONTHS = 12L

        /**
         * 쓸 최근 거래 수.
         *
         * **기간이 아니라 개수로 자르는 것이 이 어댑터의 설계다.** 거래가 많은 단지에서
         * 12개월치를 다 쓰면 오래된 가격이 중앙값을 끌어내린다(실측 p50 +7.7% 상승 추세).
         * 5건이면 거래가 활발한 단지는 대개 최근 몇 달 안에서 끝난다.
         */
        const val MAX_SAMPLE = 5

        /** 이보다 적으면 산출하지 않는다. 실측 12개월 표본의 중앙이 2건이라 절반은 여기 걸린다 */
        const val MIN_SAMPLE = 3

        /** 이 이상이면 MEDIUM. 12개월 표본에서 상위 27.7%다 */
        const val MEDIUM_SAMPLE = 5
    }
}
