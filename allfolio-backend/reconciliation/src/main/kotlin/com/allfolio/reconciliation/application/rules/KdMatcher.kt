package com.allfolio.reconciliation.application.rules

import com.allfolio.reconciliation.application.RuleDiff
import com.allfolio.reconciliation.domain.KdValueType
import com.allfolio.reconciliation.infrastructure.entity.ReconKdEntity
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * Known Difference 매칭·허용 판정 (P2 #16, v2 스펙 §5) — 순수 로직.
 *
 * 매칭: (targetSymbol, targetField — null은 와일드카드) 일치 & runDate ∈ 유효기간 & useYn.
 * 판정: ABS |diff| ≤ allow / RATIO |diff|/|internal| ≤ allow (internal 0·null이면 RATIO 매칭 불가).
 * diffValue가 없는 차이(MISSING 계열)는 수치 허용 판정이 불가능하므로 흡수하지 않는다.
 */
object KdMatcher {

    fun match(kds: List<ReconKdEntity>, diff: RuleDiff, runDate: LocalDate): ReconKdEntity? {
        val diffValue = diff.diffValue ?: return null
        return kds.firstOrNull { kd ->
            kd.useYn &&
                !runDate.isBefore(kd.apldStrtDt) && !runDate.isAfter(kd.apldEndDt) &&
                (kd.targetSymbol == null || kd.targetSymbol.equals(diff.symbol, ignoreCase = true)) &&
                (kd.targetField == null || kd.targetField.equals(diff.fieldName, ignoreCase = true)) &&
                allows(kd, diffValue, diff.internalValue)
        }
    }

    private fun allows(kd: ReconKdEntity, diffValue: BigDecimal, internalValue: BigDecimal?): Boolean =
        when (kd.valueType) {
            KdValueType.ABS -> diffValue.abs() <= kd.allowValue
            KdValueType.RATIO -> {
                if (internalValue == null || internalValue.signum() == 0) false
                else diffValue.abs().divide(internalValue.abs(), 10, RoundingMode.HALF_UP) <= kd.allowValue
            }
        }
}
