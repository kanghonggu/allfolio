package com.allfolio.market.realestate

import com.allfolio.unifiedasset.infrastructure.jpa.RtmsDealCacheJpaRepository
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 캐시에서 단지·평형을 뽑는다.
 *
 * **해제 거래를 뺀다.** 성사되지 않은 거래만 있는 평형을 목록에 보여 주면, 사용자가 그걸
 * 고르고 나서 평가가 영원히 `null`이 된다.
 *
 * **거래 수를 함께 준다.** `(단지, 면적)`당 거래가 실측 분기 2건꼴이라, 사용자가 고른 평형에
 * 표본이 몇 건인지 알아야 "왜 평가가 안 나오는지"를 화면이 설명할 수 있다.
 */
@Service
class JpaComplexSearchService(
    private val deals: RtmsDealCacheJpaRepository,
) : ComplexSearchService {

    override fun search(sggCode: String, query: String?, limit: Int): List<ComplexView> {
        val rows = deals.findComplexRows(sggCode, query?.trim()?.takeIf { it.isNotEmpty() })

        return rows
            .groupBy { it.aptSeq }
            .entries
            // 거래가 많은 단지를 먼저 — 사용자가 찾는 것은 대개 거래가 있는 큰 단지다
            .sortedByDescending { (_, rs) -> rs.sumOf { it.dealCount } }
            .take(limit)
            .map { (aptSeq, rs) ->
                val head = rs.first()
                ComplexView(
                    aptSeq = aptSeq,
                    aptName = head.aptName,
                    umdName = head.umdName,
                    buildYear = head.buildYear,
                    // 면적은 작은 것부터 — 사용자가 평형을 크기 순으로 찾는다
                    areas = rs.sortedBy { it.exclusiveAreaM2 }.map {
                        ComplexAreaView(
                            exclusiveAreaM2 = it.exclusiveAreaM2,
                            approxPyeong = pyeong(it.exclusiveAreaM2),
                            dealCount = it.dealCount,
                        )
                    },
                )
            }
    }

    /**
     * 참고 표시용 평. **저장하지 않는다** — 이 값으로 매칭하면 전용/공급 모호성이 되살아난다.
     *
     * 소수 첫째 자리까지만 낸다. `25.4평`이면 충분하고, `25.41평`은 정밀해 보이지만
     * 그 정밀도가 매칭에 쓰인다는 오해를 부른다.
     */
    private fun pyeong(m2: BigDecimal): BigDecimal =
        m2.divide(ComplexSearchService.M2_PER_PYEONG, 1, RoundingMode.HALF_UP)
}
